package io.cloudsoft.opengamma.app;

import static brooklyn.event.basic.DependentConfiguration.attributeWhenReady;

import java.util.ArrayList;
import java.util.List;

import org.jclouds.cloudstack.domain.FirewallRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.enricher.basic.SensorTransformingEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.BasicStartable;
import brooklyn.entity.basic.BasicStartable.LocationsFilter;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityAndAttribute;
import brooklyn.entity.basic.EntityFactory;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.database.postgresql.PostgreSqlSpecs;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.event.Sensor;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.location.basic.PortRanges;
import brooklyn.location.jclouds.JcloudsLocationConfig;
import brooklyn.util.CommandLineUtil;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.net.Cidr;
import brooklyn.util.text.Identifiers;
import brooklyn.util.text.Strings;
import io.cloudsoft.networking.cloudstack.legacy.LegacyAbstractSubnetApp;
import io.cloudsoft.networking.cloudstack.legacy.LegacySubnetTier;
import io.cloudsoft.networking.cloudstack.loadbalancer.CloudStackLoadBalancer;
import io.cloudsoft.networking.subnet.SubnetTier;
import io.cloudsoft.opengamma.cluster.OpenGammaClusterFactory;
import io.cloudsoft.opengamma.server.OpenGammaMonitoringAggregation;

/**
 * Differences from {@link ElasticOpenGammaApplication}: creates an OpenGamma cluster with
 * broker and database in a single region. The cluster is created behind a subnet, and
 * necessary ports are forwarded (e.g. ActiveMQ broker's JMX port for Brooklyn). The default
 * Nginx load balancer is replaced with a CloudStack loadbalancer running in "SOURCE" mode.
 */
public class CloudStackOpenGammaApplication extends LegacyAbstractSubnetApp implements ClusteredOpenGammaApplication {

    private static final Logger LOG = LoggerFactory.getLogger(CloudStackOpenGammaApplication.class);
    private static final int OPEN_GAMMA_NODE_PORT = 8080;
    private static final int LOAD_BALANCER_PORT = 80;

    @Override
    public void init() {
        applyDefaultConfig();
        super.init();

        Cidr vpcCidr = getConfig(VPC_CIDR);
        Boolean useVpc = getConfig(USE_VPC);

        final LegacySubnetTier subnet = addChild(EntitySpec.create(LegacySubnetTier.class)
                .displayName("Subnet Tier (primary location)")
                .configure(LegacySubnetTier.SUBNET_CIDR, vpcCidr != null ? useVpc ? vpcCidr.subnet(101) : vpcCidr : null));

        // Define the stock service entities: a message bus broker and database server
        BasicStartable backend = subnet.addChild(EntitySpec.create(BasicStartable.class)
                .displayName("OpenGamma Back-End")
                .configure(BasicStartable.LOCATIONS_FILTER, LocationsFilter.USE_FIRST_LOCATION));

        final ActiveMQBroker broker = backend.addChild(EntitySpec.create(ActiveMQBroker.class));
        final PostgreSqlNode database = backend.addChild(PostgreSqlSpecs.spec()
                // make it a reasonably big DB instance
                .configure(SoftwareProcess.PROVISIONING_PROPERTIES.subKey(JcloudsLocationConfig.MIN_RAM.getName()), "8192")
                .configure(PostgreSqlNode.CREATION_SCRIPT_URL, "classpath:/io/cloudsoft/opengamma/config/create-brooklyn-db.sql")
                .configure(PostgreSqlNode.DISCONNECT_ON_STOP, true));
        logNewSensorValuesOn(broker,
                SubnetTier.PUBLIC_HOSTNAME,
                SubnetTier.DEFAULT_PUBLIC_HOSTNAME_AND_PORT,
                ActiveMQBroker.JMX_URL,
                ActiveMQBroker.JMX_PORT);

        EntityFactory<ControlledDynamicWebAppCluster> ogWebClusterFactory = new CloudStackClusterFactory(this, broker, database, subnet);
        ControlledDynamicWebAppCluster webCluster = ogWebClusterFactory.newEntity(MutableMap.of(), subnet);

        // bubble up sensors (KPIs and access info) - in single-cluster mode it all comes from cluster (or is hard-coded)
        OpenGammaMonitoringAggregation.promoteKpis(this, webCluster);
        setAttribute(OpenGammaMonitoringAggregation.REGIONS_COUNT, 1);
        this.addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(webCluster, WebAppServiceConstants.ROOT_URL));

        // Open port 80
        subnet.openFirewallPort(new EntityAndAttribute<String>(subnet, LegacySubnetTier.PUBLIC_HOSTNAME), 80,
                FirewallRule.Protocol.TCP, Cidr.UNIVERSAL);

    }

    /**
     * Extends {@link OpenGammaClusterFactory} and overrides
     * {@link io.cloudsoft.opengamma.cluster.OpenGammaClusterFactory#getClusterSpec() getClusterSpec} to
     * use a {@link CloudStackLoadBalancer} (rather than Nginx) and add an enricher.
     */
    private static class CloudStackClusterFactory extends OpenGammaClusterFactory {
        private final LegacySubnetTier subnet;

        CloudStackClusterFactory(CloudStackOpenGammaApplication owningApplication, ActiveMQBroker broker,
                PostgreSqlNode database, LegacySubnetTier subnet) {
            super(owningApplication, broker, database);
            this.subnet = subnet;
        }

        @Override
        public EntitySpec<ControlledDynamicWebAppCluster> getClusterSpec() {
            // Uses SOURCE, rather than ROUNDROBIN for load balancer algorithm. Round robin confuses
            // OpenGamma's session management.
            final EntitySpec<CloudStackLoadBalancer> loadBalancerSpec = EntitySpec.create(CloudStackLoadBalancer.class)
                    .configure(CloudStackLoadBalancer.LOAD_BALANCER_NAME, "opengamma-lb-"+System.getProperty("user.name")+"-"+ Identifiers.makeRandomId(8))
                    .configure(CloudStackLoadBalancer.PUBLIC_IP_ID, attributeWhenReady(subnet, LegacySubnetTier.PUBLIC_HOSTNAME_IP_ADDRESS_ID))
                    .configure(CloudStackLoadBalancer.PROXY_HTTP_PORT, PortRanges.fromInteger(LOAD_BALANCER_PORT))
                    .configure(CloudStackLoadBalancer.INSTANCE_PORT, OPEN_GAMMA_NODE_PORT)
                    .configure(CloudStackLoadBalancer.ALGORITHM, "SOURCE");

            return super.getClusterSpec()
                    .configure(ControlledDynamicWebAppCluster.CONTROLLER_SPEC, loadBalancerSpec)
                    .enricher(new SensorTransformingEnricher<String, String>(
                            subnet,
                            LegacySubnetTier.PUBLIC_HOSTNAME,
                            // Actually going to be set on the enriched entity: the webCluster.
                            LegacySubnetTier.DEFAULT_PUBLIC_HOSTNAME_AND_PORT,
                            new Function<String, String>() {
                                @Override
                                public String apply(String input) {
                                    return Strings.isBlank(input) ? input : input + ":" + LOAD_BALANCER_PORT;
                                }
                            }));
        }
    }

    // An entity lifecycle debugging aid
    private void logNewSensorValuesOn(Entity entity, Sensor... sensors) {
        final String entityName = entity.getDisplayName();
        for (Sensor<?> sensor : sensors) {
            final String sensorName = sensor.getName();
            subscribe(entity, sensor, new   SensorEventListener() {
                @Override
                public void onEvent(SensorEvent event) {
                    LOG.info("New event on {}/{}: {}", new Object[]{entityName, sensorName, event.getValue()});
                }
            });
        }
    }

    public static void main(String[] argv) {
        List<String> args = Lists.newArrayList(argv);
        String port =  CommandLineUtil.getCommandLineOption(args, "--port", "8081+");

        List<String> locations = new ArrayList<String>();
        while (true) {
            String l = CommandLineUtil.getCommandLineOption(args, "--location", null);
            if (l!=null) locations.add(l);
            else break;
        }
        if (locations.isEmpty())
            throw new IllegalStateException("No locations selected for deployment of " +
                    CloudStackOpenGammaApplication.class.getName());

        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                 .application(EntitySpec.create(StartableApplication.class, CloudStackOpenGammaApplication.class)
                         .displayName("OpenGamma Elastic"))
                 .webconsolePort(port)
                 .locations(locations)
                 .start();

        Entities.dumpInfo(launcher.getApplications());
    }

}

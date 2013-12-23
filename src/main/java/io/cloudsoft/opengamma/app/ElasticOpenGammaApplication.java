package io.cloudsoft.opengamma.app;

import static com.google.common.base.Preconditions.checkNotNull;
import io.cloudsoft.opengamma.CustomNginxControllerImpl;
import io.cloudsoft.opengamma.cluster.OpenGammaClusterFactory;
import io.cloudsoft.opengamma.server.OpenGammaMonitoringAggregation;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.catalog.Catalog;
import brooklyn.config.StringConfigMap;
import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.enricher.basic.SensorTransformingEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.BasicStartable;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityFactory;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.database.postgresql.PostgreSqlSpecs;
import brooklyn.entity.dns.geoscaling.GeoscalingDnsService;
import brooklyn.entity.group.DynamicFabric;
import brooklyn.entity.group.DynamicRegionsFabric;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.entity.proxy.AbstractController;
import brooklyn.entity.proxy.nginx.NginxController;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.proxying.EntityTypeRegistry;
import brooklyn.entity.trait.Changeable;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.location.basic.Locations;
import brooklyn.location.basic.PortRanges;
import brooklyn.location.jclouds.JcloudsLocationConfig;
import brooklyn.util.CommandLineUtil;
import brooklyn.util.collections.MutableMap;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

@Catalog(name="OpenGamma Analytics", description="Multi-region, elastic version of " +
		"leading open-source financial analytics package with default data-sources",
		iconUrl="classpath://io/cloudsoft/opengamma/opengamma.png")
public class ElasticOpenGammaApplication extends AbstractApplication implements ClusteredOpenGammaApplication {
    
    public static final Logger LOG = LoggerFactory.getLogger(ElasticOpenGammaApplication.class);
    
    public static final String DEFAULT_LOCATION = "localhost";

    /** build the application */
    @Override
    public void init() {
        if(Iterables.get(getManagementContext().getLocationManager().getLocations(), 0).getDisplayName().startsWith("interoute-")) {
            EntityTypeRegistry typeRegistry = getManagementContext().getEntityManager().getEntityTypeRegistry();
            typeRegistry.registerImplementation(NginxController.class, CustomNginxControllerImpl.class);
        }
        StringConfigMap config = getManagementContext().getConfig();
        
        // First define the stock service entities (message bus broker and database server) for OG
        BasicStartable backend = addChild(EntitySpec.create(BasicStartable.class)
                .displayName("OpenGamma Back-End")
                .configure(BasicStartable.LOCATIONS_FILTER, Locations.USE_FIRST_LOCATION));
        final ActiveMQBroker broker = backend.addChild(EntitySpec.create(ActiveMQBroker.class));
        final PostgreSqlNode database = backend.addChild(PostgreSqlSpecs.spec()
                // make it a reasonably big DB instance
                .configure(SoftwareProcess.PROVISIONING_PROPERTIES.subKey(JcloudsLocationConfig.MIN_RAM.getName()), "8192")
                .configure(PostgreSqlNode.CREATION_SCRIPT_URL, "classpath:/io/cloudsoft/opengamma/config/create-brooklyn-db.sql")
                .configure(PostgreSqlNode.DISCONNECT_ON_STOP, true));

        // Now add the server tier, either multi-region (fabric) or fixed single-region (cluster)

        // factory for creating the OG server cluster, passed to fabric, or used directly here to make a cluster   
        EntityFactory<ControlledDynamicWebAppCluster> ogWebClusterFactory = new OpenGammaClusterFactory(this, broker, database);

        // use fabric by default, unless no password for geoscaling is set
        String geoscalingPassword = config.getFirst("brooklyn.geoscaling.password");
        
        if (getConfig(SUPPORT_MULTIREGION) && geoscalingPassword!=null) {
            LOG.info("GeoScaling support detected. Running in multi-cloud mode.");
            
            GeoscalingDnsService geoDns = addChild(EntitySpec.create(GeoscalingDnsService.class)
                    .displayName("GeoScaling DNS")
                    .configure(GeoscalingDnsService.GEOSCALING_USERNAME, checkNotNull(config.getFirst("brooklyn.geoscaling.username"), "username"))
                    .configure(GeoscalingDnsService.GEOSCALING_PASSWORD, geoscalingPassword)
                    .configure(GeoscalingDnsService.GEOSCALING_PRIMARY_DOMAIN_NAME, checkNotNull(config.getFirst("brooklyn.geoscaling.primaryDomain"), "primaryDomain")) 
                    .configure(GeoscalingDnsService.GEOSCALING_SMART_SUBDOMAIN_NAME, checkNotNull(config.getFirst(MutableMap.of("defaultIfNone", "brooklyn"), "brooklyn.geoscaling.smartSubdomain"), "smartSubdomain"))
            		.configure(GeoscalingDnsService.RANDOMIZE_SUBDOMAIN_NAME, true));

            DynamicRegionsFabric webFabric = addChild(EntitySpec.create(DynamicRegionsFabric.class)
                    .displayName("Dynamic Regions Fabric")
                    .configure(DynamicFabric.FACTORY, ogWebClusterFactory)
                    .configure(AbstractController.PROXY_HTTP_PORT, PortRanges.fromCollection(ImmutableList.of(80,"8000+"))) );

            // tell GeoDNS what to monitor
            geoDns.setTargetEntityProvider(webFabric);

            // bubble up sensors (KPIs and access info), from WebFabric and GeoDNS
            OpenGammaMonitoringAggregation.aggregateOpenGammaClusterSensors(webFabric);
            OpenGammaMonitoringAggregation.promoteKpis(this, webFabric);
            addEnricher(new SensorTransformingEnricher<Integer,Integer>(webFabric, Changeable.GROUP_SIZE, 
                    OpenGammaMonitoringAggregation.REGIONS_COUNT, Functions.<Integer>identity()));
            addEnricher(new SensorPropagatingEnricher(geoDns, WebAppServiceConstants.ROOT_URL));
        } else {
            if (getConfig(SUPPORT_MULTIREGION))
                LOG.warn("No password set for GeoScaling. Creating "+this+" in single-cluster mode.");
            else
                LOG.info("Configured not to have multi-region support. Creating "+this+" in single-cluster mode.");
            
            Entity ogWebCluster = ogWebClusterFactory.newEntity(MutableMap.of(), this);
            
            // bubble up sensors (KPIs and access info) - in single-cluster mode it all comes from cluster (or is hard-coded)
            OpenGammaMonitoringAggregation.promoteKpis(this, ogWebCluster);
            setAttribute(OpenGammaMonitoringAggregation.REGIONS_COUNT, 1);
            addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(ogWebCluster,  
                    WebAppServiceConstants.ROOT_URL));
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
        if (locations.isEmpty()) locations.add(DEFAULT_LOCATION);
        if (locations.contains("localhost") && !LocalhostMachineProvisioningLocation.isSudoAllowed())
            throw new IllegalStateException("Detected attempt to run "+ElasticOpenGammaApplication.class+" on localhost when sudo is not enabled.\n" +
            		"Enable sudo and try again!");

        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                 .application(EntitySpec.create(StartableApplication.class, ElasticOpenGammaApplication.class)
                         .displayName("OpenGamma Elastic Multi-Region"))
                 .webconsolePort(port)
                 .locations(locations)
                 .start();
             
        Entities.dumpInfo(launcher.getApplications());
    }
}

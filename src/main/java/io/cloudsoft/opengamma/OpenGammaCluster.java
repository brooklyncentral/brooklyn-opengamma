package io.cloudsoft.opengamma;

import static com.google.common.base.Preconditions.checkNotNull;
import io.cloudsoft.opengamma.server.OpenGammaMonitoringAggregation;
import io.cloudsoft.opengamma.server.OpenGammaServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.catalog.Catalog;
import brooklyn.catalog.CatalogConfig;
import brooklyn.config.ConfigKey;
import brooklyn.config.StringConfigMap;
import brooklyn.enricher.HttpLatencyDetector;
import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.enricher.basic.SensorTransformingEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.BasicStartable;
import brooklyn.entity.basic.BasicStartable.LocationsFilter;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityFactory;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.database.postgresql.PostgreSqlSpecs;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.dns.geoscaling.GeoscalingDnsService;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.group.DynamicFabric;
import brooklyn.entity.group.DynamicRegionsFabric;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.entity.proxy.AbstractController;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.trait.Changeable;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.location.basic.PortRanges;
import brooklyn.policy.Policy;
import brooklyn.policy.autoscaling.AutoScalerPolicy;
import brooklyn.policy.ha.ServiceFailureDetector;
import brooklyn.policy.ha.ServiceReplacer;
import brooklyn.policy.ha.ServiceRestarter;
import brooklyn.util.CommandLineUtil;
import brooklyn.util.collections.MutableMap;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

@Catalog(name="OpenGamma Analytics", description="Multi-region, elastic version of " +
		"leading open-source financial analytics package with default data-sources",
		iconUrl="classpath://io/cloudsoft/opengamma/opengamma.png")
public class OpenGammaCluster extends AbstractApplication implements StartableApplication {
    
    private static final long serialVersionUID = 997984655016594134L;
    public static final Logger LOG = LoggerFactory.getLogger(OpenGammaCluster.class);
    
    public static final String DEFAULT_LOCATION = "localhost";

    @CatalogConfig(label="Multi-Region", priority=1)
    public static final ConfigKey<Boolean> SUPPORT_MULTIREGION = new BasicConfigKey<Boolean>(Boolean.class,
            "opengamma.multiregion", "Whether to run multi-region", true);

    @CatalogConfig(label="Auto-Scaling Enabled", priority=3)
    public static final ConfigKey<Boolean> ENABLE_AUTOSCALING = new BasicConfigKey<Boolean>(Boolean.class,
            "opengamma.autoscaling.enabled", "Whether to enable auto-scaling", true);

    @CatalogConfig(label="Minimum Cluster Size", priority=2.1)
    public static final ConfigKey<Integer> MIN_SIZE = ConfigKeys.newIntegerConfigKey(
            "opengamma.autoscaling.size.min", "Minimum number of compute intances per cluster (also initial size)", 2);

    @CatalogConfig(label="Maximum Cluster Size", priority=2.2)
    public static final ConfigKey<Integer> MAX_SIZE = ConfigKeys.newIntegerConfigKey(
            "opengamma.autoscaling.size.max", "Maximum number of compute intances per cluster", 5);

    @CatalogConfig(label="Views-per-Server Target", priority=3.1)
    public static final ConfigKey<Double> VIEWS_PER_SERVER_SCALING_TARGET = ConfigKeys.newDoubleConfigKey(
            "opengamma.autoscaling.viewsPerServer.target", "Number of views per server to trigger scaling up", 1.0d);

    /** build the application */
    @Override
    public void init() {
        StringConfigMap config = getManagementContext().getConfig();
        
        // First define the stock service entities (message bus broker and database server) for OG
        
        BasicStartable backend = addChild(EntitySpec.create(BasicStartable.class)
                .displayName("OpenGamma Back-End")
                .configure(BasicStartable.LOCATIONS_FILTER, LocationsFilter.USE_FIRST_LOCATION));
        final ActiveMQBroker broker = backend.addChild(EntitySpec.create(ActiveMQBroker.class));
        final PostgreSqlNode database = backend.addChild(postgresSpec()
                .configure(PostgreSqlNode.CREATION_SCRIPT_URL, "classpath:/io/cloudsoft/opengamma/config/create-brooklyn-db.sql"));

        // Now add the server tier, either multi-region (fabric) or fixed single-region (cluster)

        // factory for creating the OG server cluster, passed to fabric, or used directly here to make a cluster   
        EntityFactory<Entity> ogWebClusterFactory = new EntityFactory<Entity>() {
            @Override
            public Entity newEntity(@SuppressWarnings("rawtypes") Map flags, Entity parent) {
                ControlledDynamicWebAppCluster ogWebCluster = parent.addChild(EntitySpec.create(ControlledDynamicWebAppCluster.class)
                        .displayName("Load-Balanced Cluster") 
                        .configure(ControlledDynamicWebAppCluster.INITIAL_SIZE, 2)
                        .configure(ControlledDynamicWebAppCluster.MEMBER_SPEC, 
                                EntitySpec.create(OpenGammaServer.class).displayName("OpenGamma Server")
                            .configure(OpenGammaServer.BROKER, broker)
                            .configure(OpenGammaServer.DATABASE, database)) );
                
                initAggregatingMetrics(ogWebCluster);
                initResilience(ogWebCluster);
                initElasticity(ogWebCluster);
                return ogWebCluster;
            }
        };

        // use fabric by default, unless no password for geoscaling is set
        String geoscalingPassword = config.getFirst("brooklyn.geoscaling.password");
        
        if (getConfig(SUPPORT_MULTIREGION) && geoscalingPassword!=null) {
            log.info("GeoScaling support detected. Running in multi-cloud mode.");
            
            GeoscalingDnsService geoDns = addChild(EntitySpec.create(GeoscalingDnsService.class)
                    .displayName("GeoScaling DNS")
                    .configure("username", checkNotNull(config.getFirst("brooklyn.geoscaling.username"), "username"))
                    .configure("password", geoscalingPassword)
                    .configure("primaryDomainName", checkNotNull(config.getFirst("brooklyn.geoscaling.primaryDomain"), "primaryDomain")) 
                    .configure("smartSubdomainName", "brooklyn"));

            DynamicRegionsFabric webFabric = addChild(EntitySpec.create(DynamicRegionsFabric.class)
                    .displayName("Dynamic Regions Fabric")
                    .configure(DynamicFabric.FACTORY, ogWebClusterFactory)
                    .configure(AbstractController.PROXY_HTTP_PORT, PortRanges.fromCollection(ImmutableList.of(80,"8000+"))) );

            // tell GeoDNS what to monitor
            geoDns.setTargetEntityProvider(webFabric);

            // bubble up sensors (kpi's and access info), from WebFabric and GeoDNS
            OpenGammaMonitoringAggregation.aggregateOpenGammaClusterSensors(webFabric);
            OpenGammaMonitoringAggregation.promoteKpis(this, webFabric);
            addEnricher(new SensorTransformingEnricher<Integer,Integer>(webFabric, Changeable.GROUP_SIZE, 
                    OpenGammaMonitoringAggregation.REGIONS_COUNT, Functions.<Integer>identity()));
            addEnricher(new SensorPropagatingEnricher(geoDns, WebAppServiceConstants.ROOT_URL));
        } else {
            if (getConfig(SUPPORT_MULTIREGION))
                log.warn("No password set for GeoScaling. Creating "+this+" in single-cluster mode.");
            else
                log.info("Configured not to have multi-region support. Creating "+this+" in single-cluster mode.");
            
            Entity ogWebCluster = ogWebClusterFactory.newEntity(MutableMap.of(), this);
            
            // bubble up sensors (kpi's and access info) - in single-cluster mode it all comes from cluster (or is hard-coded)
            OpenGammaMonitoringAggregation.promoteKpis(this, ogWebCluster);
            setAttribute(OpenGammaMonitoringAggregation.REGIONS_COUNT, 1);
            addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(ogWebCluster,  
                    WebAppServiceConstants.ROOT_URL));
        }
    }

    /** can be overridden, e.g. to use chef entity */
    protected EntitySpec<? extends PostgreSqlNode> postgresSpec() {
        return PostgreSqlSpecs.spec();
    }

    /** aggregate metrics and selected KPI's */
    protected void initAggregatingMetrics(ControlledDynamicWebAppCluster web) {
        web.addEnricher(HttpLatencyDetector.builder().
                url(WebAppService.ROOT_URL).
                rollup(10, TimeUnit.SECONDS).
                build());
        OpenGammaMonitoringAggregation.aggregateOpenGammaServerSensors(web.getCluster());
    }


    /** this attaches a policy at each OG Server listening for ENTITY_FAILED,
     * attempting to _restart_ the process, and 
     * failing that attempting to _replace_ the entity (e.g. a new VM), and 
     * failing that setting the cluster "on-fire" */
    protected void initResilience(ControlledDynamicWebAppCluster web) {
        ((EntityLocal)web).subscribe(web.getCluster(), DynamicCluster.MEMBER_ADDED, new SensorEventListener<Entity>() {
            @Override
            public void onEvent(SensorEvent<Entity> addition) {
                initSoftwareProcess((SoftwareProcess)addition.getValue());
            }
        });
        web.getCluster().addPolicy(new ServiceReplacer(ServiceRestarter.ENTITY_RESTART_FAILED));
    }

    /** invoked whenever a new OpenGamma server is added (the server may not be started yet) */
    protected void initSoftwareProcess(SoftwareProcess p) {
        p.addPolicy(new ServiceFailureDetector());
        p.addPolicy(new ServiceRestarter(ServiceFailureDetector.ENTITY_FAILED));
    }

    /** configures scale-out and scale-back; in this case based on number of view processes active,
     * allowing an (artificially low) max of 1.2 per node, 
     * so as soon as you have 3 view processes a scale-out is forced */
    protected void initElasticity(ControlledDynamicWebAppCluster web) {
        boolean scalingEnabled = getConfig(ENABLE_AUTOSCALING);
        double target = getConfig(VIEWS_PER_SERVER_SCALING_TARGET);
        Policy policy = AutoScalerPolicy.builder().
                metric(OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT_PER_NODE).
                metricRange(target*0.9, target + 0.1).
                sizeRange(getConfig(MIN_SIZE), getConfig(MAX_SIZE)).
                build();
        web.getCluster().addPolicy(policy);
        if (!scalingEnabled) {
            policy.suspend();
            log.info("AutoScaler policy disabled when creating "+web);
        } else {
            log.info("AutoScaler policy (target "+target+") created for "+web);
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
            throw new IllegalStateException("Detected attempt to run "+OpenGammaCluster.class+" on localhost when sudo is not enabled.\n" +
            		"Enable sudo and try again!");

        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                 .application(EntitySpec.create(OpenGammaCluster.class)
                         .additionalInterfaces(StartableApplication.class)
                         .displayName("OpenGamma Elastic Multi-Region"))
                 .webconsolePort(port)
                 .locations(locations)
                 .start();
             
        Entities.dumpInfo(launcher.getApplications());
    }
}

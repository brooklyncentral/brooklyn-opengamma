package io.cloudsoft.opengamma;

import io.cloudsoft.opengamma.demo.OpenGammaDemoServer;
import io.cloudsoft.opengamma.demo.OpenGammaMonitoringAggregation;
import io.cloudsoft.opengamma.policy.ServiceFailureDetector;
import io.cloudsoft.opengamma.policy.ServiceReplacer;
import io.cloudsoft.opengamma.policy.ServiceRestarter;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.catalog.Catalog;
import brooklyn.catalog.CatalogConfig;
import brooklyn.config.ConfigKey;
import brooklyn.enricher.HttpLatencyDetector;
import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.entity.webapp.DynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.policy.autoscaling.AutoScalerPolicy;
import brooklyn.util.CommandLineUtil;

import com.google.common.collect.Lists;

@Catalog(name="OpenGamma Cluster", description="Open source risk management platform for capital markets - real-time market risk analytics and stress testing", iconUrl="classpath://opengamma-logo.png")
public class OpenGammaCluster extends AbstractApplication implements StartableApplication {

    public static final Logger LOG = LoggerFactory.getLogger(OpenGammaCluster.class);

    public static final String DEFAULT_LOCATION = "localhost";

    @CatalogConfig(label="Debug Mode", priority=2)
    public static final ConfigKey<Boolean> DEBUG_MODE = OpenGammaDemoServer.DEBUG_MODE;

    /** build the application */
    @Override
    public void init() {
        // Add external services (message bus broker and database server) for cluster
        // TODO make these more configurable
        ActiveMQBroker broker = addChild(EntitySpecs.spec(ActiveMQBroker.class));
        PostgreSqlNode database = addChild(EntitySpecs.spec(PostgreSqlNode.class));

        ControlledDynamicWebAppCluster web = addChild(
                EntitySpecs.spec(ControlledDynamicWebAppCluster.class)
                    .configure(ControlledDynamicWebAppCluster.INITIAL_SIZE, 2)
                    .configure(ControlledDynamicWebAppCluster.MEMBER_SPEC, 
                            EntitySpecs.spec(OpenGammaDemoServer.class)
                                    .displayName("OpenGamma Server")
                                    .configure(OpenGammaDemoServer.BROKER, broker)
                                    .configure(OpenGammaDemoServer.DATABASE, database))
                    .displayName("OpenGamma Server Cluster (Web/View/Calc)")
                );

        initAggregatingMetrics(web);
        initResilience(web);
        initElasticity(web);
    }

    /** aggregate metrics and selected KPI's */
    protected void initAggregatingMetrics(ControlledDynamicWebAppCluster web) {
        web.addEnricher(HttpLatencyDetector.builder().
                url(WebAppService.ROOT_URL).
                rollup(10, TimeUnit.SECONDS).
                build());
        OpenGammaMonitoringAggregation.aggregateOpenGammaServerSensors(web.getCluster());
        addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(web,  
                WebAppServiceConstants.ROOT_URL,
                DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW,
                HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW,
                OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT_PER_NODE));
    }

    /** this attaches a policy at each OG Server listening for ENTITY_FAILED,
     * attempting to _restart_ the process, and 
     * failing that attempting to _replace_ the entity (e.g. a new VM), and 
     * failing that setting the cluster "on-fire" */
    protected void initResilience(ControlledDynamicWebAppCluster web) {
        subscribe(web.getCluster(), DynamicCluster.MEMBER_ADDED, new SensorEventListener<Entity>() {
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
        web.getCluster().addPolicy(AutoScalerPolicy.builder().
                metric(OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT_PER_NODE).
                metricRange(0.8, 1.2).
                sizeRange(2, 5).
                build());
    }

    public static void main(String[] argv) {
        List<String> args = Lists.newArrayList(argv);
        String port =  CommandLineUtil.getCommandLineOption(args, "--port", "8081+");
        String location = CommandLineUtil.getCommandLineOption(args, "--location", DEFAULT_LOCATION);

        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                 .application(EntitySpecs.appSpec(OpenGammaCluster.class)
                         .displayName("OpenGamma Cluster Application"))
                 .webconsolePort(port)
                 .location(location)
                 .start();

        Entities.dumpInfo(launcher.getApplications());
    }
}

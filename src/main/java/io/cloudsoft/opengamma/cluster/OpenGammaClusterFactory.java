package io.cloudsoft.opengamma.cluster;

import io.cloudsoft.opengamma.app.ClusteredOpenGammaApplication;
import io.cloudsoft.opengamma.server.OpenGammaMonitoringAggregation;
import io.cloudsoft.opengamma.server.OpenGammaServer;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.enricher.HttpLatencyDetector;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.EntityFactory;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.policy.Policy;
import brooklyn.policy.autoscaling.AutoScalerPolicy;
import brooklyn.policy.ha.ServiceFailureDetector;
import brooklyn.policy.ha.ServiceReplacer;
import brooklyn.policy.ha.ServiceRestarter;
import io.cloudsoft.opengamma.server.SimulatedExamplesServer;

public class OpenGammaClusterFactory implements EntityFactory<ControlledDynamicWebAppCluster> {
    public static final Logger LOG = LoggerFactory.getLogger(OpenGammaClusterFactory.class);

    final ActiveMQBroker broker;
    final PostgreSqlNode database;
    final boolean scalingEnabled;
    final double viewsPerServerScalingTarget;
    final int minSize;
    final int maxSize;

    public OpenGammaClusterFactory(ClusteredOpenGammaApplication owningApplication,
            ActiveMQBroker broker, PostgreSqlNode database) {
        this(broker, database,
                owningApplication.getConfig(ClusteredOpenGammaApplication.ENABLE_AUTOSCALING),
                owningApplication.getConfig(ClusteredOpenGammaApplication.VIEWS_PER_SERVER_SCALING_TARGET),
                owningApplication.getConfig(ClusteredOpenGammaApplication.MIN_SIZE),
                owningApplication.getConfig(ClusteredOpenGammaApplication.MAX_SIZE));
    }
    public OpenGammaClusterFactory(ActiveMQBroker broker, PostgreSqlNode database, boolean scalingEnabled,
            double viewsPerServerScalingTarget, int minSize, int maxSize) {
        this.broker = broker;
        this.database = database;
        this.scalingEnabled = scalingEnabled;
        this.viewsPerServerScalingTarget = viewsPerServerScalingTarget;
        this.minSize = minSize;
        this.maxSize = maxSize;
    }

    @Override
    public ControlledDynamicWebAppCluster newEntity(@SuppressWarnings("rawtypes") Map flags, Entity parent) {
        ControlledDynamicWebAppCluster ogWebCluster = parent.addChild(getClusterSpec());
        initAggregatingMetrics(ogWebCluster);
        initResilience(ogWebCluster);
        initElasticity(ogWebCluster);
        return ogWebCluster;
    }

    public EntitySpec<ControlledDynamicWebAppCluster> getClusterSpec() {
        return EntitySpec.create(ControlledDynamicWebAppCluster.class)
                .displayName("Load-Balanced Cluster")
                .configure(ControlledDynamicWebAppCluster.INITIAL_SIZE, minSize)
                .configure(ControlledDynamicWebAppCluster.MEMBER_SPEC,
                        EntitySpec.create(SimulatedExamplesServer.class)
                                .displayName("OpenGamma Server")
                                .configure(OpenGammaServer.BROKER, broker)
                                .configure(OpenGammaServer.DATABASE, database));
    }

    /** aggregate metrics and selected KPIs */
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
        Policy policy = AutoScalerPolicy.builder()
                .metric(OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT_PER_NODE)
                .metricRange(viewsPerServerScalingTarget * 0.9, viewsPerServerScalingTarget + 0.1)
                .sizeRange(minSize, maxSize)
                .build();
        web.getCluster().addPolicy(policy);
        if (!scalingEnabled) {
            policy.suspend();
            LOG.info("AutoScaler policy disabled when creating "+web);
        } else {
            LOG.info("AutoScaler policy (target "+viewsPerServerScalingTarget+") created for "+web);
        }
    }

}

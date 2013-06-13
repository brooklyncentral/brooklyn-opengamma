package io.cloudsoft.opengamma;

import io.cloudsoft.opengamma.demo.OpenGammaDemoServer;
import io.cloudsoft.opengamma.demo.OpenGammaMonitoringAggregation;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.catalog.CatalogConfig;
import brooklyn.config.ConfigKey;
import brooklyn.enricher.HttpLatencyDetector;
import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.entity.webapp.DynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.util.CommandLineUtil;

import com.google.common.collect.Lists;

public class OpenGammaCluster extends AbstractApplication implements StartableApplication {
    
    public static final Logger LOG = LoggerFactory.getLogger(OpenGammaCluster.class);
    
    public static final String DEFAULT_LOCATION = "localhost";

    @CatalogConfig(label="Debug Mode", priority=2)
    public static final ConfigKey<Boolean> DEBUG_MODE = OpenGammaDemoServer.DEBUG_MODE;

    @Override
    public void init() {
        ControlledDynamicWebAppCluster web = addChild(
                EntitySpecs.spec(ControlledDynamicWebAppCluster.class)
                    .configure(ControlledDynamicWebAppCluster.INITIAL_SIZE, 2)
                    .configure(ControlledDynamicWebAppCluster.MEMBER_SPEC, 
                            EntitySpecs.spec(OpenGammaDemoServer.class).displayName("OpenGamma Server"))
                    .displayName("OpenGamma Server Cluster (Web/View/Calc)")
                );
        
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
    
    public static void main(String[] argv) {
        List<String> args = Lists.newArrayList(argv);
        String port =  CommandLineUtil.getCommandLineOption(args, "--port", "8081+");
        String location = CommandLineUtil.getCommandLineOption(args, "--location", DEFAULT_LOCATION);

        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                 .application(EntitySpecs.appSpec(OpenGammaCluster.class)
                         .displayName("OpenGamma Cluster Example"))
                 .webconsolePort(port)
                 .location(location)
                 .start();
             
        Entities.dumpInfo(launcher.getApplications());
    }
}

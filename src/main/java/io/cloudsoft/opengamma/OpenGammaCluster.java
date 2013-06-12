package io.cloudsoft.opengamma;

import io.cloudsoft.opengamma.demo.OpenGammaDemoServer;

import java.util.List;

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
import brooklyn.entity.webapp.WebAppServiceConstants;
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
                    .configure(ControlledDynamicWebAppCluster.MEMBER_SPEC, EntitySpecs.spec(OpenGammaDemoServer.class))
                );
        
        addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(web,  
                WebAppServiceConstants.ROOT_URL,
                DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW,
                HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW));
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

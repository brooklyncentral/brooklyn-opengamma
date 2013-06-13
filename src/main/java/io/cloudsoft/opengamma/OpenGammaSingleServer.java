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
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.entity.webapp.DynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.util.CommandLineUtil;

import com.google.common.collect.Lists;

public class OpenGammaSingleServer extends AbstractApplication implements StartableApplication {

    public static final Logger LOG = LoggerFactory.getLogger(OpenGammaSingleServer.class);

    public static final String DEFAULT_LOCATION = "localhost";

    @CatalogConfig(label="Debug Mode", priority=2)
    public static final ConfigKey<Boolean> DEBUG_MODE = OpenGammaDemoServer.DEBUG_MODE;

    @Override
    public void init() {
        // Add external services (message bus broker and database server)
        // TODO make these more configurable
        ActiveMQBroker broker = addChild(EntitySpecs.spec(ActiveMQBroker.class));
        PostgreSqlNode database = addChild(EntitySpecs.spec(PostgreSqlNode.class));

        // Add the OG server configured with external services
        OpenGammaDemoServer web = addChild(
                EntitySpecs.spec(OpenGammaDemoServer.class)
                        .displayName("OpenGamma Server")
                        .configure(OpenGammaDemoServer.BROKER, broker)
                        .configure(OpenGammaDemoServer.DATABASE, database));

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
                 .application(EntitySpecs.appSpec(OpenGammaSingleServer.class)
                         .displayName("OpenGamma Server Example"))
                 .webconsolePort(port)
                 .location(location)
                 .start();

        Entities.dumpInfo(launcher.getApplications());
    }
}

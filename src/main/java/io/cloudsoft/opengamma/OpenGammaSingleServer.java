package io.cloudsoft.opengamma;

import io.cloudsoft.opengamma.server.OpenGammaServer;

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
import brooklyn.entity.proxy.nginx.NginxController;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.proxying.EntityTypeRegistry;
import brooklyn.entity.webapp.DynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.util.CommandLineUtil;

import com.google.common.collect.Lists;

public class OpenGammaSingleServer extends AbstractApplication implements StartableApplication {

    public static final Logger LOG = LoggerFactory.getLogger(OpenGammaSingleServer.class);

    public static final String DEFAULT_LOCATION = "localhost";

    @CatalogConfig(label="Debug Mode", priority=2)
    public static final ConfigKey<Boolean> DEBUG_MODE = OpenGammaServer.DEBUG_MODE;

    @Override
    public void init() {
        EntityTypeRegistry typeRegistry = getManagementContext().getEntityManager().getEntityTypeRegistry();
        typeRegistry.registerImplementation(NginxController.class, CustomNginxControllerImpl.class);
        
        // Add external services (message bus broker and database server)
        // TODO make these more configurable
        ActiveMQBroker broker = addChild(EntitySpec.create(ActiveMQBroker.class));
        PostgreSqlNode database = addChild(EntitySpec.create(PostgreSqlNode.class)
                .configure(PostgreSqlNode.CREATION_SCRIPT_URL, "classpath:/io/cloudsoft/opengamma/config/create-brooklyn-db.sql"));

        // Add the OG server configured with external services
        OpenGammaServer web = addChild(
                EntitySpec.create(OpenGammaServer.class)
                        .displayName("OpenGamma Server")
                        .configure(OpenGammaServer.BROKER, broker)
                        .configure(OpenGammaServer.DATABASE, database));

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
                 .application(EntitySpec.create(OpenGammaSingleServer.class)
                         .displayName("OpenGamma Server Example"))
                 .webconsolePort(port)
                 .location(location)
                 .start();

        Entities.dumpInfo(launcher.getApplications());
    }
}

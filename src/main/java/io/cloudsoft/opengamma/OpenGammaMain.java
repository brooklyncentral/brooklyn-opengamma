package io.cloudsoft.opengamma;

import io.airlift.command.Command;
import io.airlift.command.Option;
import io.cloudsoft.opengamma.app.ElasticOpenGammaApplication;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.catalog.BrooklynCatalog;
import brooklyn.cli.Main;

import com.google.common.base.Objects.ToStringHelper;

/**
 * This class provides a static main entry point for launching a custom Brooklyn-based app.
 * <p>
 * It inherits the standard Brooklyn CLI options from {@link Main},
 * plus adds a few more shortcuts for favourite blueprints to the {@link LaunchCommand}.
 */
public class OpenGammaMain extends Main {
    
    private static final Logger log = LoggerFactory.getLogger(OpenGammaMain.class);
    
    public static final String DEFAULT_LOCATION = "localhost";

    public static void main(String... args) {
        log.debug("CLI invoked with args "+Arrays.asList(args));
        new OpenGammaMain().execCli(args);
    }

    @Override
    protected String cliScriptName() {
        return "start.sh";
    }
    
    @Override
    protected Class<? extends BrooklynCommand> cliLaunchCommand() {
        return LaunchCommand.class;
    }

    @Command(name = "launch", description = "Starts a server, and optionally an application. "
        + "Use e.g. --single or --cluster to launch one-node and clustered variants of the sample web applicaiton.")
    public static class LaunchCommand extends Main.LaunchCommand {

        // add these options to the LaunchCommand as shortcuts for our favourite applications
        
        @Option(name = { "--opengamma" }, description = "Launch OpenGamma as well as the server")
        public boolean launchApp;

        @Override
        public Void call() throws Exception {
            // process our CLI arguments
            if (launchApp) setAppToLaunch( ElasticOpenGammaApplication.class.getCanonicalName() );
            
            // now process the standard launch arguments
            return super.call();
        }

        @Override
        protected void populateCatalog(BrooklynCatalog catalog) {
            super.populateCatalog(catalog);
            catalog.addItem(ElasticOpenGammaApplication.class);
        }

        @Override
        public ToStringHelper string() {
            return super.string()
                    .add("opengamma", launchApp);
        }
    }
}

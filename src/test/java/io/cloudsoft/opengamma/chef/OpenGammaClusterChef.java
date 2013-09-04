package io.cloudsoft.opengamma.chef;

import io.cloudsoft.opengamma.OpenGammaCluster;

import java.util.ArrayList;
import java.util.List;

import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.chef.ChefConfig;
import brooklyn.entity.database.postgresql.PostgreSqlSpecs;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.util.CommandLineUtil;

import com.google.common.collect.Lists;

public class OpenGammaClusterChef extends OpenGammaCluster {

    // FIXME failing on EC2 due to chef error
    public static final String DEFAULT_LOCATION = "aws-ec27";
    
    @Override
    protected EntitySpec<? extends PostgreSqlNode> postgresSpec() {
        return PostgreSqlSpecs.specChef();
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
            throw new IllegalStateException("Detected attempt to run "+OpenGammaClusterChef.class+" on localhost when sudo is not enabled.\n" +
            		"Enable sudo and try again!");

        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                 .application(EntitySpec.create(OpenGammaClusterChef.class)
                         .additionalInterfaces(StartableApplication.class)
                         .displayName("OpenGamma Elastic Multi-Region")
                         .configure(ChefConfig.KNIFE_CONFIG_FILE, OpenGammaChefConfig.installBrooklynChefHostedConfig()))
                 .webconsolePort(port)
                 .locations(locations)
                 .start();
             
        Entities.dumpInfo(launcher.getApplications());
    }
}

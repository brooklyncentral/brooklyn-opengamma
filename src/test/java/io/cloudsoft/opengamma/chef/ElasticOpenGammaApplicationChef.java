package io.cloudsoft.opengamma.chef;

import io.cloudsoft.opengamma.app.ElasticOpenGammaApplication;

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

public class ElasticOpenGammaApplicationChef extends ElasticOpenGammaApplication {

    // FIXME failing on EC2 due to chef error
    public static final String DEFAULT_LOCATION = "aws-ec27";
    
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
            throw new IllegalStateException("Detected attempt to run "+ElasticOpenGammaApplicationChef.class+" on localhost when sudo is not enabled.\n" +
            		"Enable sudo and try again!");

        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                 .application(EntitySpec.create(ElasticOpenGammaApplicationChef.class)
                         .additionalInterfaces(StartableApplication.class)
                         .displayName("OpenGamma Elastic Multi-Region")
                         .configure(ChefConfig.KNIFE_CONFIG_FILE, OpenGammaChefConfig.installBrooklynChefHostedConfig()))
                 .webconsolePort(port)
                 .locations(locations)
                 .start();
             
        Entities.dumpInfo(launcher.getApplications());
    }
}

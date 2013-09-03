package io.cloudsoft.opengamma.chef;

import java.io.File;
import java.io.IOException;

import brooklyn.util.ResourceUtils;
import brooklyn.util.stream.InputStreamSupplier;

import com.google.common.base.Throwables;
import com.google.common.io.Files;

public class OpenGammaChefConfig {

    private static String defaultConfigFile = null; 
    public synchronized static String installBrooklynChefHostedConfig() {
        if (defaultConfigFile!=null) return defaultConfigFile;
        File tempDir = Files.createTempDir();
        ResourceUtils r = new ResourceUtils(OpenGammaChefConfig.class);
        try {
            for (String f: new String[] { "knife.rb", "brooklyn-tests.pem", "brooklyn-validator.pem" }) {
                Files.copy(InputStreamSupplier.fromString(r.getResourceAsString(
                        "classpath:///io/cloudsoft/opengamma/chef/"+f)),
                        new File(tempDir, f));
            }
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
        File knifeConfig = new File(tempDir, "knife.rb");
        defaultConfigFile = knifeConfig.getPath();
        return defaultConfigFile;
    }

}

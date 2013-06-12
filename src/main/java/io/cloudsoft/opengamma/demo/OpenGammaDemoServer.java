package io.cloudsoft.opengamma.demo;

import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.event.basic.BasicAttributeSensorAndConfigKey;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.util.flags.SetFromFlag;

@ImplementedBy(OpenGammaDemoServerImpl.class)
public interface OpenGammaDemoServer extends SoftwareProcess, WebAppService {

    @SetFromFlag("debug")
    public static final ConfigKey<Boolean> DEBUG_MODE = new BasicConfigKey<Boolean>(Boolean.class,
            "opengamma.debug", "Whether to run in debug mode", true); 

    @SetFromFlag("version")
    BasicConfigKey<String> SUGGESTED_VERSION =
            new BasicConfigKey<String>(SoftwareProcess.SUGGESTED_VERSION, "1.2.0");
    
    @SetFromFlag("downloadUrl")
    BasicAttributeSensorAndConfigKey<String> DOWNLOAD_URL = new BasicAttributeSensorAndConfigKey<String>(
            SoftwareProcess.DOWNLOAD_URL, "http://developers.opengamma.com/downloads/${version}/opengamma-demo-${version}-bin.tar.gz");

}

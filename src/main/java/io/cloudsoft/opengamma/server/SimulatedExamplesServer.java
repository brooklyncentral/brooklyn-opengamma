package io.cloudsoft.opengamma.server;

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.util.flags.SetFromFlag;

@ImplementedBy(SimulatedExamplesServerImpl.class)
public interface SimulatedExamplesServer extends OpenGammaServer {

    @SetFromFlag("configToCopy")
    ConfigKey<Map<String, String>> CONFIG_FILES_TO_COPY = ConfigKeys.newConfigKeyWithDefault(
            OpenGammaServer.CONFIG_FILES_TO_COPY,
            ImmutableMap.of(
                    "classpath:/io/cloudsoft/opengamma/config/brooklyn/brooklyn-infrastructure-spring.xml",
                    "config/brooklyn/brooklyn-infrastructure-spring.xml",
                    "classpath:/io/cloudsoft/opengamma/config/brooklyn/brooklyn.ini",
                    "config/brooklyn/brooklyn.ini",
                    "classpath:/io/cloudsoft/opengamma/config/jetty-spring.xml",
                    "config/common/jetty-spring.xml",
                    "classpath:/io/cloudsoft/opengamma/scripts/og-brooklyn.sh",
                    "scripts/og-brooklyn.sh"));

    @SetFromFlag("configToTemplateAndCopy")
    ConfigKey<Map<String, String>> CONFIG_FILES_TO_TEMPLATE_AND_COPY = ConfigKeys.newConfigKeyWithDefault(
            OpenGammaServer.CONFIG_FILES_TO_TEMPLATE_AND_COPY,
            ImmutableMap.of(
                    "classpath:/io/cloudsoft/opengamma/config/brooklyn/brooklyn.properties",
                    "config/brooklyn/brooklyn.properties",
                    "classpath:/io/cloudsoft/opengamma/config/brooklyn/toolcontext-example.properties",
                    "config/brooklyn/toolcontext-example.properties"));

    @SetFromFlag("extraScripts")
    ConfigKey<List<String>> EXTRA_SCRIPTS = ConfigKeys.newConfigKeyWithDefault(
            OpenGammaServer.EXTRA_SCRIPTS,
            ImmutableList.of("classpath:/io/cloudsoft/opengamma/scripts/init-brooklyn-db.sh"));

    @SetFromFlag("startScript")
    ConfigKey<String> SERVER_START_SCRIPT = ConfigKeys.newConfigKeyWithDefault(
            OpenGammaServer.SERVER_START_SCRIPT,
            "scripts/og-brooklyn.sh");

}

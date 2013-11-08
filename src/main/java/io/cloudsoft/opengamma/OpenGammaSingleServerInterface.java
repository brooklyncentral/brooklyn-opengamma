package io.cloudsoft.opengamma;

import io.cloudsoft.opengamma.server.OpenGammaServer;
import brooklyn.catalog.CatalogConfig;
import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.proxying.ImplementedBy;

@ImplementedBy(OpenGammaSingleServer.class)
public interface OpenGammaSingleServerInterface extends StartableApplication {

    public static final String DEFAULT_LOCATION = "localhost";

    @CatalogConfig(label="Debug Mode", priority=2)
    public static final ConfigKey<Boolean> DEBUG_MODE = OpenGammaServer.DEBUG_MODE;
}

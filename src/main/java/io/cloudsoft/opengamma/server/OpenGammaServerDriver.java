package io.cloudsoft.opengamma.server;

import brooklyn.entity.java.JavaSoftwareProcessDriver;
import brooklyn.entity.proxying.ImplementedBy;

@ImplementedBy(OpenGammaServerImpl.class)
public interface OpenGammaServerDriver extends JavaSoftwareProcessDriver {

}

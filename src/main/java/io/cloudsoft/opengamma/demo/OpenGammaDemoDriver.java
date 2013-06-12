package io.cloudsoft.opengamma.demo;

import brooklyn.entity.java.JavaSoftwareProcessDriver;
import brooklyn.entity.proxying.ImplementedBy;

@ImplementedBy(OpenGammaDemoServerImpl.class)
public interface OpenGammaDemoDriver extends JavaSoftwareProcessDriver {

}

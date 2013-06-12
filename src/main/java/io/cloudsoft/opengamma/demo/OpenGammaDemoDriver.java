package io.cloudsoft.opengamma.demo;

import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.basic.SoftwareProcessDriver;
import brooklyn.entity.proxying.ImplementedBy;

@ImplementedBy(OpenGammaDemoServerImpl.class)
public interface OpenGammaDemoDriver extends SoftwareProcessDriver {

}

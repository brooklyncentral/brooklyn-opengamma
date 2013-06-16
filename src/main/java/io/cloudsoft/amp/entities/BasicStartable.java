package io.cloudsoft.amp.entities;

import brooklyn.entity.Entity;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.trait.Startable;

/**
 * Provides a pass-through Startable entity used for keeping hierarchies tidy. 
 */
@ImplementedBy(BasicStartableImpl.class)
public interface BasicStartable extends Entity, Startable {
    
}

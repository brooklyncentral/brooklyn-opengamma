package io.cloudsoft.amp.entities;

import java.util.Collection;
import java.util.Map;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.trait.StartableMethods;
import brooklyn.location.Location;
import brooklyn.util.MutableMap;

public class BasicStartableImpl extends AbstractEntity implements BasicStartable {

    public BasicStartableImpl() {
        this(MutableMap.of(), null);
    }

    public BasicStartableImpl(Map properties) {
        this (properties, null);
    }
    
    public BasicStartableImpl(Entity parent) {
        this(MutableMap.of(), parent);
    }

    @Deprecated
    public BasicStartableImpl(Map properties, Entity parent) {
        super(properties, parent);
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        StartableMethods.start(this, locations);
    }

    @Override
    public void stop() {
        StartableMethods.stop(this);
    }

    @Override
    public void restart() {
        StartableMethods.restart(this);
    }
    
}

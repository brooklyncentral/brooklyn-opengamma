package io.cloudsoft.opengamma.fabric;

import java.util.Arrays;
import java.util.Map;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.group.DynamicFabricImpl;
import brooklyn.entity.trait.Startable;
import brooklyn.location.Location;
import brooklyn.management.Task;
import brooklyn.util.MutableMap;

public class DynamicRegionsFabricImpl extends DynamicFabricImpl implements DynamicRegionsFabric {

    public DynamicRegionsFabricImpl() {
        this(MutableMap.of(), null);
    }

    public DynamicRegionsFabricImpl(Map properties) {
        this (properties, null);
    }
    
    public DynamicRegionsFabricImpl(Entity parent) {
        this(MutableMap.of(), parent);
    }

    public DynamicRegionsFabricImpl(Map properties, Entity parent) {
        super(properties, parent);
    }

    @Override
    public String addRegion(String location) {
        Preconditions.checkNotNull(location, "location");
        Location l = getManagementContext().getLocationRegistry().resolve(location);
        addLocations(Arrays.asList(l));
        
        Entity e = addCluster(l);
        ((EntityInternal)e).addLocations(Arrays.asList(l));
        if (e instanceof Startable) {
            Task task = e.invoke(Startable.START, ImmutableMap.of("locations", ImmutableList.of(l)));
            task.getUnchecked();
        }
        return e.getId();
    }
    
}

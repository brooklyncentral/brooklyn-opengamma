package io.cloudsoft.amp.entities;

import java.io.Serializable;
import java.util.List;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.trait.Startable;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.location.Location;

import com.google.common.collect.ImmutableList;

/**
 * Provides a pass-through Startable entity used for keeping hierarchies tidy. 
 */
@ImplementedBy(BasicStartableImpl.class)
public interface BasicStartable extends Entity, Startable {
    
    public interface LocationsFilter extends Serializable {
        public List<Location> filterForContext(List<Location> locations, Object context);
        
        public static final LocationsFilter USE_FIRST_LOCATION = new LocationsFilter() {
            @Override
            public List<Location> filterForContext(List<Location> locations, Object context) {
                if (locations.size()<=1) return locations;
                return ImmutableList.of(locations.get(0));
            }
        };
    }

    public static final ConfigKey<LocationsFilter> LOCATIONS_FILTER = new BasicConfigKey<LocationsFilter>(LocationsFilter.class,
            "brooklyn.locationsFilter", "Provides a hook for customizing locations to be used for a given context");
    
}

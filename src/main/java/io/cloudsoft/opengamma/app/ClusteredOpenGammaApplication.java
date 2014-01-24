package io.cloudsoft.opengamma.app;

import brooklyn.catalog.CatalogConfig;
import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;

@ImplementedBy(ElasticOpenGammaApplication.class)
public interface ClusteredOpenGammaApplication extends StartableApplication {

    @CatalogConfig(label="Multi-Region", priority=1)
    public static final ConfigKey<Boolean> SUPPORT_MULTIREGION = ConfigKeys.newBooleanConfigKey(
            "opengamma.multiregion", "Whether to run multi-region", true);

    @CatalogConfig(label="Auto-Scaling Enabled", priority=3)
    public static final ConfigKey<Boolean> ENABLE_AUTOSCALING = ConfigKeys.newBooleanConfigKey(
            "opengamma.autoscaling", "Whether to enable auto-scaling", true);

    @CatalogConfig(label="Subnet enabled", priority=4)
    public static final ConfigKey<Boolean> ENABLE_SUBNET = ConfigKeys.newBooleanConfigKey(
            "opengamma.subnet", "Whether to start the cluster in a subnet", false);

    @CatalogConfig(label="Minimum Cluster Size", priority=2.1)
    public static final ConfigKey<Integer> MIN_SIZE = ConfigKeys.newIntegerConfigKey(
            "opengamma.autoscaling.size.min", "Minimum number of compute intances per cluster (also initial size)", 2);

    @CatalogConfig(label="Maximum Cluster Size", priority=2.2)
    public static final ConfigKey<Integer> MAX_SIZE = ConfigKeys.newIntegerConfigKey(
            "opengamma.autoscaling.size.max", "Maximum number of compute instances per cluster", 5);

    @CatalogConfig(label="Views-per-Server Target", priority=3.1)
    public static final ConfigKey<Double> VIEWS_PER_SERVER_SCALING_TARGET = ConfigKeys.newDoubleConfigKey(
            "opengamma.autoscaling.viewsPerServer.target", "Number of views per server to trigger scaling up", 1.0d);
}

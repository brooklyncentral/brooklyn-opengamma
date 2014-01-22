package io.cloudsoft.opengamma.server;

import java.util.List;

import brooklyn.enricher.CustomAggregatingEnricher;
import brooklyn.enricher.Enrichers;
import brooklyn.enricher.HttpLatencyDetector;
import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.enricher.basic.SensorTransformingEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.group.DynamicFabric;
import brooklyn.entity.java.UsesJavaMXBeans;
import brooklyn.entity.trait.Changeable;
import brooklyn.entity.webapp.DynamicWebAppCluster;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.util.collections.MutableMap;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

public class OpenGammaMonitoringAggregation {

    public static final AttributeSensor<Double> PROCESSING_TIME_PER_SECOND_LAST = OpenGammaServer.PROCESSING_TIME_PER_SECOND_LAST;
    public static final AttributeSensor<Double> PROCESSING_TIME_PER_SECOND_IN_WINDOW = OpenGammaServer.PROCESSING_TIME_PER_SECOND_IN_WINDOW;
    public static final AttributeSensor<Integer> VIEW_PROCESSES_COUNT = OpenGammaServer.VIEW_PROCESSES_COUNT;
    
    public static final AttributeSensor<Double> PROCESSING_TIME_PER_SECOND_LAST_PER_NODE =
            new BasicAttributeSensor<Double>(Double.class, "webapp.reqs.processingTime.perSec.last.perNode", "Mean across cluster of percentage of time spent processing requests (most recent period; cf CPU utilisation)");

    public static final AttributeSensor<Double> PROCESSING_TIME_PER_SECOND_IN_WINDOW_PER_NODE =
            new BasicAttributeSensor<Double>(Double.class, "webapp.reqs.processingTime.perSec.windowed.perNode", "Mean across cluster of percentage of time spent processing requests (windowed over time period)");

    public static final AttributeSensor<Double> VIEW_PROCESSES_COUNT_PER_NODE =
            new BasicAttributeSensor<Double>(Double.class, "opengamma.views.processes.active.count.perNode", "Mean across cluster of number of active view processes");

    public static final AttributeSensor<Double> PROCESS_CPU_TIME_FRACTION_IN_WINDOW = UsesJavaMXBeans.PROCESS_CPU_TIME_FRACTION_IN_WINDOW;
    
    public static final AttributeSensor<Double> PROCESS_CPU_TIME_FRACTION_IN_WINDOW_PER_NODE =
            new BasicAttributeSensor<Double>(Double.class, "java.metrics.processCpuTime.fraction.avg.per.node", "Mean across cluster of the fraction of time (since the last event) consumed as cpu time");

    public static final BasicAttributeSensor<Integer> REGIONS_COUNT =
            new BasicAttributeSensor<Integer>(Integer.class, "opengamma.regions.count", "Number of active regions hosting OpenGamma");

    public static final BasicAttributeSensor<Integer> OG_SERVER_COUNT =
            new BasicAttributeSensor<Integer>(Integer.class, "opengamma.servers.count", "Number of active servers (web/calc) hosting OpenGamma");

    public static void aggregateOpenGammaServerSensors(Entity cluster) {

        cluster.addEnricher(Enrichers.builder()
                .aggregating(PROCESSING_TIME_PER_SECOND_LAST)
                .fromMembers()
                .publishing(PROCESSING_TIME_PER_SECOND_LAST_PER_NODE)
                .computingAverage()
                .defaultValueForUnreportedSensors(null)
                .build());
        cluster.addEnricher(Enrichers.builder()
                .aggregating(PROCESSING_TIME_PER_SECOND_IN_WINDOW)
                .fromMembers()
                .publishing(PROCESSING_TIME_PER_SECOND_IN_WINDOW_PER_NODE)
                .computingAverage()
                .defaultValueForUnreportedSensors(null)
                .build());
        cluster.addEnricher(Enrichers.builder()
                .aggregating(PROCESS_CPU_TIME_FRACTION_IN_WINDOW)
                .fromMembers()
                .publishing(PROCESS_CPU_TIME_FRACTION_IN_WINDOW_PER_NODE)
                .computingAverage()
                .defaultValueForUnreportedSensors(null)
                .build());
        cluster.addEnricher(Enrichers.builder()
                .aggregating(VIEW_PROCESSES_COUNT)
                .fromMembers()
                .publishing(VIEW_PROCESSES_COUNT_PER_NODE)
                .computingAverage()
                .defaultValueForUnreportedSensors(0)
                .build());
    }

    public static void aggregateOpenGammaClusterSensors(DynamicFabric webFabric) {
        // at fabric, take the total for ViewProcesses and Reqs/Sec;
        // and take avg for reqLatency (note: simple avg -- assuming all regions equal)
        webFabric.addEnricher(CustomAggregatingEnricher.newSummingEnricher(MutableMap.of("allMembers", true),
                OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT,
                OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT, 0, null));
        webFabric.addEnricher(CustomAggregatingEnricher.newSummingEnricher(MutableMap.of("allMembers", true), 
                DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW,
                DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW, null, null));
        webFabric.addEnricher(CustomAggregatingEnricher.newSummingEnricher(MutableMap.of("allMembers", true),
                OpenGammaMonitoringAggregation.OG_SERVER_COUNT,
                OpenGammaMonitoringAggregation.OG_SERVER_COUNT, null, null));
        webFabric.addEnricher(CustomAggregatingEnricher.newAveragingEnricher(MutableMap.of("allMembers", true), 
                HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW,
                HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW, null, null));
    }

    public static void promoteKpis(Entity target, Entity webMetricsSource) {
        target.addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(webMetricsSource,  
                DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW,
                HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW,
                OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT,
                OpenGammaMonitoringAggregation.OG_SERVER_COUNT));
    }

    // TODO use StringFunctions.surround when available
    public static Function<String,String> surround(final String prefix, final String suffix) {
        Preconditions.checkNotNull(prefix);
        Preconditions.checkNotNull(suffix);
        return new Function<String,String>() {
            @Override
            public String apply(String input) {
                if (input==null) return null;
                return prefix+input+suffix;
            }
        };
    }
    
}

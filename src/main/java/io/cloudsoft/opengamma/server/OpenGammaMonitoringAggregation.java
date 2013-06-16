package io.cloudsoft.opengamma.server;

import java.util.List;

import javax.annotation.Nullable;

import brooklyn.enricher.CustomAggregatingEnricher;
import brooklyn.enricher.HttpLatencyDetector;
import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.enricher.basic.SensorTransformingEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.dns.geoscaling.GeoscalingDnsService;
import brooklyn.entity.group.DynamicFabric;
import brooklyn.entity.java.UsesJavaMXBeans;
import brooklyn.entity.trait.Changeable;
import brooklyn.entity.webapp.DynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.util.MutableMap;

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

    public static final AttributeSensor<Double> PROCESS_CPU_TIME_FRACTION_IN_WINDOW = UsesJavaMXBeans.AVG_PROCESS_CPU_TIME_FRACTION;
    
    public static final AttributeSensor<Double> PROCESS_CPU_TIME_FRACTION_IN_WINDOW_PER_NODE =
            new BasicAttributeSensor<Double>(Double.class, "java.metrics.processCpuTime.fraction.avg.per.node", "Mean across cluster of the fraction of time (since the last event) consumed as cpu time");

    public static final BasicAttributeSensor<Integer> REGIONS_COUNT =
            new BasicAttributeSensor<Integer>(Integer.class, "opengamma.regions.count", "Number of active regions hosting OpenGamma");

    public static final BasicAttributeSensor<Integer> OG_SERVER_COUNT =
            new BasicAttributeSensor<Integer>(Integer.class, "opengamma.servers.count", "Number of active servers (web/calc) hosting OpenGamma");

    public static void aggregateOpenGammaServerSensors(Entity cluster) {
        List<? extends List<? extends AttributeSensor<? extends Number>>> summingEnricherSetup = ImmutableList.of(
                ImmutableList.of(PROCESSING_TIME_PER_SECOND_LAST, PROCESSING_TIME_PER_SECOND_LAST),
                ImmutableList.of(PROCESSING_TIME_PER_SECOND_IN_WINDOW, PROCESSING_TIME_PER_SECOND_IN_WINDOW),
                ImmutableList.of(VIEW_PROCESSES_COUNT, VIEW_PROCESSES_COUNT),
                ImmutableList.of(PROCESS_CPU_TIME_FRACTION_IN_WINDOW, PROCESS_CPU_TIME_FRACTION_IN_WINDOW)
        );
        
        List<? extends List<? extends AttributeSensor<? extends Number>>> averagingEnricherSetup = ImmutableList.of(
                ImmutableList.of(PROCESSING_TIME_PER_SECOND_LAST, PROCESSING_TIME_PER_SECOND_LAST_PER_NODE),
                ImmutableList.of(PROCESSING_TIME_PER_SECOND_IN_WINDOW, PROCESSING_TIME_PER_SECOND_IN_WINDOW_PER_NODE),
                ImmutableList.of(VIEW_PROCESSES_COUNT, VIEW_PROCESSES_COUNT_PER_NODE),
                ImmutableList.of(PROCESS_CPU_TIME_FRACTION_IN_WINDOW, PROCESS_CPU_TIME_FRACTION_IN_WINDOW_PER_NODE)
        );
        
        for (List<? extends AttributeSensor<? extends Number>> es : summingEnricherSetup) {
            AttributeSensor<? extends Number> t = es.get(0);
            AttributeSensor<? extends Number> total = es.get(1);
            CustomAggregatingEnricher<?,?> totaller = CustomAggregatingEnricher.newSummingEnricher(MutableMap.of("allMembers", true), t, total);
            cluster.addEnricher(totaller);
        }
        
        for (List<? extends AttributeSensor<? extends Number>> es : averagingEnricherSetup) {
            AttributeSensor<Number> t = (AttributeSensor<Number>) es.get(0);
            AttributeSensor<Double> average = (AttributeSensor<Double>) es.get(1);
            CustomAggregatingEnricher<?,?> averager = CustomAggregatingEnricher.newAveragingEnricher(MutableMap.of("allMembers", true), t, average);
            cluster.addEnricher(averager);
        }
        
        cluster.addEnricher(new SensorTransformingEnricher<Integer, Integer>(cluster, Changeable.GROUP_SIZE, OG_SERVER_COUNT, Functions.<Integer>identity()));
    }

    public static void aggregateOpenGammaClusterSensors(DynamicFabric webFabric) {
        // at fabric, take the total for ViewProcesses and Reqs/Sec;
        // and take avg for reqLatency (note: simple avg -- assuming all regions equal)
        webFabric.addEnricher(CustomAggregatingEnricher.newSummingEnricher(MutableMap.of("allMembers", true), 
                OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT, OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT));
        webFabric.addEnricher(CustomAggregatingEnricher.newSummingEnricher(MutableMap.of("allMembers", true), 
                DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW, DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW));
        webFabric.addEnricher(CustomAggregatingEnricher.newSummingEnricher(MutableMap.of("allMembers", true), 
                OpenGammaMonitoringAggregation.OG_SERVER_COUNT, OpenGammaMonitoringAggregation.OG_SERVER_COUNT));
        webFabric.addEnricher(CustomAggregatingEnricher.newAveragingEnricher(MutableMap.of("allMembers", true), 
                HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW, HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW));
    }

    public static void promoteKpis(Entity target, Entity webMetricsSource) {
        target.addEnricher(SensorPropagatingEnricher.newInstanceListeningTo(webMetricsSource,  
                DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW,
                HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW,
                OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT,
                OpenGammaMonitoringAggregation.OG_SERVER_COUNT));
    }

    // TODO move to StringFunctions
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

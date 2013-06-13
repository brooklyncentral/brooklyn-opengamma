package io.cloudsoft.opengamma.demo;

import java.util.List;

import brooklyn.enricher.CustomAggregatingEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.java.UsesJavaMXBeans;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.util.MutableMap;

import com.google.common.collect.ImmutableList;

public class OpenGammaMonitoringAggregation {

    public static final AttributeSensor<Double> PROCESSING_TIME_PER_SECOND_LAST = OpenGammaDemoServer.PROCESSING_TIME_PER_SECOND_LAST;
    public static final AttributeSensor<Double> PROCESSING_TIME_PER_SECOND_IN_WINDOW = OpenGammaDemoServer.PROCESSING_TIME_PER_SECOND_IN_WINDOW;
    public static final AttributeSensor<Integer> VIEW_PROCESSES_COUNT = OpenGammaDemoServer.VIEW_PROCESSES_COUNT;
    
    public static final AttributeSensor<Double> PROCESSING_TIME_PER_SECOND_LAST_PER_NODE =
            new BasicAttributeSensor<Double>(Double.class, "webapp.reqs.processingTime.perSec.last.perNode", "Mean across cluster of percentage of time spent processing requests (most recent period; cf CPU utilisation)");

    public static final AttributeSensor<Double> PROCESSING_TIME_PER_SECOND_IN_WINDOW_PER_NODE =
            new BasicAttributeSensor<Double>(Double.class, "webapp.reqs.processingTime.perSec.windowed.perNode", "Mean across cluster of percentage of time spent processing requests (windowed over time period)");

    public static final AttributeSensor<Double> VIEW_PROCESSES_COUNT_PER_NODE =
            new BasicAttributeSensor<Double>(Double.class, "opengamma.views.processes.active.count.perNode", "Mean across cluster of number of active view processes");

    public static final AttributeSensor<Double> PROCESS_CPU_TIME_FRACTION_IN_WINDOW = UsesJavaMXBeans.AVG_PROCESS_CPU_TIME_FRACTION;
    
    public static final AttributeSensor<Double> PROCESS_CPU_TIME_FRACTION_IN_WINDOW_PER_NODE =
            new BasicAttributeSensor<Double>(Double.class, "java.metrics.processCpuTime.fraction.avg.per.node", "Mean across cluster of the fraction of time (since the last event) consumed as cpu time");

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
    }
}

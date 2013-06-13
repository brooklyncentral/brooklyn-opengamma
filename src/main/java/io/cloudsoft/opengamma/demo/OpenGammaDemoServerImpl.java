package io.cloudsoft.opengamma.demo;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.enricher.RollingTimeWindowMeanEnricher;
import brooklyn.enricher.TimeWeightedDeltaEnricher;
import brooklyn.entity.basic.SoftwareProcessImpl;
import brooklyn.entity.java.JavaAppUtils;
import brooklyn.entity.java.UsesJmx;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.entity.webapp.WebAppServiceMethods;
import brooklyn.event.adapter.JmxObjectNameAdapter;
import brooklyn.event.adapter.JmxSensorAdapter;
import brooklyn.event.feed.http.HttpFeed;
import brooklyn.event.feed.http.HttpPollConfig;
import brooklyn.event.feed.http.HttpValueFunctions;
import brooklyn.event.feed.jmx.JmxHelper;
import brooklyn.location.access.BrooklynAccessUtils;
import brooklyn.util.MutableMap;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.net.HostAndPort;

public class OpenGammaDemoServerImpl extends SoftwareProcessImpl implements OpenGammaDemoServer, UsesJmx {

    private static final Logger log = LoggerFactory.getLogger(OpenGammaDemoServerImpl.class);
    
    private HttpFeed httpFeed;
    private JmxObjectNameAdapter jettyStatsHandler;

    @Override
    public Class getDriverInterface() {
        return OpenGammaDemoDriver.class;
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();

        HostAndPort hp = BrooklynAccessUtils.getBrooklynAccessibleAddress(this, getAttribute(HTTP_PORT));
        String rootUrl = "http://"+hp.getHostText()+":"+hp.getPort()+"/";
        setAttribute(ROOT_URL, rootUrl);
        
        httpFeed = HttpFeed.builder()
                .entity(this)
                .period(1000)
                .baseUri(rootUrl)
                .poll(new HttpPollConfig<Boolean>(SERVICE_UP)
                        .onSuccess(HttpValueFunctions.responseCodeEquals(200))
                        .onError(Functions.constant(false)))
                .build();

    }
    
    @Override
    protected void postStart() {
        super.postStart();
        
        // do all this after service up, to prevent warnings
        
        // TODO migrate JMX routines to brooklyn 6 syntax
        
        Map flags = MutableMap.of("period", 5000);
        JmxSensorAdapter jmx = sensorRegistry.register(new JmxSensorAdapter(flags));

        JavaAppUtils.connectMXBeanSensors(this, jmx);
        
        jettyStatsHandler = jmx.objectName("com.opengamma.jetty:service=HttpConnector");
        // have to explicitly turn on, see in postStart
        jettyStatsHandler.attribute("Running").subscribe(SERVICE_UP);
        jettyStatsHandler.attribute("Requests").subscribe(REQUEST_COUNT);
        // these two from jetty not available from opengamma bean:
//        jettyStatsHandler.attribute("requestTimeTotal").subscribe(TOTAL_PROCESSING_TIME);
//        jettyStatsHandler.attribute("responsesBytesTotal").subscribe(BYTES_SENT);
        // these two might be custom OpenGamma
        jettyStatsHandler.attribute("ConnectionsDurationTotal").subscribe(TOTAL_PROCESSING_TIME);
        jettyStatsHandler.attribute("ConnectionsDurationMax").subscribe(MAX_PROCESSING_TIME);

        WebAppServiceMethods.connectWebAppServerPolicies(this);
        JavaAppUtils.connectJavaAppServerPolicies(this);

        addEnricher(new TimeWeightedDeltaEnricher<Integer>(this,
                WebAppServiceConstants.TOTAL_PROCESSING_TIME, PROCESSING_TIME_PER_SECOND_LAST, 1));
        addEnricher(new RollingTimeWindowMeanEnricher<Double>(this,
                PROCESSING_TIME_PER_SECOND_LAST, PROCESSING_TIME_PER_SECOND_IN_WINDOW,
                WebAppServiceConstants.REQUESTS_PER_SECOND_WINDOW_PERIOD));

        JmxObjectNameAdapter opengammaViewHandler = jmx.objectName("com.opengamma:type=ViewProcessor,name=ViewProcessor main");
        opengammaViewHandler.attribute("NumberOfViewProcesses").subscribe(VIEW_PROCESSES_COUNT);
        
        JmxObjectNameAdapter opengammaCalcHandler = jmx.objectName("com.opengamma:type=CalculationNodes,name=local");
        opengammaCalcHandler.attribute("TotalJobCount").subscribe(CALC_JOB_COUNT);
        opengammaCalcHandler.attribute("TotalNodeCount").subscribe(CALC_NODE_COUNT);

        // job count is some temporal measure already
//        addEnricher(new RollingTimeWindowMeanEnricher<Double>(this,
//                CALC_JOB_COUNT, CALC_JOB_RATE,
//                60*1000));
        
        // If MBean is unreachable, then mark as service-down
        opengammaViewHandler.reachable().poll(new Function<Boolean,Void>() {
                @Override public Void apply(Boolean input) {
                    if (input != null && Boolean.FALSE.equals(input)) {
                        Boolean prev = setAttribute(SERVICE_UP, false);
                        if (Boolean.TRUE.equals(prev)) {
                            LOG.warn("Could not reach {} over JMX, marking service-down", OpenGammaDemoServerImpl.this);
                        } else {
                            if (LOG.isDebugEnabled()) LOG.debug("Could not reach {} over JMX, service-up was previously {}", OpenGammaDemoServerImpl.this, prev);
                        }
                    }
                    return null;
                }});
        
        Object jettyStatsOnResult = new JmxHelper(this).operation(JmxHelper.createObjectName("com.opengamma.jetty:service=HttpConnector"), 
                "setStatsOn", true);
        log.debug("result of setStatsOn for "+this+": "+jettyStatsOnResult);
    }
    
    @Override
    protected void disconnectSensors() {
        super.disconnectSensors();
        if (httpFeed != null) httpFeed.stop();
    }

}

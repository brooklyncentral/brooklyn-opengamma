package io.cloudsoft.opengamma.server;

import java.util.Map;

import org.jclouds.compute.domain.OsFamily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.enricher.RollingTimeWindowMeanEnricher;
import brooklyn.enricher.TimeWeightedDeltaEnricher;
import brooklyn.entity.basic.SoftwareProcessImpl;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.java.JavaAppUtils;
import brooklyn.entity.java.UsesJmx;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.entity.webapp.WebAppServiceMethods;
import brooklyn.event.feed.http.HttpFeed;
import brooklyn.event.feed.http.HttpPollConfig;
import brooklyn.event.feed.http.HttpValueFunctions;
import brooklyn.event.feed.jmx.JmxAttributePollConfig;
import brooklyn.event.feed.jmx.JmxFeed;
import brooklyn.event.feed.jmx.JmxHelper;
import brooklyn.location.MachineProvisioningLocation;
import brooklyn.location.access.BrooklynAccessUtils;
import brooklyn.location.jclouds.templates.PortableTemplateBuilder;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;

import com.google.common.base.Functions;
import com.google.common.net.HostAndPort;

public class OpenGammaServerImpl extends SoftwareProcessImpl implements OpenGammaServer, UsesJmx {

    private static final Logger log = LoggerFactory.getLogger(OpenGammaServerImpl.class);
    
    private HttpFeed httpFeed;
    private ActiveMQBroker broker;
    private PostgreSqlNode database;

    @Override
    public void init() {
        broker = getConfig(BROKER);
        database = getConfig(DATABASE);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Class getDriverInterface() {
        return OpenGammaServerDriver.class;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected Map<String,Object> obtainProvisioningFlags(MachineProvisioningLocation location) {
        Map flags = super.obtainProvisioningFlags(location);
        flags.put("templateBuilder", new PortableTemplateBuilder()
            // need a beefy machine
            .os64Bit(true)
            .minRam(8192)
            // either should work... however ubuntu not available in GCE
//          .osFamily(OsFamily.UBUNTU).osVersionMatches("12.04")
//          .osFamily(OsFamily.CENTOS)
            );
        return flags;
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();

        HostAndPort hp = BrooklynAccessUtils.getBrooklynAccessibleAddress(this, getAttribute(HTTP_PORT));
        log.debug("HostAndPort seen during connectSensors " + hp);
        String rootUrl = "http://"+hp.getHostText()+":"+hp.getPort()+"/";
        setAttribute(ROOT_URL, rootUrl);
        
        httpFeed = HttpFeed.builder()
                .entity(this)
                .period(1000)
                .baseUri(rootUrl)
                .poll(new HttpPollConfig<Boolean>(SERVICE_UP)
                        .onSuccess(HttpValueFunctions.responseCodeEquals(200))
                        .onFailureOrException(Functions.constant(false)))
                .build();
    }
    
    @Override
    protected void postStart() {
        super.postStart();
        
            String ogJettyStatsMbeanName = "com.opengamma.jetty:service=HttpConnector";

            JmxFeed.builder().entity(this).period(Duration.ONE_SECOND)
                    .pollAttribute(new JmxAttributePollConfig<Boolean>(SERVICE_UP)
                            .objectName(ogJettyStatsMbeanName)
                            .attributeName("Running")
                            .setOnFailureOrException(false))
                    .pollAttribute(new JmxAttributePollConfig<Integer>(REQUEST_COUNT)
                            .objectName(ogJettyStatsMbeanName)
                            .attributeName("Requests"))
        // these two from jetty not available from opengamma bean:
//        jettyStatsHandler.attribute("requestTimeTotal").subscribe(TOTAL_PROCESSING_TIME);
//        jettyStatsHandler.attribute("responsesBytesTotal").subscribe(BYTES_SENT);
                    .pollAttribute(new JmxAttributePollConfig<Integer>(TOTAL_PROCESSING_TIME)
                            .objectName(ogJettyStatsMbeanName)
                            .attributeName("ConnectionsDurationTotal"))
                    .pollAttribute(new JmxAttributePollConfig<Integer>(MAX_PROCESSING_TIME)
                            .objectName(ogJettyStatsMbeanName)
                            .attributeName("ConnectionsDurationMax"))
                            
                    .pollAttribute(new JmxAttributePollConfig<Integer>(VIEW_PROCESSES_COUNT)
                            .objectName("com.opengamma:type=ViewProcessor,name=ViewProcessor main")
                            .attributeName("NumberOfViewProcesses"))
                            
                    .pollAttribute(new JmxAttributePollConfig<Integer>(CALC_JOB_COUNT)
                            .objectName("com.opengamma:type=CalculationNodes,name=local")
                            .attributeName("TotalJobCount"))
                    .pollAttribute(new JmxAttributePollConfig<Integer>(CALC_NODE_COUNT)
                            .objectName("com.opengamma:type=CalculationNodes,name=local")
                            .attributeName("TotalNodeCount"))
                            
                    .build();
            
        JavaAppUtils.connectMXBeanSensors(this);
        JavaAppUtils.connectJavaAppServerPolicies(this);
        WebAppServiceMethods.connectWebAppServerPolicies(this);

        addEnricher(new TimeWeightedDeltaEnricher<Integer>(this,
                WebAppServiceConstants.TOTAL_PROCESSING_TIME, PROCESSING_TIME_PER_SECOND_LAST, 1));
        addEnricher(new RollingTimeWindowMeanEnricher<Double>(this,
                PROCESSING_TIME_PER_SECOND_LAST, PROCESSING_TIME_PER_SECOND_IN_WINDOW,
                WebAppServiceMethods.DEFAULT_WINDOW_DURATION));

        // turn stats on, allowing a few sleep-then-retries in case the server isn't yet up
        for (int i=3; i<=0; i--) {
            try {
                // many of the stats only are available once we explicitly turn them on
                Object jettyStatsOnResult = new JmxHelper(this).operation(JmxHelper.createObjectName("com.opengamma.jetty:service=HttpConnector"), 
                    "setStatsOn", true);
                log.debug("result of setStatsOn for "+this+": "+jettyStatsOnResult);
                break;
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                if (i==0)
                    throw Exceptions.propagate(e);
                Time.sleep(Duration.TEN_SECONDS);
            }
        }
    }
    
    @Override
    protected void disconnectSensors() {
        super.disconnectSensors();
        if (httpFeed != null) httpFeed.stop();
    }

    /** HTTP port number for Jetty web service. */
    public Integer getHttpPort() { return getAttribute(HTTP_PORT); }

    /** HTTPS port number for Jetty web service. */
    public Integer getHttpsPort() { return getAttribute(HTTPS_PORT); }

    /** {@inheritDoc} */
    @Override
    public ActiveMQBroker getBroker() { return broker; }

    /** {@inheritDoc} */
    @Override
    public PostgreSqlNode getDatabase() { return database; }
}

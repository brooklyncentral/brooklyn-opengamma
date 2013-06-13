package io.cloudsoft.opengamma.demo;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.event.basic.BasicAttributeSensorAndConfigKey;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.event.basic.PortAttributeSensorAndConfigKey;
import brooklyn.location.basic.PortRanges;
import brooklyn.util.flags.SetFromFlag;

@ImplementedBy(OpenGammaDemoServerImpl.class)
public interface OpenGammaDemoServer extends SoftwareProcess, WebAppService {

    @SetFromFlag("debug")
    ConfigKey<Boolean> DEBUG_MODE = new BasicConfigKey<Boolean>(Boolean.class,
            "opengamma.debug", "Whether to run in debug mode", true);

    PortAttributeSensorAndConfigKey EMBEDDED_MESSAGING_PORT = new PortAttributeSensorAndConfigKey(
            "opengamma.embedded.msg.port", "Embedded messaging service port", PortRanges.fromString("61616+"));

    // give it 2m to start up, by default
    ConfigKey<Integer> START_TIMEOUT = new BasicConfigKey<Integer>(ConfigKeys.START_TIMEOUT,
                2*60);

    @SetFromFlag("version")
    ConfigKey<String> SUGGESTED_VERSION = new BasicConfigKey<String>(SoftwareProcess.SUGGESTED_VERSION, "1.2.0");

    @SetFromFlag("downloadUrl")
    BasicAttributeSensorAndConfigKey<String> DOWNLOAD_URL = new BasicAttributeSensorAndConfigKey<String>(
            SoftwareProcess.DOWNLOAD_URL, "http://developers.opengamma.com/downloads/${version}/opengamma-demo-${version}-bin.tar.gz");

    @SetFromFlag("broker")
    ConfigKey<Entity> BROKER = new BasicConfigKey<Entity>(Entity.class,
            "opengamma.services.message-bus.entity", "The entity representing the OpenGamma message bus broker");

    @SetFromFlag("database")
    ConfigKey<Entity> DATABASE = new BasicConfigKey<Entity>(Entity.class,
            "opengamma.services.database.entity", "The entity representing the OpenGamma database server");

    AttributeSensor<Integer> VIEW_PROCESSES_COUNT =
            new BasicAttributeSensor<Integer>(Integer.class, "opengamma.views.processes.active.count", "Number of active view processes");

    AttributeSensor<Integer> CALC_JOB_COUNT =
            new BasicAttributeSensor<Integer>(Integer.class, "opengamma.calc.jobs.count", "Calc jobs total");

    // "count" is some temporal value already
//   BasicAttributeSensor<Double> CALC_JOB_RATE =
//            new BasicAttributeSensor<Double>(Double.class, "opengamma.calc.jobs.rate", "Calc jobs per second");

    AttributeSensor<Integer> CALC_NODE_COUNT =
            new BasicAttributeSensor<Integer>(Integer.class, "opengamma.calc.nodes.count", "Calc nodes total (default 8 per server)");

    AttributeSensor<Double> PROCESSING_TIME_PER_SECOND_LAST =
            new BasicAttributeSensor<Double>(Double.class, "webapp.reqs.processingTime.perSec.last", "Percentage of time spent processing requests (most recent period; cf CPU utilisation)");

    AttributeSensor<Double> PROCESSING_TIME_PER_SECOND_IN_WINDOW =
            new BasicAttributeSensor<Double>(Double.class, "webapp.reqs.processingTime.perSec.windowed", "Percentage of time spent processing requests (windowed over time period)");

}

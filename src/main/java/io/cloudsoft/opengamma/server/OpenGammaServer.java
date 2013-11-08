package io.cloudsoft.opengamma.server;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.BrooklynConfigKeys;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.event.basic.BasicAttributeSensorAndConfigKey;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.util.flags.SetFromFlag;

@ImplementedBy(OpenGammaServerImpl.class)
public interface OpenGammaServer extends SoftwareProcess, WebAppService {

    @SetFromFlag("debug")
    ConfigKey<Boolean> DEBUG_MODE = new BasicConfigKey<Boolean>(Boolean.class,
            "opengamma.debug", "Whether to run in debug mode", true);

    // give it 2m to start up, by default
    ConfigKey<Integer> START_TIMEOUT = ConfigKeys.newConfigKeyWithDefault(BrooklynConfigKeys.START_TIMEOUT, 2*60);

    @SetFromFlag("version")
    ConfigKey<String> SUGGESTED_VERSION = ConfigKeys.newConfigKeyWithDefault(SoftwareProcess.SUGGESTED_VERSION, "2.1.0");

    @SetFromFlag("downloadUrl")
    BasicAttributeSensorAndConfigKey<String> DOWNLOAD_URL = new BasicAttributeSensorAndConfigKey<String>(
            SoftwareProcess.DOWNLOAD_URL, "http://developers.opengamma.com/downloads/${version}/examples-simulated-${version}-server.tar.bz2");
//            SoftwareProcess.DOWNLOAD_URL, "http://developers.opengamma.com/downloads/${version}/opengamma-demo-${version}-bin.tar.gz");
    // http://developers.opengamma.com/downloads/2.1.0/opengamma-demo-2.1.0-bin.tar.gz
    // http://developers.opengamma.com/downloads/2.1.0/examples-simulated-2.1.0-server.tar.bz2

    @SetFromFlag("downloadArchiveSubpath")
    ConfigKey<String> DOWNLOAD_ARCHIVE_SUBPATH = ConfigKeys.newStringConfigKey(
            "download.archive.subpath", "Path segment(s) which must be traversed from the downloaded archive to find the real content", "examples-simulated-${version}");

    @SetFromFlag("broker")
    ConfigKey<ActiveMQBroker> BROKER = new BasicConfigKey<ActiveMQBroker>(ActiveMQBroker.class,
            "opengamma.services.message-bus.entity", "The entity representing the OpenGamma message bus broker");

    @SetFromFlag("database")
    ConfigKey<PostgreSqlNode> DATABASE = new BasicConfigKey<PostgreSqlNode>(PostgreSqlNode.class,
            "opengamma.services.database.entity", "The entity representing the OpenGamma database server");

    AttributeSensor<Boolean> DATABASE_INITIALIZED =
        new BasicAttributeSensor<Boolean>(Boolean.class, "opengamma.db.completed", "OG database completely initialised");
    
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

    /** The OpenGamma message bus broker entity. */
    ActiveMQBroker getBroker();

    /** The OpenGamma database server entity. */
    PostgreSqlNode getDatabase();

}

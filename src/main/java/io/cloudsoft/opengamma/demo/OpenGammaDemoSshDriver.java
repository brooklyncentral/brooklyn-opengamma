package io.cloudsoft.opengamma.demo;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import brooklyn.BrooklynVersion;
import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.drivers.downloads.DownloadResolver;
import brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.DependentConfiguration;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.MutableMap;
import brooklyn.util.ResourceUtils;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.internal.ssh.SshTool;
import brooklyn.util.jmx.jmxrmi.JmxRmiAgent;
import brooklyn.util.ssh.CommonCommands;
import brooklyn.util.task.Tasks;

import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;

public class OpenGammaDemoSshDriver extends JavaSoftwareProcessSshDriver implements OpenGammaDemoDriver {

    private static final String OPENGAMMA_SUBDIR = "opengamma";
    private static final String SCRIPT_SUBDIR = OPENGAMMA_SUBDIR + "/scripts";
    private static final String CONFIG_SUBDIR = OPENGAMMA_SUBDIR + "/config";
    private static final String COMMON_SUBDIR = CONFIG_SUBDIR + "/common";
    private static final String BROOKLYN_SUBDIR = CONFIG_SUBDIR + "/brooklyn";

    public OpenGammaDemoSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    // FIXME should not have to jump through these hoops
    // unintuitive that .get() doesn't work (because the task isn't submitted)
    private <T> T attributeWhenReady(ConfigKey<? extends Entity> target, AttributeSensor<T> sensor) {
        try {
            return Tasks.resolveValue(
                    DependentConfiguration.attributeWhenReady(entity.getConfig(target), sensor),
                    sensor.getType(),
                    entity.getExecutionContext(),
                    "Getting "+sensor+" from "+target);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    /** Blocking call to return the {@link ActiveMqBroker#ADDRESS host} for the {@link OpenGammaDemoServer#BROKER broker}. */
    public String getBrokerAddress() {
        return attributeWhenReady(OpenGammaDemoServer.BROKER, ActiveMQBroker.ADDRESS);
    }

    /** Return the {@link ActiveMqBroker#OPEN_WIRE_PORT port} for the {@link OpenGammaDemoServer#BROKER broker}. */
    public Integer getBrokerPort() {
        return attributeWhenReady(OpenGammaDemoServer.BROKER, ActiveMQBroker.OPEN_WIRE_PORT);
    }

    /** Return the {@code host:port} location for the {@link OpenGammaDemoServer#BROKER broker}. */
    public String getBrokerLocation() {
        String address = getBrokerAddress();
        Integer port = getBrokerPort();
        HostAndPort broker = HostAndPort.fromParts(address, port);
        return broker.toString();
    }

    /** Blocking call to return the {@code host:port} location for the {@link OpenGammaDemoServer#DATABASE database}. */
    public String getDatabaseLocation() {
        String address = attributeWhenReady(OpenGammaDemoServer.DATABASE, PostgreSqlNode.ADDRESS);
        Integer port = attributeWhenReady(OpenGammaDemoServer.DATABASE, PostgreSqlNode.POSTGRESQL_PORT);
        HostAndPort database = HostAndPort.fromParts(address, port);
        return database.toString();
    }

    @Override
    public void install() {
        DownloadResolver resolver = Entities.newDownloader(this);
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();

        List<String> commands = ImmutableList.<String>builder()
                .addAll(CommonCommands.downloadUrlAs(urls, saveAs))
                .add(CommonCommands.INSTALL_TAR)
                .add("tar xvfz "+saveAs)
                .build();

        newScript(INSTALLING)
                .updateTaskAndFailOnNonZeroResultCode()
                .body.append(commands).execute();
    }

    @Override
    public void customize() {
        installJava();

        DownloadResolver resolver = Entities.newDownloader(this);
        // Copy the install files to the run-dir
        newScript(CUSTOMIZING)
            .updateTaskAndFailOnNonZeroResultCode()
            .body.append("cp -r "+getInstallDir()+"/"+resolver.getUnpackedDirectoryName("opengamma")+" "+"opengamma")
            // create the dirs where we will put config files
            .body.append("mkdir -p " + COMMON_SUBDIR)
            .body.append("mkdir -p " + BROOKLYN_SUBDIR)
            .execute();

        String[] fileNames = {
                "brooklyn-bin.properties",
                "brooklyn-infrastructure-spring.xml",
                "brooklyn-viewprocessor-spring.xml",
                "brooklyn.ini",
                "brooklyn.properties",
        };
        for (String name : fileNames) {
            String contents = processTemplate("classpath:/io/cloudsoft/opengamma/config/brooklyn/" + name);
            // TODO use Urls.mergePath (brooklyn 0.6)
            String destination = String.format("%s/%s/%s", getRunDir(), BROOKLYN_SUBDIR, name);
            // TODO brooklyn 0.6 use KnownSizeInputStream.of(contents) (and also below)
            getMachine().copyTo(new StringReader(contents), destination);
        }

        copyResource("classpath:/io/cloudsoft/opengamma/config/jetty-spring.xml",
                getRunDir() + "/" + COMMON_SUBDIR + "/jetty-spring.xml");
        copyResource(MutableMap.of(SshTool.PROP_PERMISSIONS.getName(), "0755"), 
                "classpath:/io/cloudsoft/opengamma/scripts/og-brooklyn.sh",
                getRunDir() + "/" + SCRIPT_SUBDIR + "/og-brooklyn.sh");

        String contents = processTemplate("classpath:/io/cloudsoft/opengamma/scripts/init-brooklyn-db.sh");
        String destination = String.format("%s/%s/%s", getRunDir(), SCRIPT_SUBDIR, "init-brooklyn-db.sh");
        getMachine().copyTo(MutableMap.of(SshTool.PROP_PERMISSIONS.getName(), "0755"), new StringReader(contents), destination);

        newScript(CUSTOMIZING)
                .updateTaskAndFailOnNonZeroResultCode()
                .body.append("cd opengamma", "scripts/init-brooklyn-db.sh")
                .execute();
        
        /*
         * CODE FROM HERE DOWN TO LAUNCH copied from ActiveMQ -- 
         * TODO replace with off-the-shelf conveniences when using brooklyn 0.6.0 (snapshot)
         */
        // Copy JMX agent Jar to server
        getMachine().copyTo(new ResourceUtils(this).getResourceFromUrl(getJmxRmiAgentJarUrl()), getJmxRmiAgentJarDestinationFilePath());
    }

    public String getJmxRmiAgentJarBasename() {
        return "brooklyn-jmxrmi-agent-" + BrooklynVersion.get() + ".jar";
    }

    public String getJmxRmiAgentJarUrl() {
        return "classpath://" + getJmxRmiAgentJarBasename();
    }

    public String getJmxRmiAgentJarDestinationFilePath() {
        return getRunDir() + "/" + getJmxRmiAgentJarBasename();
    }

    @Override
    protected Map<String, ?> getJmxJavaSystemProperties() {
        MutableMap<String, ?> opts = MutableMap.copyOf(super.getJmxJavaSystemProperties());
        if (opts != null && opts.size() > 0) {
            opts.remove("com.sun.management.jmxremote.port");
        }
        return opts;
    }

    /**
     * Return any JVM arguments required, other than the -D defines returned by {@link #getJmxJavaSystemProperties()}
     */
    protected List<String> getJmxJavaConfigOptions() {
        List<String> result = new ArrayList<String>();
        // TODO do this based on config property in UsesJmx
        String jmxOpt = String.format("-javaagent:%s -D%s=%d -D%s=%d -Djava.rmi.server.hostname=%s",
                getJmxRmiAgentJarDestinationFilePath(),
                JmxRmiAgent.JMX_SERVER_PORT_PROPERTY, getJmxPort(),
                JmxRmiAgent.RMI_REGISTRY_PORT_PROPERTY, getRmiServerPort(),
                getHostname());
        result.add(jmxOpt);
        return result;
    }

    @Override
    public void launch() {
        newScript(LAUNCHING)
                .updateTaskAndFailOnNonZeroResultCode()
                .body.append("cd opengamma", "nohup scripts/og-brooklyn.sh start")
                .execute();
    }


    @Override
    public boolean isRunning() {
        return newScript(MutableMap.of("usePidFile", "opengamma/og-examples.pid"), CHECK_RUNNING)
                // XXX ps --pid is not portable so can't use their scripts
                // .body.append("cd opengamma", "scripts/og-examples.sh status").
                .execute() == 0;
    }

    @Override
    public void stop() {
        newScript(MutableMap.of("usePidFile", "opengamma/og-examples.pid"), STOPPING)
                // XXX ps --pid is not portable so can't use their scripts
                // .body.append("cd opengamma", "scripts/og-examples.sh stop").
                .execute();
    }

    @Override
    protected String getLogFileLocation() {
        return null;
    }

    @Override
    public Map<String, String> getShellEnvironment() {
        Map<String,String> env = super.getShellEnvironment();

        // rename JAVA_OPTS to what OG scripts expect
        String jopts = env.remove("JAVA_OPTS");
        if (jopts != null) env.put("EXTRA_JVM_OPTS", jopts);

        return env;
    }
}

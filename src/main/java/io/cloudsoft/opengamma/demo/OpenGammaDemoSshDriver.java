package io.cloudsoft.opengamma.demo;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import brooklyn.BrooklynVersion;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.drivers.downloads.DownloadResolver;
import brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.event.basic.DependentConfiguration;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.MutableMap;
import brooklyn.util.ResourceUtils;
import brooklyn.util.jmx.jmxrmi.JmxRmiAgent;
import brooklyn.util.ssh.CommonCommands;

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

    // TODO use DependentConfiguration.attributeWhenReady

    /** Return the {@link ActiveMqBroker#ADDRESS host} for the {@link OpenGammaDemoServer#BROKER broker}. */
    public String getBrokerAddress() {
        return entity.getConfig(OpenGammaDemoServer.BROKER).getAttribute(ActiveMQBroker.ADDRESS);
    }

    /** Return the {@link ActiveMqBroker#OPEN_WIRE_PORT port} for the {@link OpenGammaDemoServer#BROKER broker}. */
    public Integer getBrokerPort() {
        return entity.getConfig(OpenGammaDemoServer.BROKER).getAttribute(ActiveMQBroker.OPEN_WIRE_PORT);
    }

    /** Return the {@code host:port} location for the {@link OpenGammaDemoServer#BROKER broker}. */
    public String getBrokerLocation() {
        String address = getBrokerAddress();
        Integer port = getBrokerPort();
        HostAndPort broker = HostAndPort.fromParts(address, port);
        return broker.toString();
    }

    /** Return the {@code host:port} location for the {@link OpenGammaDemoServer#DATABASE database}. */
    public String getDatabaseLocation() {
        String address = entity.getConfig(OpenGammaDemoServer.DATABASE).getAttribute(PostgreSqlNode.ADDRESS);
        Integer port = entity.getConfig(OpenGammaDemoServer.DATABASE).getAttribute(PostgreSqlNode.POSTGRESQL_PORT);
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
            String destination = String.format("%s/%s/%s", getRunDir(), BROOKLYN_SUBDIR, name);
            getMachine().copyTo(new ByteArrayInputStream(contents.getBytes()), destination);
        }

        copyResource("classpath:/io/cloudsoft/opengamma/config/jetty-spring.xml",
                getRunDir() + "/" + COMMON_SUBDIR + "/jetty-spring.xml");
        copyResource(MutableMap.of("permissions", "755"),
                "classpath:/io/cloudsoft/opengamma/scripts/og-brooklyn.sh",
                getRunDir() + "/" + SCRIPT_SUBDIR + "/og-brooklyn.sh");

        String contents = processTemplate("classpath:/io/cloudsoft/opengamma/scripts/init-brooklyn-db.sh");
        String destination = String.format("%s/%s/%s", getRunDir(), SCRIPT_SUBDIR, "init-brooklyn-db.sh");
        getMachine().copyTo(MutableMap.of("permissions", "755"), new ByteArrayInputStream(contents.getBytes()), destination);

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

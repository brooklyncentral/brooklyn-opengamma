package io.cloudsoft.opengamma.server;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.drivers.downloads.DownloadResolver;
import brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.event.basic.DependentConfiguration;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.internal.ssh.SshTool;
import brooklyn.util.net.Urls;
import brooklyn.util.ssh.BashCommands;
import brooklyn.util.stream.KnownSizeInputStream;
import brooklyn.util.task.Tasks;
import brooklyn.util.text.Strings;

import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

public class OpenGammaServerSshDriver extends JavaSoftwareProcessSshDriver implements OpenGammaServerDriver {

    // sensor put on DB entity, when running distributed, not OG server 
    public static final AttributeSensor<Boolean> DB_INITIALISED =
            new BasicAttributeSensor<Boolean>(Boolean.class, "opengamma.database.initialised");
    
    public OpenGammaServerSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    // FIXME should not have to jump through these hoops
    // unintuitive that .get() doesn't work (because the task isn't submitted)
    @SuppressWarnings("unchecked")
    private <T> T attributeWhenReady(ConfigKey<? extends Entity> target, AttributeSensor<T> sensor) {
        try {
            return (T) Tasks.resolveValue(
                    DependentConfiguration.attributeWhenReady(entity.getConfig(target), sensor),
                    sensor.getType(),
                    ((EntityInternal)entity).getExecutionContext(),
                    "Getting "+sensor+" from "+target);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    /** Blocking call to return the {@link ActiveMqBroker#ADDRESS host} for the {@link OpenGammaServer#BROKER broker}. */
    public String getBrokerAddress() {
        return attributeWhenReady(OpenGammaServer.BROKER, ActiveMQBroker.ADDRESS);
    }

    /** Return the {@link ActiveMqBroker#OPEN_WIRE_PORT port} for the {@link OpenGammaServer#BROKER broker}. */
    public Integer getBrokerPort() {
        return attributeWhenReady(OpenGammaServer.BROKER, ActiveMQBroker.OPEN_WIRE_PORT);
    }

    /** Return the {@code host:port} location for the {@link OpenGammaServer#BROKER broker}. */
    public String getBrokerLocation() {
        String address = getBrokerAddress();
        Integer port = getBrokerPort();
        HostAndPort broker = HostAndPort.fromParts(address, port);
        return broker.toString();
    }

    /** Return the {@code host:port} location for the {@link OpenGammaServer#DATABASE database}. */
    public String getDatabaseLocation() {
        String address = attributeWhenReady(OpenGammaServer.DATABASE, PostgreSqlNode.ADDRESS);
        Integer port = attributeWhenReady(OpenGammaServer.DATABASE, PostgreSqlNode.POSTGRESQL_PORT);
        HostAndPort database = HostAndPort.fromParts(address, port);
        return database.toString();
    }

    public String getDownloadArchiveSubpath() {
        return Strings.replaceAllNonRegex(getEntity().getConfig(OpenGammaServer.DOWNLOAD_ARCHIVE_SUBPATH),
            "${version}", getVersion());
    }
    
    protected String OPENGAMMA_SUBDIR() {
        return "opengamma";
//        return getDownloadArchiveSubpath(); 
    }
    protected String LIB_OVERRIDE_SUBDIR() { return OPENGAMMA_SUBDIR() + "/lib/override"; }
    protected String TEMP_SUBDIR() { return OPENGAMMA_SUBDIR() + "/temp"; }
    protected String SCRIPT_SUBDIR() { return OPENGAMMA_SUBDIR() + "/scripts"; }
    protected String CONFIG_SUBDIR() { return OPENGAMMA_SUBDIR() + "/config"; }
    protected String COMMON_SUBDIR() { return CONFIG_SUBDIR() + "/common"; }
    protected String BROOKLYN_CONFIG_SUBDIR() { return CONFIG_SUBDIR() + "/brooklyn"; }
//    protected String TOOLCONTEXT_SUBDIR() { return CONFIG_SUBDIR() + "/toolcontext"; }

    @Override
    public void install() {
        DownloadResolver resolver = Entities.newDownloader(this);
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();

        List<String> commands = ImmutableList.<String>builder()
                .addAll(BashCommands.commandsToDownloadUrlsAs(urls, saveAs))
                .add(BashCommands.INSTALL_TAR)
                .add(BashCommands.INSTALL_UNZIP)
                .add("tar xvfz "+saveAs)
                .build();

        newScript(INSTALLING)
                .updateTaskAndFailOnNonZeroResultCode()
                .body.append(commands).execute();
    }
    /*

in theory, from discussions with Stephen Colebourne; setting:

[infrastructure].springFile = classpath:fullstack/custom-infrastructure-spring.xml

in my properties that will override this from the ini:

[infrastructure]
factory = com.opengamma.component.factory.SpringInfrastructureComponentFactory
springFile = classpath:fullstack/fullstack-examplessimulated-infrastructure-spring.xml

AND

[activeMQ].factory = com.opengamma.component.factory.SpringInfrastructureComponentFactory
[activeMQ].springFile = brooklynEmptySpringFile.xml

should effectively nullify the [activeMQ] section in the ini file

(however for now i am copying their files and making a few clearly marked changes)

     */

    @Override
    public void customize() {
        DownloadResolver resolver = Entities.newDownloader(this);
        // Copy the install files to the run-dir
        newScript(CUSTOMIZING)
            .updateTaskAndFailOnNonZeroResultCode()
            .body.append("cp -r "+getInstallDir()+"/"+resolver.getUnpackedDirectoryName(getDownloadArchiveSubpath())+" "+OPENGAMMA_SUBDIR())
            // create the dirs where we will put config files
            .body.append("mkdir -p " + TEMP_SUBDIR())
            .body.append("mkdir -p " + COMMON_SUBDIR())
            .body.append("mkdir -p " + BROOKLYN_CONFIG_SUBDIR())
            .body.append("mkdir -p " + LIB_OVERRIDE_SUBDIR())
            // FIXME install the postgres jar (should be done as install step ideally)
            .body.append(BashCommands.commandToDownloadUrlAs(
                "http://jdbc.postgresql.org/download/postgresql-9.2-1003.jdbc4.jar",
                LIB_OVERRIDE_SUBDIR()+"/postgresql-9.2-1003.jdbc4.jar"))
            .execute();

        String[] fileNamesToCopyLiterally = {
                "brooklyn-infrastructure-spring.xml",
                "brooklyn.ini"
        };
        String[] fileNamesToCopyTemplated = {
                "brooklyn.properties"
        };
        for (String name : fileNamesToCopyLiterally) {
            String contents = getResourceAsString("classpath:/io/cloudsoft/opengamma/config/brooklyn/" + name);
            getMachine().copyTo(KnownSizeInputStream.of(contents), Urls.mergePaths(getRunDir(), BROOKLYN_CONFIG_SUBDIR(), name));
        }
        for (String name : fileNamesToCopyTemplated) {
            String contents = processTemplate("classpath:/io/cloudsoft/opengamma/config/brooklyn/" + name);
            getMachine().copyTo(KnownSizeInputStream.of(contents), Urls.mergePaths(getRunDir(), BROOKLYN_CONFIG_SUBDIR(), name));
        }

        // needed for 2.1.0 due as workaround for https://github.com/OpenGamma/OG-Platform/pull/6
        // (remove once that is fixed in OG)
        copyResource("classpath:/io/cloudsoft/opengamma/config/patches/patch-postgres-rsk-v-51.jar",
                getRunDir() + "/" + LIB_OVERRIDE_SUBDIR() + "/patch-postgres-rsk-v-51.jar");
        // patch does not work due to local classloading -- we need to rebuild the jar
        newScript("patching postgres rsk")
            .updateTaskAndFailOnNonZeroResultCode()
            .body.append("cd "+getRunDir(),
                "cd "+LIB_OVERRIDE_SUBDIR(),
                "mkdir tmp", 
                "cd tmp",
                "unzip ../../og-masterdb-2.1.0.jar",
                "mv META-INF META-INF_masterdb",
                "unzip -fo ../patch-postgres-rsk-v-51.jar",
                "rm -rf META-INF",
                "mv META-INF_masterdb META-INF",
                "jar cvf ../og-masterdb-2.1.0.jar .",
                "cd ..",
                "rm -rf tmp",
                "rm -f patch-postgres-rsk-v-51.jar",
                "mv og-masterdb-2.1.0.jar ..")
            .failOnNonZeroResultCode()
            .execute();
        
        copyResource("classpath:/io/cloudsoft/opengamma/config/jetty-spring.xml",
                getRunDir() + "/" + COMMON_SUBDIR() + "/jetty-spring.xml");
        copyResource(MutableMap.of(SshTool.PROP_PERMISSIONS.getName(), "0755"), 
                "classpath:/io/cloudsoft/opengamma/scripts/og-brooklyn.sh",
                getRunDir() + "/" + SCRIPT_SUBDIR() + "/og-brooklyn.sh");

        String toolcontextContents = processTemplate("classpath:/io/cloudsoft/opengamma/config/brooklyn/toolcontext-example.properties");
        String toolcontextDestination = Urls.mergePaths(getRunDir(), BROOKLYN_CONFIG_SUBDIR(), "toolcontext-example.properties");
        getMachine().copyTo(new StringReader(toolcontextContents), toolcontextDestination);

        // FIXME no need to be a template
        String scriptContents = 
            //processTemplate(
            getResourceAsString(
            "classpath:/io/cloudsoft/opengamma/scripts/init-brooklyn-db.sh");
        String scriptDestination = Urls.mergePaths(getRunDir(), SCRIPT_SUBDIR(), "init-brooklyn-db.sh");
        getMachine().copyTo(MutableMap.of(SshTool.PROP_PERMISSIONS.getName(), "0755"), new StringReader(scriptContents), scriptDestination);

        // wait for DB up, of course
        attributeWhenReady(OpenGammaServer.DATABASE, PostgreSqlNode.SERVICE_UP);

        // Use the database server's location  and id as a mutex to prevents multiple execution of the initialisation code
        Entity database = entity.getConfig(OpenGammaServer.DATABASE);
        if (database!=null) {
            SshMachineLocation machine = (SshMachineLocation) Iterables.find(database.getLocations(), Predicates.instanceOf(SshMachineLocation.class));
            try {
                machine.acquireMutex(database.getId(), "initialising database "+database);
                if (database.getAttribute(DB_INITIALISED) != Boolean.TRUE) {
                    log.info("{}: Initialising database on {}", entity, database);
                    newScript(CUSTOMIZING)
                            .updateTaskAndFailOnNonZeroResultCode()
                            .body.append("cd opengamma", "scripts/init-brooklyn-db.sh")
                            .execute();
                    ((EntityLocal)database).setAttribute(DB_INITIALISED, true);
                } else {
                    log.info("{}: Database on {} already initialised", entity, database);
                }
            } catch (InterruptedException e) {
                throw Throwables.propagate(e);
            } finally {
                machine.releaseMutex(database.getId());
            }
        }
    }

    @Override
    public void launch() {
        // and wait for broker up also
        attributeWhenReady(OpenGammaServer.BROKER, ActiveMQBroker.SERVICE_UP);
        
        newScript(LAUNCHING)
                .updateTaskAndFailOnNonZeroResultCode()
                .body.append("cd opengamma", "nohup scripts/og-brooklyn.sh start",
                        /* sleep needed sometimes else the java process - the last thing done by the script -
                         * does not seem to start; it is being invoked as `exec (setsid) java ... < /dev/null &` */
                        "sleep 1")
                .execute();
    }


    @Override
    public boolean isRunning() {
        return newScript(MutableMap.of("usePidFile", "opengamma/data/og-brooklyn.pid"), CHECK_RUNNING)
                // XXX ps --pid is not portable so can't use their scripts
                // .body.append("cd opengamma", "scripts/og-examples.sh status").
                .execute() == 0;
    }

    @Override
    public void stop() {
        newScript(MutableMap.of("usePidFile", "opengamma/og-brooklyn.pid"), STOPPING)
                // XXX ps --pid is not portable so we can't rely on the OG default scripts
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

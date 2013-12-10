package io.cloudsoft.opengamma.server;

import java.io.StringReader;
import java.util.Arrays;
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
import brooklyn.util.task.ssh.SshTasks;
import brooklyn.util.task.system.ProcessTaskWrapper;
import brooklyn.util.text.Strings;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;

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
    
    boolean isInitial = false;

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

    /** Blocking call to return the {@link ActiveMQBroker#ADDRESS host} for the {@link OpenGammaServer#BROKER broker}. */
    public String getBrokerAddress() {
        return attributeWhenReady(OpenGammaServer.BROKER, ActiveMQBroker.ADDRESS);
    }

    /** Return the {@link ActiveMQBroker#OPEN_WIRE_PORT port} for the {@link OpenGammaServer#BROKER broker}. */
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

    protected String getOpenGammaDirectory() {
        return getRunDir() + "/opengamma";
    }

    protected String getLibOverrideDirectory() {
        return getOpenGammaDirectory() + "/lib/override";
    }

    protected String getTempDirectory() {
        return getOpenGammaDirectory() + "/temp";
    }

    protected String getScriptsDirectory() {
        return getOpenGammaDirectory() + "/scripts";
    }

    protected String getConfigDirectory() {
        return getOpenGammaDirectory() + "/config";
    }

    protected String getCommonDirectory() {
        return getConfigDirectory() + "/common";
    }

    protected String getLogsDirectory() {
        return getOpenGammaDirectory() + "/logs";
    }

    protected String getDataDirectory() {
        return getOpenGammaDirectory() + "/data";
    }

    protected String getBrooklynConfigurationDirectory() {
        return getConfigDirectory() + "/brooklyn";
    }

    @Override
    protected String getLogFileLocation() {
        return getLogsDirectory() + "/jetty.log";
    }

    protected String getPidFileRelativeToRunDir() {
        return getDataDirectory() + "/og-brooklyn.pid";
    }

    protected String getPropertiesTemplateUrl() {
        return entity.getConfig(OpenGammaServer.PROPERTIES_TEMPLATE_URL);
    }

    @Override
    public Map<String, String> getShellEnvironment() {
        Map<String,String> env = super.getShellEnvironment();

        // rename JAVA_OPTS to what OG scripts expect
        String jopts = env.remove("JAVA_OPTS");
        if (jopts != null) env.put("EXTRA_JVM_OPTS", jopts);

        return env;
    }

    @Override
    public void install() {
        DownloadResolver resolver = Entities.newDownloader(this);
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();

        List<String> commands = ImmutableList.<String>builder()
                .addAll(BashCommands.commandsToDownloadUrlsAs(urls, saveAs))
                .add(BashCommands.INSTALL_TAR)
                .add(BashCommands.INSTALL_UNZIP)
                // some versions of tar must NOT specify z for a bzip file (e.g. centos on interroute)
                .add(BashCommands.alternatives("tar xvfz "+saveAs, "tar xvf "+saveAs))
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
            .body.append("cp -r "+getInstallDir()+"/"+resolver.getUnpackedDirectoryName(getDownloadArchiveSubpath())+" "+ getOpenGammaDirectory())
            // create the dirs where we will put config files
            .body.append("mkdir -p " + getTempDirectory())
            .body.append("mkdir -p " + getCommonDirectory())
            .body.append("mkdir -p " + getBrooklynConfigurationDirectory())
            .body.append("mkdir -p " + getLibOverrideDirectory())
            // scripts may try to access these before they are created
            .body.append("mkdir -p " + getLogsDirectory())
            .body.append("mkdir -p " + getDataDirectory())
            // install the postgres jar (FIXME should be done as install step ideally)
            .body.append(BashCommands.commandToDownloadUrlAs(
                "http://jdbc.postgresql.org/download/postgresql-9.2-1003.jdbc4.jar",
                getLibOverrideDirectory()+"/postgresql-9.2-1003.jdbc4.jar"))
            .execute();

        String[] fileNamesToCopyLiterally = {
                "classpath:/io/cloudsoft/opengamma/config/brooklyn/brooklyn-infrastructure-spring.xml",
                "classpath:/io/cloudsoft/opengamma/config/brooklyn/brooklyn.ini"
        };
        String[] filesToCopyTemplated = {
                getPropertiesTemplateUrl()
        };
        for (String name : fileNamesToCopyLiterally) {
            String contents = getResourceAsString(name);
            String filename = name.substring(name.lastIndexOf('/') + 1);
            getMachine().copyTo(KnownSizeInputStream.of(contents), Urls.mergePaths(getBrooklynConfigurationDirectory(), filename));
        }
        for (String name : filesToCopyTemplated) {
            String contents = processTemplate(name);
            String filename = name.substring(name.lastIndexOf('/') + 1);
            getMachine().copyTo(KnownSizeInputStream.of(contents), Urls.mergePaths(getBrooklynConfigurationDirectory(), filename));
        }

        // needed for 2.1.0 due as workaround for https://github.com/OpenGamma/OG-Platform/pull/6
        // (remove once that is fixed in OG)
        copyResource("classpath:/io/cloudsoft/opengamma/config/patches/patch-postgres-rsk-v-51.jar",
                getLibOverrideDirectory() + "/patch-postgres-rsk-v-51.jar");
        // patch does not work due to local classloading -- we need to rebuild the jar
        newScript("patching postgres rsk")
            .updateTaskAndFailOnNonZeroResultCode()
            .body.append(
                "cd "+ getLibOverrideDirectory(),
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
                getCommonDirectory() + "/jetty-spring.xml");
        copyResource(MutableMap.of(SshTool.PROP_PERMISSIONS.getName(), "0755"), 
                "classpath:/io/cloudsoft/opengamma/scripts/og-brooklyn.sh",
                getScriptsDirectory() + "/og-brooklyn.sh");

        String toolcontextContents = processTemplate("classpath:/io/cloudsoft/opengamma/config/brooklyn/toolcontext-example.properties");
        String toolcontextDestination = Urls.mergePaths(getBrooklynConfigurationDirectory(), "toolcontext-example.properties");
        getMachine().copyTo(new StringReader(toolcontextContents), toolcontextDestination);

        String scriptContents = getResourceAsString("classpath:/io/cloudsoft/opengamma/scripts/init-brooklyn-db.sh");
        String scriptDestination = Urls.mergePaths(getScriptsDirectory(), "init-brooklyn-db.sh");
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
                    isInitial = true;
                    log.info("{}: Initialising database on {}", entity, database);
                    newScript("initialising OG db")
                            .updateTaskAndFailOnNonZeroResultCode()
                            .body.append("cd "+getRunDir(), "cd opengamma", "unset JAVA_HOME", "scripts/init-brooklyn-db.sh")
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

        Entity database = entity.getConfig(OpenGammaServer.DATABASE);
        
        if (!isInitial) {
            // have seen errors if two cluster nodes start at the same time, subsequent may try to initialize database
            Boolean up = database.getAttribute(OpenGammaServer.DATABASE_INITIALIZED);
            if (up==Boolean.TRUE) {
                log.debug("OG server "+getEntity()+" is not initial, but database is already up, so continuing");
            } else {
                log.info("OG server "+getEntity()+" is not initial, waiting on database to be completely initialised");
                Tasks.setBlockingDetails("Waiting on OpenGamma database to be initialised");
                Entities.submit(getEntity(), DependentConfiguration.attributeWhenReady(database, OpenGammaServer.DATABASE_INITIALIZED)).getUnchecked();
                log.debug("OG server "+getEntity()+" continuing, as database is now completely initialised");
            }
        }
        
        newScript(LAUNCHING)
                .updateTaskAndFailOnNonZeroResultCode()
                .body.append(
                        "cd opengamma",
                        "unset JAVA_HOME",
                        "nohup scripts/og-brooklyn.sh start",
                        /* sleep needed sometimes else the java process - the last thing done by the script -
                         * does not seem to start; it is being invoked as `exec (setsid) java ... < /dev/null &` */
                        "sleep 3")
                .execute();
        
        if (isInitial) {
            log.info("Primary "+getEntity()+" has launched, will set database initialized to allow other servers to boot");
            Time.sleep(Duration.TEN_SECONDS);
            ((EntityInternal)database).setAttribute(OpenGammaServer.DATABASE_INITIALIZED, true);
        }
    }


    @Override
    public boolean isRunning() {
        return newScript(MutableMap.of("usePidFile", getPidFileRelativeToRunDir()), CHECK_RUNNING)
                // XXX ps --pid is not portable so can't use their scripts
                // .body.append("cd opengamma", "scripts/og-examples.sh status").
                .execute() == 0;
    }

    @Override
    public void stop() {
        newScript(MutableMap.of("usePidFile", getPidFileRelativeToRunDir()), STOPPING)
                // XXX ps --pid is not portable so we can't rely on the OG default scripts
                // .body.append("cd opengamma", "scripts/og-examples.sh stop").
                .execute();
    }

    @Override
    public boolean installJava() {
        try {
            getLocation().acquireMutex("install:" + getLocation().getDisplayName(), "installing Java at " + getLocation());
            log.debug("checking for java at " + entity + " @ " + getLocation());
            int result = getLocation().execCommands("check java", Arrays.asList("which java"));
            if (result == 0) {
                log.debug("java detected at " + entity + " @ " + getLocation()+"; checking version");
                ProcessTaskWrapper<Integer> versionTask = Entities.submit(getEntity(),
                        SshTasks.newSshExecTaskFactory(getLocation(), "java -version").requiringExitCodeZero());
                versionTask.get();
                String jversion = versionTask.getStderr()+"\n"+versionTask.getStdout();
                
                int start=0;
                while (start<jversion.length() && !Character.isDigit(jversion.charAt(start)))
                    start++;
                
                if (start>=jversion.length()) {
                    log.warn("Cannot parse java version string, assuming 1.7:\n"+jversion);
                    return true;
                }
                int end = start+1;
                while (end<jversion.length() && (Character.isDigit(jversion.charAt(end)) || "\"\'_-.".indexOf(jversion.charAt(end))>=0))
                    end++;
                String versionSubstring = jversion.substring(start, end);
                log.debug("java version detected as "+versionSubstring+", from:\n"+jversion.trim());
                if (versionSubstring.startsWith("1.7")) {
                    log.debug("java 7 detected; not installing");
                    return true;
                } else {
                    if (versionSubstring.startsWith("1.6") || versionSubstring.startsWith("1.5") || 
                        /* heaven forbid */ versionSubstring.startsWith("1.4")) {
                        log.debug("old version of java detected; installing new version");
                    } else {
                        log.debug("unrecognised/too-new version of java detected; not installing requested version");
                        return true;
                    }
                }
            } else {
                log.debug("java not detected at " + entity + " @ " + getLocation() + ", installing (using BashCommands-based installJava7)");
            }

            result = newScript("INSTALL_OPENJDK").body.append(
                BashCommands.installJava7OrFail()
                // could use Jclouds routines -- but the following complains about yum-install not defined
                // even though it is set as an alias (at the start of the first file)
                //   new ResourceUtils(this).getResourceAsString("classpath:///functions/setupPublicCurl.sh"),
                //   new ResourceUtils(this).getResourceAsString("classpath:///functions/installOpenJDK.sh"),
                //   "installOpenJDK"
                ).execute();
            if (result==0)
                return true;

            // some failures might want a delay and a retry; 
            // NOT confirmed this is needed, so:
            // if we don't see the warning then remove, 
            // or if we do see the warning then just remove this comment!  3 Sep 2013
            log.warn("Unable to install Java at " + getLocation() + " for " + entity +
                " (and Java not detected); invalid result "+result+". " + 
                "Will retry.");
            Time.sleep(Duration.TEN_SECONDS);

            result = newScript("INSTALL_OPENJDK").body.append(
                BashCommands.installJava7OrFail()
                ).execute();
            if (result==0) {
                log.info("Succeeded installing Java at " + getLocation() + " for " + entity + " after retry.");
                return true;
            }
            log.error("Unable to install Java at " + getLocation() + " for " + entity +
                " (and Java not detected), including one retry; invalid result "+result+". " + 
                "Processes may fail to start.");
            return false;

        } catch (Exception e) {
            throw Throwables.propagate(e);
        } finally {
            getLocation().releaseMutex("install:" + getLocation().getDisplayName());
        }

        // //this works on ubuntu (surprising that jdk not in default repos!)
        // "sudo add-apt-repository ppa:dlecan/openjdk",
        // "sudo apt-get update",
        // "sudo apt-get install -y --allow-unauthenticated openjdk-7-jdk"
    }
}

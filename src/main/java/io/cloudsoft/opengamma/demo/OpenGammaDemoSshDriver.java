package io.cloudsoft.opengamma.demo;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.drivers.downloads.DownloadResolver;
import brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.util.MutableMap;
import brooklyn.util.ssh.CommonCommands;

public class OpenGammaDemoSshDriver extends JavaSoftwareProcessSshDriver implements OpenGammaDemoDriver {

    public OpenGammaDemoSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public void install() {
        DownloadResolver resolver = ((EntityInternal)entity).getManagementContext().getEntityDownloadsManager().newDownloader(this);
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();
        
        List<String> commands = new LinkedList<String>();
        commands.addAll(CommonCommands.downloadUrlAs(urls, saveAs));
        commands.add(CommonCommands.INSTALL_TAR);
        commands.add("tar xvfz "+saveAs);

        newScript(INSTALLING).
                updateTaskAndFailOnNonZeroResultCode().
                body.append(commands).execute();
    }

    @Override
    public void customize() {
        DownloadResolver resolver = ((EntityInternal)entity).getManagementContext().getEntityDownloadsManager().newDownloader(this);
        final String COMMON_SUBDIR = "opengamma/config/common";
        // Copy the install files to the run-dir
        newScript(CUSTOMIZING)
            .updateTaskAndFailOnNonZeroResultCode()
                .body.append("cp -r "+getInstallDir()+"/"+resolver.getUnpackedDirectoryName("opengamma")+" "+"opengamma")
                // create the dir where we will put new JMX config
                .body.append("mkdir -p "+COMMON_SUBDIR)
                .body.append("cd opengamma")
                // amend the ports in the properties file
                .body.append(String.format("sed -i.bk" +
                        " \"s/jetty.port = 8080/jetty.port = %s/\""+ 
                        " config/fullstack/fullstack-example.properties",
                        entity.getAttribute(Attributes.HTTP_PORT)))
                .body.append(String.format("sed -i.bk" +
                        " \"s/61616/%s/\""+ 
                        " config/fullstack/fullstack-example.properties",
                        entity.getAttribute(OpenGammaDemoServer.EMBEDDED_MESSAGING_PORT)))
                .body.append("scripts/init-og-examples-db.sh")
                .execute();
        
        copyResource("classpath:/io/cloudsoft/opengamma/config/jetty-spring.xml", 
                getRunDir()+"/"+COMMON_SUBDIR+"/jetty-spring.xml");
    }

    @Override
    public void launch() {
        newScript(LAUNCHING).
            updateTaskAndFailOnNonZeroResultCode().
            body.append("cd opengamma", 
                "nohup scripts/og-examples.sh start").
            execute();
    }


    @Override
    public boolean isRunning() {
        return newScript(MutableMap.of("usePidFile", "opengamma/og-examples.pid"), CHECK_RUNNING).
                // ps --pid is not portable so can't use their scripts
//                body.append("cd opengamma", "scripts/og-examples.sh status").
                execute() == 0;
    }

    @Override
    public void stop() {
        newScript(MutableMap.of("usePidFile", "opengamma/og-examples.pid"), STOPPING).
                // ps --pid is not portable so can't use their scripts
//            body.append("cd opengamma", "scripts/og-examples.sh stop").
            execute();
    }

    @Override
    public void kill() {
        newScript(MutableMap.of("usePidFile", "opengamma/og-examples.pid"), KILLING).
            execute();
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
        if (jopts!=null) env.put("EXTRA_JVM_OPTS", jopts);
        
        return env;
    }
    
}

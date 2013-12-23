package io.cloudsoft.opengamma.locations;

import static brooklyn.util.GroovyJavaMethods.truth;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.jclouds.compute.options.RunScriptOptions.Builder.overrideLoginCredentials;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.jclouds.ContextBuilder;
import org.jclouds.abiquo.AbiquoContext;
import org.jclouds.abiquo.domain.cloud.VirtualMachine;
import org.jclouds.abiquo.domain.enterprise.Enterprise;
import org.jclouds.abiquo.domain.infrastructure.Datacenter;
import org.jclouds.abiquo.domain.network.ExternalIp;
import org.jclouds.abiquo.domain.network.ExternalNetwork;
import org.jclouds.abiquo.domain.network.Ip;
import org.jclouds.abiquo.domain.network.Network;
import org.jclouds.abiquo.domain.task.AsyncTask;
import org.jclouds.abiquo.features.services.MonitoringService;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.domain.Statements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsSshMachineLocation;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.config.ConfigBag;

import com.abiquo.server.core.cloud.VirtualMachineState;
import com.abiquo.server.core.task.enums.TaskState;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;

public class JcloudsInteroutePublicIpLocation extends JcloudsLocation {

    private static final long serialVersionUID = 2759594407476315548L;
    public static final Logger log = LoggerFactory.getLogger(JcloudsInteroutePublicIpLocation.class);

    private static final String EXTERNAL_NETWORK_NAME_PREFIX = "CLPU0_IPAC";

    /**
     * Expect caller to use:
     * {@code LocationSpec.create(JcloudsInteroutePublicIpLocation.class).configure(jcloudsLocation.getAllConfig(true)));}
     */
    public JcloudsInteroutePublicIpLocation() {
    }
    
    /**
     * @deprecated Use LocationSpec, so no-arg constructor; delete this method when confirm that works!
     */
    public JcloudsInteroutePublicIpLocation(JcloudsLocation location) {
        super(MutableMap.copyOf(location.getAllConfig(true)));
        //TODO configure called in AbstractLocation<init>, because legacy-style constructor
        //configure(location.getAllConfig(true));
    }
    
    /**
     * @deprecated Use LocationSpec, so no-arg constructor; delete this method when confirm that works!
     */
    public JcloudsInteroutePublicIpLocation(Map<?,?> map) {
        super(map);
    }    
    
    
    /* 2013-12-23: I have attempted to refactor this, and JcloudsLocation, to make the interroute-specific 
     * calls which are needed using the 0.7.0 code.  BUT I've NOT tested this.
     * I have left some of the old code here for convenience until we do test/confirm. */
    
//    @Override    
//    public JcloudsSshMachineLocation obtain(Map<?,?> flags) throws NoMachinesAvailableException {
//        /*
//        AccessController.Response access = getManagementContext().getAccessController().canProvisionLocation(this);
//        if (!access.isAllowed()) {
//            throw new IllegalStateException("Access controller forbids provisioning in "+this+": "+access.getMsg());
//        }
//        */
//        ConfigBag setup = ConfigBag.newInstanceExtending(getConfigBag(), flags);
//        setCreationString(setup);
//        
//        final ComputeService computeService = JcloudsUtil.findComputeService(setup);
//        String groupId = elvis(setup.get(GROUP_ID), new JcloudsMachineNamer(setup).generateNewGroupId());
//        NodeMetadata node = null;
//        JcloudsSshMachineLocation sshMachineLocation = null;
//        
//        try {
//            LOG.info("Creating VM in "+setup.getDescription()+" for "+this);
//
//            Template template = buildTemplate(computeService, setup);
//            LoginCredentials initialCredentials = initUserTemplateOptions(template, setup);
//            for (JcloudsLocationCustomizer customizer : getCustomizers(setup)) {
//                customizer.customize(this, computeService, template.getOptions());
//            }
//            LOG.debug("jclouds using template {} / options {} to provision machine in {}", new Object[] {
//                    template, template.getOptions(), setup.getDescription()});
//
//            if (!setup.getUnusedConfig().isEmpty())
//                LOG.debug("NOTE: unused flags passed to obtain VM in "+setup.getDescription()+": "+
//                        setup.getUnusedConfig());
//            
//            Set<? extends NodeMetadata> nodes = computeService.createNodesInGroup(groupId, 1, template);
//            node = Iterables.getOnlyElement(nodes, null);
//            LOG.debug("jclouds created {} for {}", node, setup.getDescription());
//            if (node == null)
//                throw new IllegalStateException("No nodes returned by jclouds create-nodes in " + setup.getDescription());
//
//            LoginCredentials customCredentials = setup.get(CUSTOM_CREDENTIALS);
//            if (customCredentials != null) {
//                initialCredentials = customCredentials;
//                //set userName and other data, from these credentials
//                Object oldUsername = setup.put(USER, customCredentials.getUser());
//                LOG.debug("node {} username {} / {} (customCredentials)", new Object[] { node, customCredentials.getUser(), oldUsername });
//                if (truth(customCredentials.getPassword())) setup.put(PASSWORD, customCredentials.getPassword());
//                if (truth(customCredentials.getPrivateKey())) setup.put(PRIVATE_KEY_DATA, customCredentials.getPrivateKey());
//            }
//            if (initialCredentials == null) {
//                initialCredentials = extractVmCredentials(setup, node);
//                LOG.debug("Extracted credentials from node {}/{}", initialCredentials.getUser(), initialCredentials.getPrivateKey());
//            }
//            if (initialCredentials != null) {
//                node = NodeMetadataBuilder.fromNodeMetadata(node).credentials(initialCredentials).build();
//            } else {
//                // only happens if something broke above...
//                initialCredentials = LoginCredentials.fromCredentials(node.getCredentials());
//            }
//            
//            LOG.debug("jclouds will use the following initial credentials {} for node {}", initialCredentials.getUser(), initialCredentials.getPrivateKey());
//            sshMachineLocation = prepareAndRegisterJcloudsSshMachineLocation(computeService, node, initialCredentials, setup);
//            
//            // Apply same securityGroups rules to iptables, if iptables is running on the node
//            String waitForSshable = setup.get(WAIT_FOR_SSHABLE);
//            if (!(waitForSshable!=null && "false".equalsIgnoreCase(waitForSshable))) {
//               String setupScript = setup.get(JcloudsLocationConfig.CUSTOM_MACHINE_SETUP_SCRIPT_URL);
//                if(Strings.isNonBlank(setupScript)) {
//                   String setupVarsString = setup.get(JcloudsLocationConfig.CUSTOM_MACHINE_SETUP_SCRIPT_VARS);
//                   Map<String, String> substitutions = Splitter.on(",").withKeyValueSeparator(":").split(setupVarsString);
//                   String script = TemplateProcessor.processTemplate(setupScript, substitutions);
//                   sshMachineLocation.execCommands("Customizing node " + this, ImmutableList.of(script));
//                }
//                
//                if (setup.get(JcloudsLocationConfig.MAP_DEV_RANDOM_TO_DEV_URANDOM))
//                   sshMachineLocation.execCommands("using urandom instead of random", 
//                        Arrays.asList("sudo mv /dev/random /dev/random-real", "sudo ln -s /dev/urandom /dev/random"));
//
//                
//                if (setup.get(GENERATE_HOSTNAME)) {
//                   sshMachineLocation.execCommands("Generate hostname " + node.getName(), 
//                         Arrays.asList("sudo hostname " + node.getName(),
//                                       "sudo sed -i \"s/HOSTNAME=.*/HOSTNAME=" + node.getName() + "/g\" /etc/sysconfig/network",
//                                       "sudo bash -c \"echo 127.0.0.1   `hostname` >> /etc/hosts\"")
//                   );
//               }
///*
//                if (setup.get(OPEN_IPTABLES)) {
//                   List<String> iptablesRules = createIptablesRulesForNetworkInterface((Iterable<Integer>) setup.get(INBOUND_PORTS));
//                   iptablesRules.add(IptablesCommands.saveIptablesRules());
//                   sshMachineLocation.execCommands("Inserting iptables rules", iptablesRules);
//                   sshMachineLocation.execCommands("List iptables rules", ImmutableList.of(IptablesCommands.listIptablesRule()));
//                }
// */                   
//            } else {
//                // Otherwise would break CloudStack, where port-forwarding means that jclouds opinion 
//                // of using port 22 is wrong.
//            }
//            
//            // Apply any optional app-specific customization.
//            for (JcloudsLocationCustomizer customizer : getCustomizers(setup)) {
//                customizer.customize(this, computeService, sshMachineLocation);
//            }
//            
//            return sshMachineLocation;
//        } catch (Exception e) {
//            if (e instanceof RunNodesException && ((RunNodesException)e).getNodeErrors().size() > 0) {
//                node = Iterables.get(((RunNodesException)e).getNodeErrors().keySet(), 0);
//            }
//            boolean destroyNode = (node != null) && Boolean.TRUE.equals(setup.get(DESTROY_ON_FAILURE));
//            
//            LOG.error("Failed to start VM for {}{}: {}", 
//                    new Object[] {setup.getDescription(), (destroyNode ? " (destroying "+node+")" : ""), e.getMessage()});
//            LOG.debug(Throwables.getStackTraceAsString(e));
//            
//            if (destroyNode) {
//                if (sshMachineLocation != null) {
//                    releaseSafely(sshMachineLocation);
//                } else {
//                    releaseNodeSafely(node);
//                }
//            }
//            
//            throw Exceptions.propagate(e);
//            
//        } finally {
//            //leave it open for reuse
////            computeService.getContext().close();
//        }
//
//    }    

    @Override
    protected JcloudsSshMachineLocation registerJcloudsSshMachineLocation(ComputeService computeService, NodeMetadata node, LoginCredentials initialCredentials, Optional<HostAndPort> sshHostAndPort, ConfigBag setup) throws IOException {
        // FIXME turning off selinux!
        Statement statement = Statements.exec("sed -i.brooklyn.bak 's/^SELINUX=enforcing/SELINUX=permissive/' /etc/sysconfig/selinux");
        ExecResponse response = computeService.runScriptOnNode(node.getId(), statement, 
            overrideLoginCredentials(initialCredentials).runAsRoot(true));
        if (response.getExitStatus() == 0) {
            log.info(">>> For VM {} in {}, set SELINUX to permissive", new Object[] {node.getName(), this});
        } else {
            log.error("For VM {} in {}, failed to set SELINUX to permissive: status={}\n\terr={}\n\tout={}", new Object[] {
                node.getName(), this, response.getExitStatus(), response.getError(), response.getOutput()});
        }

        // attach 2nd NIC
        Optional<AbiquoContext> abiquoContext = abiquoContext();
        if (abiquoContext.isPresent()) {
            ExternalIp externalIp = attachSecondNic(node, abiquoContext.get());
            String vmHostname = externalIp.getIp();
            log.info(">>> For VM {} in {}, using IP {}", new Object[] {node.getName(), this, vmHostname});
        } else {
            log.info(">>> For VM {} in {}, skipping external NIC as not abiquo", new Object[] {node.getName(), this});            
        }
        
        return super.registerJcloudsSshMachineLocation(computeService, node, initialCredentials, sshHostAndPort, setup);
    }

    private ExternalIp attachSecondNic(NodeMetadata node, AbiquoContext context) {
        String machineName = node.getName();
        log.info(">>> Attaching second NIC to " + machineName + " ...");
        try {
            Iterable<VirtualMachine> vms = context.getCloudService().listVirtualMachines();
            Optional<VirtualMachine> optionalVirtualMachine = Iterables.tryFind(vms,
                    VirtualMachinePredicates.name(machineName));
            if (!optionalVirtualMachine.isPresent()) {
            	String msg = "Cannot find a virtual machine with same JcloudsSshMachineLocation name \""+machineName+"\"";
            	log.error(msg + ". The available virtual machines are: " + Iterables.toString(vms));
                throw new IllegalStateException(msg);
            }
            VirtualMachine virtualMachine = optionalVirtualMachine.get();
            Enterprise enterprise = context.getAdministrationService().getCurrentEnterprise();
            Datacenter datacenter = virtualMachine.getVirtualDatacenter().getDatacenter();
            List<ExternalNetwork> externalNetworks = listExternalNetworks(enterprise, datacenter);
            ExternalNetwork externalNetwork = tryFindExternalNetwork(externalNetworks, EXTERNAL_NETWORK_NAME_PREFIX);
            log.info(">>> Found externalNetwork " + externalNetwork + " in datacenter " + datacenter);
            // reconfigure NICs on that virtualMachine
            ExternalIp externalIp = tryFindExternalIp(externalNetwork);
            List<Ip<?, ?>> nics = ImmutableList.<Ip<?, ?>> builder().addAll(virtualMachine.listAttachedNics())
                    .add(externalIp).build();
            reconfigureNICsOnVirtualMachine(context, externalNetwork, nics, virtualMachine);
            return externalIp;
        } finally {
            context.close();
        }
    }

    private Optional<AbiquoContext> abiquoContext() {
        log.info(">>> Provider " + this.getProvider());
        if ("abiquo".equals(this.getProvider())) {
            return Optional.of(ContextBuilder.newBuilder(this.getProvider()).endpoint(this.getEndpoint())
                    .credentials(this.getIdentity(), this.getCredential()).buildView(AbiquoContext.class));
        }
        return Optional.absent();
    }

    protected String getPublicHostname(NodeMetadata node, Optional<HostAndPort> sshHostAndPort, ConfigBag setup) {
        // prefer the public address to the hostname because hostname is sometimes wrong/abbreviated
        // (see that javadoc; also e.g. on rackspace, the hostname lacks the domain)
        // TODO would it be better to prefer hostname, but first check that it is resolvable?
        if (truth(node.getPublicAddresses())) {
            return node.getPublicAddresses().iterator().next();
        } else if (truth(node.getHostname())) {
            return node.getHostname();
        } else if (truth(node.getPrivateAddresses())) {
            return node.getPrivateAddresses().iterator().next();
        } else {
            return super.getPublicHostname(node, sshHostAndPort, setup);
        }
    }

    private void reconfigureNICsOnVirtualMachine(AbiquoContext context, ExternalNetwork externalNetwork,
            List<Ip<?, ?>> nics, VirtualMachine virtualMachine) {
        log.info(">>> Attaching NICs " + Iterables.toString(nics) + " to virtualMachine("
                + virtualMachine.getNameLabel() + ")");
        MonitoringService monitoringService = context.getMonitoringService();
        ensureVirtualMachineState(virtualMachine, VirtualMachineState.OFF, monitoringService);
        AsyncTask asyncTask = virtualMachine.setNics(externalNetwork, nics);
        monitoringService.getAsyncTaskMonitor().awaitCompletion(asyncTask);
        Preconditions
                .checkState(asyncTask.getState() == TaskState.FINISHED_SUCCESSFULLY, "Error in task: " + asyncTask);
        monitoringService.getVirtualMachineMonitor().awaitState(VirtualMachineState.OFF, virtualMachine);
        ensureVirtualMachineState(virtualMachine, VirtualMachineState.ON, monitoringService);
        log.info(">>> Attached NICs : " + Iterables.toString(virtualMachine.listAttachedNics()) + " to virtualMachine("
                + virtualMachine.getNameLabel() + ")");
    }

    private void ensureVirtualMachineState(VirtualMachine virtualMachine, VirtualMachineState virtualMachineState,
            MonitoringService monitoringService) {
        virtualMachine.changeState(virtualMachineState);
        monitoringService.getVirtualMachineMonitor().awaitState(virtualMachineState, virtualMachine);
        log.info("virtualMachine(" + virtualMachine.getNameLabel() + ") is " + virtualMachine.getState());
    }

    private ExternalIp tryFindExternalIp(ExternalNetwork externalNetwork) {
        List<ExternalIp> listUnusedIps = externalNetwork.listUnusedIps();
        if (listUnusedIps != null && listUnusedIps.isEmpty()) {
            throw new IllegalStateException("Cannot find an available externalIp in external network "
                    + externalNetwork);
        }
        Optional<ExternalIp> optionalExternalIp = Optional.of(listUnusedIps.get(0));
        if (optionalExternalIp.isPresent()) {
            return optionalExternalIp.get();
        } else {
            throw new IllegalStateException("Cannot find an available externalIp in external network "
                    + externalNetwork);
        }
    }

    private ExternalNetwork tryFindExternalNetwork(List<ExternalNetwork> externalNetworks,
            final String externalNetworkName) {
        Optional<ExternalNetwork> optionalExternalNetwork = Iterables.tryFind(externalNetworks,
                new ExternalNetworkPredicate(externalNetworkName));
        if (optionalExternalNetwork.isPresent()) {
            return optionalExternalNetwork.get();
        } else {
            throw new IllegalStateException("Cannot find an externalNetwork in any datacenters with name "
                    + externalNetworkName + ". The available externalNetworks are: "
                    + Iterables.toString(externalNetworks));
        }
    }

    private List<ExternalNetwork> listExternalNetworks(Enterprise enterprise, Datacenter datacenter) {
        return enterprise.listExternalNetworks(datacenter);
    }

    private final class ExternalNetworkPredicate implements Predicate<Network<ExternalIp>> {
        private final String externalNetworkPrefix;

        private ExternalNetworkPredicate(String externalNetworkPrefix) {
            this.externalNetworkPrefix = externalNetworkPrefix;
        }

        public boolean apply(@Nullable Network<ExternalIp> input) {
            return input != null && input.getName().startsWith(externalNetworkPrefix);
        }
    }

    private final static class VirtualMachinePredicates {

        public static Predicate<VirtualMachine> name(final String machineName) {
            checkNotNull(machineName, "machineName must be defined");

            return new Predicate<VirtualMachine>() {
                @Override
                public boolean apply(final VirtualMachine virtualMachine) {
                    return Arrays.asList(machineName).contains(virtualMachine.getNameLabel());
                }
            };
        }
    }
}

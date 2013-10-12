package io.cloudsoft.opengamma;

import static com.google.common.base.Preconditions.checkNotNull;

import io.cloudsoft.opengamma.locations.JcloudsInteroutePublicIpLocation;

import java.util.Arrays;
import java.util.List;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Effector;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.SoftwareProcessDriverLifecycleEffectorTasks;
import brooklyn.entity.proxy.nginx.NginxControllerImpl;
import brooklyn.location.Location;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsSshMachineLocation;
import brooklyn.util.ssh.BashCommands;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;

import com.abiquo.server.core.cloud.VirtualMachineState;
import com.abiquo.server.core.task.enums.TaskState;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

public class CustomNginxControllerImpl extends NginxControllerImpl {

private static final String EXTERNAL_NETWORK_NAME_PREFIX = "CLPU0_IPAC";
public static final Logger log = LoggerFactory.getLogger(CustomNginxControllerImpl.class);

   protected String getCodeForServerConfig() {
       // See http://zeroturnaround.com/labs/wordpress-protips-go-with-a-clustered-approach/#!/
       // 
       // But fails if use the brooklyn default:
       //     proxy_set_header Host $http_host;
       // instead of:
       //     proxy_set_header Host $host;
       
       return ""+
           "    server_tokens off;\n"+
           "    proxy_set_header Host $host;\n"+
           "    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"+
           "    proxy_set_header X-Real-IP $remote_addr;\n";
   } 
   
   private static final SoftwareProcessDriverLifecycleEffectorTasks LIFECYCLE_TASKS =
           new SoftwareProcessDriverLifecycleEffectorTasks() {
       protected void startInLocation(Location location) {
           if (location instanceof JcloudsLocation && !(location instanceof JcloudsInteroutePublicIpLocation)) {
               location = new JcloudsInteroutePublicIpLocation(location);
           }
           super.startInLocation(location);
       }
   };
   
   public static final Effector<Void> START = LIFECYCLE_TASKS.newStartEffector();
   public static final Effector<Void> RESTART = LIFECYCLE_TASKS.newRestartEffector();
   public static final Effector<Void> STOP = LIFECYCLE_TASKS.newStopEffector();
   
   /*
   @Override
   protected void preStart() {
       super.preStart();
       log.info(">>> preStart: locations="+getLocations());
       customizeEntity();
   }
   
   private void customizeEntity() {
      for (Location loc : getLocations()) {
         JcloudsLocation jcloudsLocation = null;
         if (loc instanceof JcloudsSshMachineLocation) {
            jcloudsLocation = ((JcloudsSshMachineLocation)loc).getParent();
         } else if (loc instanceof JcloudsLocation) {
             jcloudsLocation = ((JcloudsLocation) loc);
         }
         
         if (jcloudsLocation != null) {
            log.info(">>> Provider " + jcloudsLocation.getProvider());
            if ("abiquo".equals(jcloudsLocation.getProvider())) {
               AbiquoContext context = ContextBuilder.newBuilder(jcloudsLocation.getProvider())
                     .endpoint(jcloudsLocation.getEndpoint())
                     .credentials(jcloudsLocation.getIdentity(), jcloudsLocation.getCredential())
                     .buildView(AbiquoContext.class);
               customizeEntity(this, context);
            }
         } else {
             log.info(">>> Location " + loc + " not a jclouds-location; ignoring for nic setup");
         }
      }
   }
   
    private void customizeEntity(Entity entity, AbiquoContext context) {
        log.info(">>> Customizing entity: " + entity.getEntityType().getSimpleName());
        try {
            JcloudsSshMachineLocation machine = (JcloudsSshMachineLocation) Iterables.get(entity.getLocations(),0);
            String machineName = machine.getNode().getName();

            Iterable<VirtualMachine> vms = context.getCloudService().listVirtualMachines();
            Optional<VirtualMachine> optionalVirtualMachine = Iterables.tryFind(vms, VirtualMachinePredicates.name(machineName));
            if(!optionalVirtualMachine.isPresent()) {
                String msg = "Cannot find a virtual machine with same JcloudsSshMachineLocation name.\nThe available virtual machines are: " + Iterables.toString(vms);
                log.error(msg);
                return;
            }
            VirtualMachine virtualMachine = optionalVirtualMachine.get();
            
            Enterprise enterprise = context.getAdministrationService().getCurrentEnterprise();
            Datacenter datacenter = virtualMachine.getVirtualDatacenter().getDatacenter();
            List<ExternalNetwork> externalNetworks = listExternalNetworks(enterprise, datacenter);
            ExternalNetwork externalNetwork = tryFindExternalNetwork(externalNetworks, EXTERNAL_NETWORK_NAME_PREFIX);
            log.info(">>> Found externalNetwork " + externalNetwork + " in datacenter " + datacenter);

            
            // reconfigure NICs on that virtualMachine
            ExternalIp externalIp = tryFindExternalIp(externalNetwork);
            List<Ip<?, ?>> nics = ImmutableList.<Ip<?, ?>> builder()
                    .addAll(virtualMachine.listAttachedNics())
                    .add(externalIp)
                    .build();
            reconfigureNICsOnVirtualMachine(context, externalNetwork, nics, virtualMachine);
            Time.sleep(Duration.TEN_SECONDS);
            List<String> commands = ImmutableList.<String>builder()
                    .add(BashCommands.sudo("/sbin/ip ro del default"))
                    .add(BashCommands.sudo("/sbin/ip ro add default via " + externalNetwork.getGateway()))
                    .build();
            machine.execCommands("reconfigure default gateway(" + externalNetwork.getGateway() + ")" , commands);
        } finally {
            context.close();
        }
    }

   private void reconfigureNICsOnVirtualMachine(AbiquoContext context, ExternalNetwork externalNetwork, List<Ip<?, ?>> nics, VirtualMachine virtualMachine) {
      log.info(">>> Attaching NICs " + Iterables.toString(nics) + " to virtualMachine(" + virtualMachine.getNameLabel() + ")");
      MonitoringService monitoringService = context.getMonitoringService();
      ensureVirtualMachineState(virtualMachine, VirtualMachineState.OFF, monitoringService);
      AsyncTask asyncTask = virtualMachine.setNics(externalNetwork, nics);
      monitoringService.getAsyncTaskMonitor().awaitCompletion(asyncTask);
      Preconditions.checkState(asyncTask.getState() == TaskState.FINISHED_SUCCESSFULLY, "Error in task: "+asyncTask);
      monitoringService.getVirtualMachineMonitor().awaitState(VirtualMachineState.OFF, virtualMachine);    
      ensureVirtualMachineState(virtualMachine, VirtualMachineState.ON, monitoringService);
      log.info(">>> Attached NICs : " + Iterables.toString(virtualMachine.listAttachedNics()) + " to virtualMachine(" + virtualMachine.getNameLabel() + ")");
   }

   private void ensureVirtualMachineState(VirtualMachine virtualMachine, VirtualMachineState virtualMachineState, MonitoringService monitoringService) {
       virtualMachine.changeState(virtualMachineState);
       monitoringService.getVirtualMachineMonitor().awaitState(virtualMachineState, virtualMachine);    
       log.info("virtualMachine(" + virtualMachine.getNameLabel() + ") is " + virtualMachine.getState());
   }

   private ExternalIp tryFindExternalIp(ExternalNetwork externalNetwork) {
      List<ExternalIp> listUnusedIps = externalNetwork.listUnusedIps();
      if(listUnusedIps != null && listUnusedIps.isEmpty()) {
          throw new IllegalStateException("Cannot find an available externalIp in external network " +
                  externalNetwork);
      }
      Optional<ExternalIp> optionalExternalIp = Optional.of(listUnusedIps.get(0));
      if(optionalExternalIp.isPresent()) {
         return optionalExternalIp.get();
      } else {
         throw new IllegalStateException("Cannot find an available externalIp in external network " +
                 externalNetwork);
      }
   }

   private ExternalNetwork tryFindExternalNetwork(List<ExternalNetwork> externalNetworks, final String externalNetworkName) {
       Optional<ExternalNetwork> optionalExternalNetwork = Iterables.tryFind(externalNetworks, new ExternalNetworkPredicate(externalNetworkName));
       if(optionalExternalNetwork.isPresent()) {
          return optionalExternalNetwork.get();
       } else {
          throw new IllegalStateException("Cannot find an externalNetwork in any datacenters with name " + externalNetworkName + ". The available externalNetworks are: " + 
                  Iterables.toString(externalNetworks));
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
   */
}

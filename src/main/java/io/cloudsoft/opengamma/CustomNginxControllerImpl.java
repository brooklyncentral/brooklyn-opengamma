package io.cloudsoft.opengamma;

import static com.google.common.base.Preconditions.checkNotNull;

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
import org.jclouds.abiquo.features.services.MonitoringService;
import org.jclouds.abiquo.predicates.infrastructure.DatacenterPredicates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.proxy.nginx.NginxControllerImpl;
import brooklyn.location.Location;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsSshMachineLocation;

import com.abiquo.server.core.cloud.VirtualMachineState;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class CustomNginxControllerImpl extends NginxControllerImpl {

private static final String EXTERNAL_NETWORK_NAME = "CLPU0_IPAC";
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
      try {
          // needed because otherwise it applies to all entities !?!?
          if(entity.getEntityType().getSimpleName().equalsIgnoreCase("NginxController")) {
              log.info(">>> Customizing entity: " + entity.getEntityType().getSimpleName());
              Enterprise enterprise = context.getAdministrationService().getCurrentEnterprise();
              Datacenter datacenter = enterprise.findAllowedDatacenter(DatacenterPredicates.name("Paris"));      
              List<ExternalNetwork> externalNetworks = listExternalNetworks(enterprise, datacenter);
    
                 ExternalNetwork externalNetwork = tryFindExternalNetwork(externalNetworks, EXTERNAL_NETWORK_NAME);
                 log.info(">>> Found externalNetwork " + externalNetwork + " in datacenter " + datacenter);
                 ExternalIp externalIp = tryFindExternalIp(externalNetwork);
                 Iterable<VirtualMachine> vms = context.getCloudService().listVirtualMachines();
                 JcloudsSshMachineLocation machine = (JcloudsSshMachineLocation) Iterables.getFirst(entity.getLocations(), null);
                 checkNotNull(machine);
                 for (VirtualMachine virtualMachine : vms) {
                    if (virtualMachine.getNameLabel().equals(machine.getNode().getName())) {
                       List<Ip<?, ?>> nics = appendExternalIpToNICs(externalIp, virtualMachine);
                       log.info(">>> Setting NIC " + Iterables.toString(nics) + " on virtualMachine(" + virtualMachine.getNameLabel());
                       reconfigureNICsOnVirtualMachine(context, externalNetwork, nics, virtualMachine);
                    }
                 }
          }
      } finally {
          context.close();
      }
   }

   private void reconfigureNICsOnVirtualMachine(AbiquoContext context, ExternalNetwork externalNetwork, List<Ip<?, ?>> nics, VirtualMachine virtualMachine) {
      MonitoringService monitoringService = context.getMonitoringService();
      virtualMachine.changeState(VirtualMachineState.OFF);
      monitoringService.getVirtualMachineMonitor().awaitState(VirtualMachineState.OFF, virtualMachine);
      log.info("virtualMachine(" + virtualMachine.getNameLabel() + ") is " + virtualMachine.getState());
      virtualMachine.setNics(nics);
      monitoringService.getVirtualMachineMonitor().awaitState(VirtualMachineState.OFF, virtualMachine);
      log.info("virtualMachine(" + virtualMachine.getNameLabel() + ") is " + virtualMachine.getState() + "; nics " + nics);

      virtualMachine.changeState(VirtualMachineState.ON);
      monitoringService.getVirtualMachineMonitor().awaitState(VirtualMachineState.ON, virtualMachine);
      log.info("virtualMachine(" + virtualMachine.getNameLabel() + ") is " + virtualMachine.getState() + "; nics " + nics);
   }

   private List<Ip<?, ?>> appendExternalIpToNICs(ExternalIp externalIp, VirtualMachine virtualMachine) {
      List<Ip<?, ?>> initialIps = virtualMachine.listAttachedNics();
      List<Ip<?, ?>> ips = Lists.newArrayList();
      ips.add(externalIp);
      ips.addAll(initialIps);
      return ips;
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
   
}

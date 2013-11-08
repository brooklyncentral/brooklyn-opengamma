echo ==============================================================================
echo  CUSTOMIZING HOST
echo ==============================================================================

unset OS
OS=`head -1 /etc/issue | awk '{ print $1 }'`
unset OS_MAJOR
OS_MAJOR=`head -1 /etc/issue | awk '{ print $4 }' | cut -d'.' -f1`
if [ $OS = "CentOS" -a $OS_MAJOR -eq 6 ]; then
    echo Appending options single-request-reopen to /etc/resolv.conf
    sudo -s -- '/bin/echo options single-request-reopen > /etc/resolv.conf'
fi

echo Inserting nameserver 8.8.8.8 to /etc/resolv.conf
sudo -s -- '/bin/echo nameserver 8.8.8.8 >> /etc/resolv.conf'

echo Changing default gateway to ${defaultGateway}
#sudo -s -- '/sbin/route del default gw ${originalGateway}'
#sudo -s -- '/sbin/route add default gw ${defaultGateway} eth0'
#sudo -s -- '/sbin/ip route add ${destination} via ${originalGateway} dev eth0'
#sudo -s -- '/bin/touch /etc/sysconfig/network-scripts/route-eth0'

# set default gateway (our private proxy machine) if we can't already contact the internet
# (if a public ip was set up, with public network gateway, then we can already contact the internet,
# and running this command would break INGRESS to the public ip)
( ping -c 1 8.8.8.8 ) || ( sudo -s -- '/bin/echo GATEWAY=${defaultGateway} >> /etc/sysconfig/network' && sudo -s -- '/bin/echo ${destination} via ${originalGateway} dev eth0 >> /etc/sysconfig/network-scripts/route-eth0' )

sudo -s -- '/sbin/service network restart'
/sbin/route -n

echo Shutdown iptables
sudo -s -- '/sbin/service iptables stop'
sudo -s -- '/sbin/chkconfig iptables off'
/sbin/chkconfig

echo ==============================================================================
echo  CUSTOMIZATION COMPLETED
echo ==============================================================================

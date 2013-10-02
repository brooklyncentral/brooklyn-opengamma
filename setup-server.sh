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
sudo -s -- '/bin/echo GATEWAY=${defaultGateway} >> /etc/sysconfig/network-scripts/ifcfg-eth0'
sudo -s -- '/sbin/service network restart'

echo Shutdown iptables
sudo -s -- '/sbin/service iptables stop'
sudo -s -- '/sbin/chkconfig iptables off'

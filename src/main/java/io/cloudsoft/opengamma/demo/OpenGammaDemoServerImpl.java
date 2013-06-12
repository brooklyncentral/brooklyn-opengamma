package io.cloudsoft.opengamma.demo;

import brooklyn.entity.basic.SoftwareProcessImpl;
import brooklyn.event.feed.http.HttpFeed;
import brooklyn.event.feed.http.HttpPollConfig;
import brooklyn.event.feed.http.HttpValueFunctions;
import brooklyn.location.access.BrooklynAccessUtils;

import com.google.common.base.Functions;
import com.google.common.net.HostAndPort;

public class OpenGammaDemoServerImpl extends SoftwareProcessImpl implements OpenGammaDemoServer {

    private HttpFeed httpFeed;

    @Override
    public Class getDriverInterface() {
        return OpenGammaDemoDriver.class;
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();

        HostAndPort hp = BrooklynAccessUtils.getBrooklynAccessibleAddress(this, getAttribute(HTTP_PORT));
        String rootUrl = "http://"+hp.getHostText()+":"+hp.getPort()+"/";
        setAttribute(ROOT_URL, rootUrl);
        
        httpFeed = HttpFeed.builder()
                .entity(this)
                .period(1000)
                .baseUri(rootUrl)
                .poll(new HttpPollConfig<Boolean>(SERVICE_UP)
                        .onSuccess(HttpValueFunctions.responseCodeEquals(200))
                        .onError(Functions.constant(false)))
                .build();
        
        // TODO JMX mgmt
    }
    
    @Override
    protected void disconnectSensors() {
        super.disconnectSensors();
        if (httpFeed != null) httpFeed.stop();
    }

}

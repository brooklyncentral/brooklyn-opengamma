## Quick Start

This project uses [OpenGamma](http://www.opengamma.com/) to illustrate
deploying and managing a set of dependent software services with
Brooklyn to provide a scalable application platform.

### Getting and Building

You must first have the `brooklyn-opengamma` repository cloned or
downloaded. To compile the project you'll need Maven 3 and Java 1.6 or
higher installed, then simply run the following commands:

    % git clone https://github.com/cloudsoft/brooklyn-opengamma.git
    % cd brooklyn-opengamma
    % mvn clean install
    % mvn assembly:single

This will build a Jar file suitable for use with the Brooklyn CLI,
and the last command will create a standalone distribution with the
Brooklyn manager embedded.

### Credentials

To run, you'll need to specify credentials for your preferred cloud.  This
can be done in `~/.brooklyn/brooklyn.properties` using this syntax, shown
here for AWS EC2:

    brooklyn.jclouds.aws-ec2.identity = AAAAAAAAAAAA
    brooklyn.jclouds.aws-ec2.credential = xxxxxxxxxxxxxxxxxxxxxxxx

Alternatively these can be set as shell environment parameters or JVM
system properties.

Many other public clouds are supported, as well as groups of existing
machines, custom endpoints for private and hybrid clouds, and specifying
custom keys and passphrases. For more information see the section on
_Locations_ in the [Brooklyn User Guide](http://brooklyn.io/use/guide/defining-applications/common-usage.html)

### Running

The `mvn assembly:single` command creates a distribution archive named
`brooklyn-opengamma-0.2.0-SNAPSHOT-bin.tar.gz`. Unpack this and run the
demo as follows:

    % cd target
    % tar zxf brooklyn-opengamma-0.2.0-SNAPSHOT-bin.tar.gz
    % cd brooklyn-opengamma-0.2.0-SNAPSHOT
    % ./start.sh --location LOCATION

Where *LOCATION* is either:

- A cloud or environment you've set up as above, such as `aws-ec2:us-east-1`.
    Not every environment has been tested, of course, but requirements are
    fairly straightforward, mainly java and gcc, so it should be fairly
    portable, and quick to fix any targets which need attention. Let us know if
    we can help with some environment where this doesn't work!
- `localhost`. This application can run on a local Linux or OS X system,
    standing up multiple processes, scaling them out, and is resilient to process
    failures (or deliberate kills). This requires passwordless `ssh localhost`
    access, and (for nginx) development tools including `gcc` and the `pcre`
    library. (On OS X this can usually be achieved by installing *XCode* and
    *XCode Command-Line tools*, both from Apple, and `sudo port install pcre`
    using *MacPorts*, or the equivalent with *Brew*.)

You can also run the demo using the `brooklyn` command-line tool, using the same
techniques as described in the Brooklyn documentation for [running the
examples](http://brooklyncentral.github.io/use/examples/index.html), like this:

    % export BROOKLYN_CLASSPATH=target/brooklyn-opengamma-0.2.0-SNAPSHOT.jar
    % brooklyn launch --app io.cloudsoft.opengamma.OpenGammaCluster \
        --location aws-ec2:us-east-1

And, of course, as it's just Java code, you can run it in any number of
ways from an IDE, by accessing the `io.cloudsoft.opengamma.OpenGammaCluster`
class and examining the `main` method.

### Demo

After about 5 minutes, when all the VMs have been created and the services
have started, the console will print out a summary of the entire
application state. This will include the OpenGamma console URL,
which should appear towards the end, as the **webapp.url** property, like
this:

    service.state: running
    webapp.url: http://ec2-54-28-5-19.compute.amazonaws.com:8000/
    Policies:
      {name=Controller targets tracker, running=true}

In the meantime you can follow the progress by opening the Brooklyn
console at <http://localhost:8081/> in your browser. In the console, you can:

* See the *Sensors* on the root of the **OpenGamma Cluster Application**,
    including a link to OpenGamma itself
* View the entities representing the **ActiveMQBroker** and **PostgreSqlNode**
    messaging and database servers
* Drill down into the individual **OpenGamma Server**s, and see detailed
    metrics (search for *time* or *reqs*)
* Step up to the parent nodes to see selected aggregated metrics (do the
    same search)
* See the *Policies* on the **OpenGamma Server** nodes, on the
    **DynamicWebAppCluster** parent, and on the **NginxController**

If you kill the process and/or the VM for a server, you will see it get removed
from the **proxy.serverpool.targets** list on the **NginxController**. The
**ServiceReplacer** policy will kick in to create a new node, which is then
added to the cluster again.

To destroy the VMs provisioned, either invoke `stop` on the root of the
application in the Brooklyn console or use the management console of your
cloud.  VMs are not destroyed simply by killing the Brooklyn process.

## Screenshots

![Brooklyn Screenshot](https://raw.github.com/cloudsoft/brooklyn-opengamma/master/docs/screenshots/brooklyn.png)
![OpenGamma Screenshot](https://raw.github.com/cloudsoft/brooklyn-opengamma/master/docs/screenshots/opengamma.png)

## More about Brooklyn

[Brooklyn](https://github.com/brooklyncentral/brooklyn/) is a Java library
and framework for managing distributed applications in the cloud. It has
been used to create this project for rolling out OpenGamma, as well as
many other distributed software packages.

This project can be extended for more complex topologies, additional
applications which run alongside OpenGamma core, and to develop
sophisticated management policies to scale or tune the cluster for specific
applications.

For more information on the open-source Brooklyn project visit <http://brooklyn.io/> or:

- [Fork the code on GitHub](https://github.com/brooklyncentral/brooklyn/fork)
- [Join the brooklyn-users discussion group](https://groups.google.com/forum/#!forum/brooklyn-users) 

For commercial enquiries including bespoke development and paid support
contact [Cloudsoft Corp.](http://www.cloudsoftcorp.com/), the supporters of
Brooklyn, at **[info@cloudsoftcorp.com](mailto:info@cloudsoftcorp.com)**.

***

This software is Copyright 2013 by Cloudsoft Corp.; Released as open source
under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

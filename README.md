brooklyn-opengamma
==================

Brooklyn deployment and management of the OpenGamma financial analytics platform (opengamma.com)

For screenshots, see: https://github.com/cloudsoft/brooklyn-opengamma/tree/master/docs/screenshots/


### Setup 1:  Getting and Building

You must have the following repo installed and compiled (clone or fork the code, or download a ZIP), e.g.:

    git clone http://github.com/cloudsoft/brooklyn-opengamma
    cd brooklyn-opengamma

To compile brooklyn-opengamma, in the root of this project, you'll need maven 3 and javac 1.6 or higher;
then build a redistributable binary assembly:

    mvn clean assembly:assembly
    ls -al target/brooklyn-opengamma-*-bin.tar.gz


### Setup 2:  Credentials

To run, you'll need to specify credentials for your preferred cloud.  This can be done 
in `~/.brooklyn/brooklyn.properties`, using this syntax (shown for AWS):

    brooklyn.jclouds.aws-ec2.identity=AKXXXXXXXXXXXXXXXXXX
    brooklyn.jclouds.aws-ec2.credential=secret01xxxxxxxxxxxxxxxxxxxxxxxxxxx

Alternatively these can be set as shell environment parameters or JVM system properties.

Many other clouds are supported also, as well as pre-existing machines ("bring your own nodes"),
custom endpoints for private clouds, and specifying custom keys and passphrases.
For more information see the section on "Locations" in the User Guide:

    http://brooklyn.io/use/guide/defining-applications/common-usage.html


### Run

The `mvn assembly:assembly` command above creates a tarball in `target/brooklyn-opengamma-...-bin.tar.gz`.
Unpack this and run:

* `./start.sh --location <LOCATION>`

Where `<LOCATION>` is either:

* A cloud or environment you've set up as above, such as `aws-ec2:us-east-1`:
  Not every environment has been tested, of course, but dependencies are fairly simple, 
  mainly java and gcc, so it should be fairly portable, and quick to fix any targets which need attention.
  Let us know if we can help with some environment where this doesn't work! 
* `localhost`: This bundle can run on a localhost *nix system, standing up multiple processes,
  scaling out, and resilient to process failures (or deliberate kills). 
  This requires passwordless `ssh localhost` login, and (for nginx) a dev 
  environment including `gcc` and the lib `pcre`.
  (On OS X this can usually be achieved by installing XCode and XCode Command-Line tools, both from Apple,
  and `sudo port install pcre`, using MacPorts.)  

You can also run it using the `brooklyn` CLI tool, similarly to how the Brooklyn quick-start does it,
at http://brooklyncentral.github.com/ :

        export BROOKLYN_CLASSPATH=target/brooklyn-opengamma-0.1.0-SNAPSHOT.jar
        brooklyn launch -a io.cloudsoft.opengamma.OpenGammaCluster -l aws-ec2:us-east-1

And, of course, as it's just java, you can run it in any number of ways (such as an IDE), 
pointing at the static `main` in `io.cloudsoft.opengamma.OpenGammaCluster`.


### Demo

After about 5 minutes, it should print out the URL of the OpenGamma web/view console.
In the meantime you can follow the progress in the Brooklyn console, usually at localhost:8081.  

In the Brooklyn console, you can:

* See the sensors on the root of the "OpenGamma Cluster Application", including a link to OpenGamma itself
* Drill down into the individual OpenGamma servers, and see detailed metrics (search for "time" or "reqs")
* Step up to the parent nodes to see selected aggregated metrics (do the same search)
* See the policies on the OG server nodes, on the DWAC parent, and on nginx 
* Observe the "Summary" for the server nodes; kill the process and/or the VM, and watch it get removed from nginx, 
  the policies kick in to recover, and then the recovered node updated in nginx

To destroy the VM's provisioned, either invoke `stop` on the root of the application in the 
Brooklyn console or use the management console of your cloud.  VM's are not destroyed simply 
by killing Brooklyn.



### More about Brooklyn

Brooklyn is a code library and framework for managing distributed applications
in the cloud.  It has been used to create this project for rolling out OpenGamma,
as well as many other distributed software packages.

This project can be extended for more complex topologies, additional applications
which run alongside OpenGamma core, and to develop sophisticated management policies to
scale or tune the cluster for specific applications.

For more information consider:

* Visiting the open-source Brooklyn home page at  http://brooklyncentral.github.com
* Forking the Brooklyn project at  http://github.com/brooklyncentral/brooklyn
* Forking the brooklyn-opengamma project at  http://github.com/cloudsoft/brooklyn-opengamma
* Emailing  brooklyn-users@googlegroups.com 

For commercial enquiries -- including bespoke development and paid support --
contact Cloudsoft, the supporters of Brooklyn, at:

* www.CloudsoftCorp.com
* info@cloudsoftcorp.com

This software is (c) 2013 Cloudsoft Corporation, released as open source under the Apache License v2.0.


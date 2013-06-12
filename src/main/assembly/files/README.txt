brooklyn-opengamma
==================

Brooklyn deployment and management of the OpenGamma financial analytics platform (opengamma.com)


### Setup:  Credentials

To run, you'll need to specify credentials for your preferred cloud.  This can be done 
in `~/.brooklyn/brooklyn.properties`:

    brooklyn.jclouds.aws-ec2.identity=AKXXXXXXXXXXXXXXXXXX
    brooklyn.jclouds.aws-ec2.credential=secret01xxxxxxxxxxxxxxxxxxxxxxxxxxx

Alternatively these can be set as shell environment parameters or JVM system properties.

Many other clouds are supported also, as well as pre-existing machines ("bring your own nodes"),
custom endpoints for private clouds, and specifying custom keys and passphrases.
For more information see:

    https://github.com/brooklyncentral/brooklyn/blob/master/docs/use/guide/defining-applications/common-usage.md#off-the-shelf-locations


### Run

To run it:

   ./start.sh

After about 5 minutes, it should print out the URL of the OpenGamma web/view console.
In the meantime you can follow the progress in the Brooklyn console, usually at localhost:8081.  

To destroy the VM's provisioned, either invoke `stop` on the root of the application in the 
Brooklyn console or use the management console of your cloud.  VM's are not destroyed simply 
by killing Brooklyn.


### More about Brooklyn

Brooklyn is a code library and framework for managing distributed applications
in the cloud.  It has been used to create this project for rolling out OpenGamma,
as well as many other non-trivial software and cloud solutions.

This project can be extended for more complex topologies, additional applications
which run alongside OpenGamma, and to develop sophisticated management policies to
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


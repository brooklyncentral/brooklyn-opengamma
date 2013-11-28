#!/bin/sh
set -e

SCRIPTDIR="$(dirname "$0")"

# based on init-examples-simulated-db.sh with one change, to append the properties file as the first arg

echo "### Creating populated database"

${SCRIPTDIR}/run-tool.sh --chdirtoinstallation \
  -Xms512M \
  -Xmx1024M \
  -Dlogback.configurationFile=jetty-logback.xml \
  com.opengamma.examples.simulated.tool.ExampleDatabaseCreator \
  classpath:brooklyn/toolcontext-example.properties

echo "### Completed"

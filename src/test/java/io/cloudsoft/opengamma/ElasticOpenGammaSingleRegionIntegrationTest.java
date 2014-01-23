package io.cloudsoft.opengamma;

import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.database.postgresql.PostgreSqlNode;
import brooklyn.entity.database.postgresql.PostgreSqlSpecs;
import brooklyn.entity.messaging.activemq.ActiveMQBroker;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.trait.Startable;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.test.Asserts;
import brooklyn.test.EntityTestUtils;
import brooklyn.test.entity.TestApplication;
import com.google.common.collect.ImmutableList;
import io.cloudsoft.opengamma.server.OpenGammaMonitoringAggregation;
import io.cloudsoft.opengamma.server.OpenGammaServer;
import io.cloudsoft.opengamma.server.SimulatedExamplesServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public class ElasticOpenGammaSingleRegionIntegrationTest {

    private TestApplication app;
    private LocalhostMachineProvisioningLocation localhostProvisioningLocation;

    @BeforeMethod(alwaysRun = true)
    public void setUp() throws Exception {
        localhostProvisioningLocation = new LocalhostMachineProvisioningLocation();
        app = ApplicationBuilder.newManagedApp(TestApplication.class);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        if (app != null) Entities.destroyAll(app.getManagementContext());
    }

    @Test(groups = "Integration")
    public void testCanStartAndStop() throws Exception {
        ActiveMQBroker broker = app.createAndManageChild(EntitySpec.create(ActiveMQBroker.class));
        PostgreSqlNode database = app.createAndManageChild(EntitySpec.create(PostgreSqlSpecs.spec()
                .configure(PostgreSqlNode.CREATION_SCRIPT_URL, "classpath:/io/cloudsoft/opengamma/config/create-brooklyn-db.sql")
                .configure(PostgreSqlNode.DISCONNECT_ON_STOP, true)));
        EntitySpec<ControlledDynamicWebAppCluster> web = EntitySpec.create(ControlledDynamicWebAppCluster.class)
                .displayName("Load-Balanced Cluster")
                .configure(ControlledDynamicWebAppCluster.INITIAL_SIZE, 1)
                .configure(ControlledDynamicWebAppCluster.MEMBER_SPEC,
                        EntitySpec.create(SimulatedExamplesServer.class)
                                .displayName("OpenGamma Server")
                                .configure(OpenGammaServer.BROKER, broker)
                                .configure(OpenGammaServer.DATABASE, database));

        final ControlledDynamicWebAppCluster ogWebCluster = app.createAndManageChild(web);

        assertEquals(ogWebCluster.getCurrentSize().intValue(), 0);
        app.start(ImmutableList.of(localhostProvisioningLocation));

        EntityTestUtils.assertAttributeEqualsEventually(ogWebCluster, Startable.SERVICE_UP, true);
        assertEquals(ogWebCluster.getCurrentSize().intValue(), 1);

        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                assertTrue(ogWebCluster.getAttribute(OpenGammaMonitoringAggregation.PROCESSING_TIME_PER_SECOND_LAST) >= 0);
                assertTrue(ogWebCluster.getAttribute(OpenGammaMonitoringAggregation.PROCESSING_TIME_PER_SECOND_IN_WINDOW) >= 0);
                assertTrue(ogWebCluster.getAttribute(OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT) >= 0);
                assertTrue(ogWebCluster.getAttribute(OpenGammaMonitoringAggregation.PROCESS_CPU_TIME_FRACTION_IN_WINDOW) >= 0);
                assertTrue(ogWebCluster.getAttribute(OpenGammaMonitoringAggregation.PROCESSING_TIME_PER_SECOND_LAST_PER_NODE) >= 0);
                assertTrue(ogWebCluster.getAttribute(OpenGammaMonitoringAggregation.PROCESSING_TIME_PER_SECOND_IN_WINDOW_PER_NODE) >= 0);
                assertTrue(ogWebCluster.getAttribute(OpenGammaMonitoringAggregation.VIEW_PROCESSES_COUNT_PER_NODE) >= 0);
                assertTrue(ogWebCluster.getAttribute(OpenGammaMonitoringAggregation.PROCESS_CPU_TIME_FRACTION_IN_WINDOW_PER_NODE) >= 0);
            }
        });
        ogWebCluster.stop();
        assertFalse(ogWebCluster.getAttribute(Startable.SERVICE_UP));
    }

}
package io.cloudsoft.opengamma;

import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import io.cloudsoft.opengamma.app.ClusteredOpenGammaApplication;
import io.cloudsoft.opengamma.server.OpenGammaMonitoringAggregation;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.trait.Startable;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.test.Asserts;
import brooklyn.test.EntityTestUtils;

import com.google.common.collect.ImmutableList;

public class ElasticOpenGammaSingleRegionIntegrationTest {

    private ClusteredOpenGammaApplication app;
    private LocalhostMachineProvisioningLocation localhostProvisioningLocation;

    @BeforeMethod(alwaysRun = true)
    public void setUp() throws Exception {
        localhostProvisioningLocation = new LocalhostMachineProvisioningLocation();
        app = ApplicationBuilder.newManagedApp(EntitySpec.create(ClusteredOpenGammaApplication.class)
                .configure(ClusteredOpenGammaApplication.SUPPORT_MULTIREGION, false));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        if (app != null) Entities.destroyAll(app.getManagementContext());
    }

    @Test(groups = "Integration")
    public void testCanStartAndStop() throws Exception {
        ControlledDynamicWebAppCluster ogWebCluster = null;
        app.start(ImmutableList.of(localhostProvisioningLocation));
        Entities.dumpInfo(app);
        for (Entity child : app.getChildren()) {
            if(child instanceof ControlledDynamicWebAppCluster) {
                ogWebCluster = (ControlledDynamicWebAppCluster) child;
            }

        }
        EntityTestUtils.assertAttributeEqualsEventually(ogWebCluster, Startable.SERVICE_UP, true);
        assertEquals(ogWebCluster.getCurrentSize().intValue(), 2);

        Asserts.succeedsEventually(new CheckControlledDynamicWebAppClusterStatus(ogWebCluster));
        ogWebCluster.stop();
        assertFalse(ogWebCluster.getAttribute(Startable.SERVICE_UP));
    }

    private class CheckControlledDynamicWebAppClusterStatus implements Runnable {

        private ControlledDynamicWebAppCluster ogWebCluster;

        public CheckControlledDynamicWebAppClusterStatus(ControlledDynamicWebAppCluster ogWebCluster) {
            this.ogWebCluster = ogWebCluster;
        }

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
    }
}
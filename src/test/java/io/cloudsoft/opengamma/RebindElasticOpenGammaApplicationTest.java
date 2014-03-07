package io.cloudsoft.opengamma;

import io.cloudsoft.opengamma.app.ElasticOpenGammaApplication;

import java.io.File;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.Application;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.rebind.RebindTestUtils;
import brooklyn.management.internal.LocalManagementContext;

import com.google.common.io.Files;

public class RebindElasticOpenGammaApplicationTest {

	private File mementoDir;
	private LocalManagementContext origManagementContext;
	private Application origApp;
	private Application newApp;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() {
        mementoDir = Files.createTempDir();
        origManagementContext = RebindTestUtils.newPersistingManagementContext(mementoDir, getClass().getClassLoader(), 1);
        origManagementContext.getBrooklynProperties().put("brooklyn.geoscaling.password", "dummy-password-want-this-to-fail-so-subdomain-not-setup");
    }
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() {
        if (origManagementContext != null) Entities.destroyAll(origManagementContext);
        if (newApp != null) Entities.destroyAll(newApp.getManagementContext());
        if (mementoDir != null) RebindTestUtils.deleteMementoDir(mementoDir);
    }

    @Test
    public void testMultiRegionRebindableBeforeStart() throws Exception {
        origApp = origManagementContext.getEntityManager().createEntity(EntitySpec.create(StartableApplication.class, ElasticOpenGammaApplication.class)
            .configure(ElasticOpenGammaApplication.SUPPORT_MULTIREGION, true));
        Entities.startManagement(origApp, origManagementContext);
    
    	newApp = rebind();
    	Entities.dumpInfo(newApp);
    	
    	((StartableApplication)newApp).stop();
    }
    
    @Test
    public void testSingleRegionRebindableBeforeStart() throws Exception {
        origApp = origManagementContext.getEntityManager().createEntity(EntitySpec.create(StartableApplication.class, ElasticOpenGammaApplication.class)
            .configure(ElasticOpenGammaApplication.SUPPORT_MULTIREGION, false));
        Entities.startManagement(origApp, origManagementContext);
    
        newApp = rebind();
        Entities.dumpInfo(newApp);
        
        ((StartableApplication)newApp).stop();
    }
    
    private Application rebind() throws Exception {
        RebindTestUtils.waitForPersisted(origApp);
        origManagementContext.terminate();
        origManagementContext = null;
        return (Application) RebindTestUtils.rebind(mementoDir, getClass().getClassLoader());
    }
}

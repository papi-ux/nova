package com.papi.nova.manager;

import com.papi.nova.api.PolarisCapabilities;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FeatureFlagManagerTest {

    @After
    public void tearDown() {
        FeatureFlagManager.INSTANCE.reset();
    }

    @Test
    public void hasCursorVisibilityControl_reflectsCapabilities() throws Exception {
        setCapabilities(new PolarisCapabilities(
                "polaris",
                "1.0.0",
                new PolarisCapabilities.Features(false, false, false, false, false, true),
                new PolarisCapabilities.CaptureInfo()
        ));

        assertTrue(FeatureFlagManager.INSTANCE.getHasCursorVisibilityControl());
    }

    @Test
    public void hasCursorVisibilityControl_isFalseWithoutCapabilities() {
        FeatureFlagManager.INSTANCE.reset();

        assertFalse(FeatureFlagManager.INSTANCE.getHasCursorVisibilityControl());
    }

    private static void setCapabilities(PolarisCapabilities capabilities) throws Exception {
        Field field = FeatureFlagManager.class.getDeclaredField("capabilities");
        field.setAccessible(true);
        field.set(null, capabilities);
    }
}

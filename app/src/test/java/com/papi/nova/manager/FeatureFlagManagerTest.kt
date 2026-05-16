package com.papi.nova.manager

import com.papi.nova.api.PolarisCapabilities
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagManagerTest {

    @After
    fun tearDown() {
        FeatureFlagManager.reset()
    }

    @Test
    fun hasCursorVisibilityControlReflectsCapabilities() {
        setCapabilities(
            PolarisCapabilities(
                server = "polaris",
                version = "1.0.0",
                features = PolarisCapabilities.Features(cursorVisibilityControl = true),
                capture = PolarisCapabilities.CaptureInfo()
            )
        )

        assertTrue(FeatureFlagManager.hasCursorVisibilityControl)
    }

    @Test
    fun hasCursorVisibilityControlIsFalseWithoutCapabilities() {
        FeatureFlagManager.reset()

        assertFalse(FeatureFlagManager.hasCursorVisibilityControl)
    }

    private fun setCapabilities(capabilities: PolarisCapabilities) {
        val field = FeatureFlagManager::class.java.getDeclaredField("capabilities")
        field.isAccessible = true
        field.set(null, capabilities)
    }
}

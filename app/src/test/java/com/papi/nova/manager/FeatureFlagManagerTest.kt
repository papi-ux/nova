package com.papi.nova.manager

import com.papi.nova.api.PolarisCapabilities
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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

    @Test
    fun obsoleteScopeCannotPublishOrResetNewGameCapabilities() {
        val oldScope = FeatureFlagManager.beginScope()
        val currentScope = FeatureFlagManager.beginScope()
        val current = PolarisCapabilities(
            server = "polaris",
            version = "1.3.14",
            features = PolarisCapabilities.Features(doctorV2ShadowEnabled = true),
            capture = PolarisCapabilities.CaptureInfo()
        )

        assertFalse(FeatureFlagManager.publishForScope(oldScope, current))
        assertTrue(FeatureFlagManager.publishForScope(currentScope, current))
        FeatureFlagManager.reset(oldScope)

        assertNull(FeatureFlagManager.capabilitiesForScope(oldScope))
        assertTrue(FeatureFlagManager.capabilitiesForScope(currentScope) === current)
    }

    private fun setCapabilities(capabilities: PolarisCapabilities) {
        val field = FeatureFlagManager::class.java.getDeclaredField("capabilities")
        field.isAccessible = true
        field.set(null, capabilities)
    }
}

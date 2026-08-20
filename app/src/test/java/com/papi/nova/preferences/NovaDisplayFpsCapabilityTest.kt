package com.papi.nova.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class NovaDisplayFpsCapabilityTest {
    @Test
    fun panelBelow90ThresholdOffersOnly30And60() {
        assertEquals(listOf(30, 60), NovaDisplayFpsCapability.allowedFpsValues(59.9f))
        assertEquals(listOf(30, 60), NovaDisplayFpsCapability.allowedFpsValues(60f))
        assertEquals(listOf(30, 60), NovaDisplayFpsCapability.allowedFpsValues(87.9f))
    }

    @Test
    fun panelAt90ThresholdAdds90ButNot120() {
        assertEquals(listOf(30, 60, 90), NovaDisplayFpsCapability.allowedFpsValues(88f))
        assertEquals(listOf(30, 60, 90), NovaDisplayFpsCapability.allowedFpsValues(90f))
        assertEquals(listOf(30, 60, 90), NovaDisplayFpsCapability.allowedFpsValues(117.9f))
    }

    @Test
    fun panelAt120ThresholdOffersEverything() {
        assertEquals(listOf(30, 60, 90, 120), NovaDisplayFpsCapability.allowedFpsValues(118f))
        assertEquals(listOf(30, 60, 90, 120), NovaDisplayFpsCapability.allowedFpsValues(120f))
        assertEquals(listOf(30, 60, 90, 120), NovaDisplayFpsCapability.allowedFpsValues(144f))
    }

    @Test
    fun coerceFollowsTheLegacyFallbackChain() {
        // 120 on a panel that can only offer 90 falls to 90, exactly like the
        // legacy removeEntryFromListAndSetValue(FPS, "120", "90") rewrite.
        assertEquals(90, NovaDisplayFpsCapability.coerce(120, 100f))
        // 120 on a 60Hz panel cascades all the way down, like the legacy
        // double rewrite 120 -> 90 -> 60.
        assertEquals(60, NovaDisplayFpsCapability.coerce(120, 60f))
        assertEquals(60, NovaDisplayFpsCapability.coerce(90, 60f))
    }

    @Test
    fun coerceLeavesAllowedValuesAlone() {
        assertEquals(120, NovaDisplayFpsCapability.coerce(120, 120f))
        assertEquals(90, NovaDisplayFpsCapability.coerce(90, 90f))
        assertEquals(30, NovaDisplayFpsCapability.coerce(30, 60f))
    }

    @Test
    fun coerceLeavesNonStandardValuesAlone() {
        // The legacy culling only ever rewrote the standard entries it removed;
        // a native or custom rate must pass through untouched.
        assertEquals(144, NovaDisplayFpsCapability.coerce(144, 60f))
        assertEquals(59, NovaDisplayFpsCapability.coerce(59, 60f))
    }
}

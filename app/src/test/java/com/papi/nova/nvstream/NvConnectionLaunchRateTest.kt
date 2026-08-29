package com.papi.nova.nvstream

import org.junit.Assert.assertEquals
import org.junit.Test

class NvConnectionLaunchRateTest {

    @Test
    fun keepsRequestedLaunchRateWhenServerDoesNotAdvertiseCap() {
        assertEquals(120.0f, NvConnection.negotiateLaunchRefreshRate(120.0f, 0), 0.001f)
    }

    @Test
    fun clampsRequestedLaunchRateToAdvertisedServerCap() {
        assertEquals(60.0f, NvConnection.negotiateLaunchRefreshRate(120.0f, 60), 0.001f)
    }

    @Test
    fun preservesCustomLaunchRateWithinAdvertisedServerCap() {
        assertEquals(75.0f, NvConnection.negotiateLaunchRefreshRate(75.0f, 120), 0.001f)
    }

    @Test
    fun fractionalMilliFpsEncodingUsesTheSameAdvertisedCap() {
        assertEquals(60_000.0f, NvConnection.negotiateLaunchRefreshRate(119_880.0f, 60), 0.001f)
        assertEquals(119.88f, NvConnection.launchRefreshRateHz(119_880.0f), 0.001f)
    }
}

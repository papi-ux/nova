package com.papi.nova.nvstream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NvConnectionLaunchRateTest {

    @Test
    public void keepsRequestedLaunchRateWhenServerDoesNotAdvertiseCap() {
        assertEquals(120.0f, NvConnection.negotiateLaunchRefreshRate(120.0f, 0), 0.001f);
    }

    @Test
    public void clampsRequestedLaunchRateToAdvertisedServerCap() {
        assertEquals(60.0f, NvConnection.negotiateLaunchRefreshRate(120.0f, 60), 0.001f);
    }

    @Test
    public void preservesCustomLaunchRateWithinAdvertisedServerCap() {
        assertEquals(75.0f, NvConnection.negotiateLaunchRefreshRate(75.0f, 120), 0.001f);
    }
}

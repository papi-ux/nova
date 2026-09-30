package com.papi.nova.preferences

import org.junit.Assert.*
import org.junit.Test

class NovaCapabilityProbeTest {
    private val size = NovaSize(3840,2160)
    @Test fun performancePointsAreAuthoritativeOverAchievableAndClaims() {
        assertTrue(NovaCapabilityProbe.classify(size,60,NovaCapabilityProbe.Report(true,true,true,30.0,true))!!.covered)
        assertFalse(NovaCapabilityProbe.classify(size,60,NovaCapabilityProbe.Report(true,true,false,120.0,true))!!.covered)
    }
    @Test fun achievableIsFallbackOnlyAndClaimIsInsufficient() {
        assertTrue(NovaCapabilityProbe.classify(size,60,NovaCapabilityProbe.Report(true,false,false,60.0,true))!!.covered)
        assertFalse(NovaCapabilityProbe.classify(size,60,NovaCapabilityProbe.Report(true,false,false,null,true))!!.covered)
        assertFalse(NovaCapabilityProbe.classify(size,60,NovaCapabilityProbe.Report(true,false,false,Double.NaN,true))!!.covered)
        assertNull(NovaCapabilityProbe.classify(size,60,NovaCapabilityProbe.Report(false,true,true,120.0,true)))
    }
}

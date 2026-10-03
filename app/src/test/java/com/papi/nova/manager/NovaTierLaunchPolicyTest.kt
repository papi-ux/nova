package com.papi.nova.manager

import com.papi.nova.preferences.PreferenceConfiguration.FormatOption
import org.junit.Assert.*
import org.junit.Test

class NovaTierLaunchPolicyTest {
    @Test fun pyrowaveAndMeteredAreTheOnlyBitrateLocks() {
        for (codec in FormatOption.entries) for (space in listOf(false,true)) for (metered in listOf(false,true)) {
            assertEquals(metered || (!space && codec == FormatOption.FORCE_PYROWAVE),
                NovaTierLaunchPolicy.bitrateLocked(codec,space,metered))
        }
    }
    @Test fun spaceNeverLocksDisplayAndNullCodecIsNotPyrowave() {
        assertFalse(NovaTierLaunchPolicy.displayLocked(true,true))
        assertTrue(NovaTierLaunchPolicy.displayLocked(true,false))
        assertFalse(NovaTierLaunchPolicy.bitrateLocked(null,false,false))
    }
}

package com.papi.nova.utils

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionControlHostPolicyTest {
    @Test
    fun defaultDisplayUsesActivityFallback() {
        assertEquals(
            CompanionControlHostPolicy.HostType.ACTIVITY,
            CompanionControlHostPolicy.select(Display.DEFAULT_DISPLAY),
        )
    }

    @Test
    fun nonDefaultDisplayUsesPresentationHost() {
        assertEquals(
            CompanionControlHostPolicy.HostType.PRESENTATION,
            CompanionControlHostPolicy.select(Display.DEFAULT_DISPLAY + 1),
        )
    }
}

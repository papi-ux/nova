package com.papi.nova.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionScreenDimmingPolicyTest {
    @Test
    fun configuredTimeoutConvertsToMilliseconds() {
        assertEquals(30_000L, CompanionScreenDimmingPolicy.delayMillis(timeoutSeconds = 30))
    }

    @Test
    fun neverDisablesCompanionDimming() {
        assertNull(CompanionScreenDimmingPolicy.delayMillis(timeoutSeconds = 0))
    }

    @Test
    fun companionCanDimWhenNoInteractiveOverlayIsOpen() {
        assertTrue(
            CompanionScreenDimmingPolicy.shouldDimNow(
                keyboardVisible = false,
                quickMenuOpen = false,
            )
        )
    }

    @Test
    fun companionStaysAwakeWhileKeyboardIsVisible() {
        assertFalse(
            CompanionScreenDimmingPolicy.shouldDimNow(
                keyboardVisible = true,
                quickMenuOpen = false,
            )
        )
    }

    @Test
    fun companionStaysAwakeWhileQuickMenuIsOpen() {
        assertFalse(
            CompanionScreenDimmingPolicy.shouldDimNow(
                keyboardVisible = false,
                quickMenuOpen = true,
            )
        )
    }
}

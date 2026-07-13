package com.papi.nova.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionControlLifecyclePolicyTest {
    @Test
    fun activeLiveGameCanShowCompanionControls() {
        assertTrue(CompanionControlLifecyclePolicy.canShow(true, false, false))
    }

    @Test
    fun inactiveStreamCannotShowCompanionControls() {
        assertFalse(CompanionControlLifecyclePolicy.canShow(false, false, false))
    }

    @Test
    fun finishingGameCannotShowCompanionControls() {
        assertFalse(CompanionControlLifecyclePolicy.canShow(true, true, false))
    }

    @Test
    fun destroyedGameCannotShowCompanionControls() {
        assertFalse(CompanionControlLifecyclePolicy.canShow(true, false, true))
    }
}

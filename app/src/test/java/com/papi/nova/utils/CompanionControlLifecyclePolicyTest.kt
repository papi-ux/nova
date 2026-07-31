package com.papi.nova.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionControlLifecyclePolicyTest {
    @Test
    fun activeLiveGameCanShowCompanionControls() {
        assertTrue(CompanionControlLifecyclePolicy.canShow(true, false, false, false, false))
    }

    @Test
    fun inactiveStreamCannotShowCompanionControls() {
        assertFalse(CompanionControlLifecyclePolicy.canShow(false, false, false, false, false))
    }

    @Test
    fun finishingGameCannotShowCompanionControls() {
        assertFalse(CompanionControlLifecyclePolicy.canShow(true, true, false, false, false))
    }

    @Test
    fun destroyedGameCannotShowCompanionControls() {
        assertFalse(CompanionControlLifecyclePolicy.canShow(true, false, true, false, false))
    }

    @Test
    fun sessionDismissalBlocksAutomaticLifecycleReopen() {
        assertFalse(CompanionControlLifecyclePolicy.canShow(true, false, false, true, false))
    }

    @Test
    fun explicitUserRequestCanReopenADismissedCompanion() {
        assertTrue(CompanionControlLifecyclePolicy.canShow(true, false, false, true, true))
    }

    @Test
    fun explicitRequestCannotOverrideDeadGameLifecycle() {
        assertFalse(CompanionControlLifecyclePolicy.canShow(false, false, false, true, true))
        assertFalse(CompanionControlLifecyclePolicy.canShow(true, true, false, true, true))
        assertFalse(CompanionControlLifecyclePolicy.canShow(true, false, true, true, true))
    }

    @Test
    fun hideRequiresAUsableExplicitReopenAuthority() {
        assertTrue(CompanionControlLifecyclePolicy.canHide(reopenAvailable = true))
        assertFalse(CompanionControlLifecyclePolicy.canHide(reopenAvailable = false))
    }

    @Test
    fun dismissedCompanionRestoresIfExplicitReopenAuthorityDisappears() {
        assertTrue(
            CompanionControlLifecyclePolicy.shouldRestoreDismissedCompanion(
                dismissedByUser = true,
                reopenAvailable = false,
            ),
        )
        assertFalse(
            CompanionControlLifecyclePolicy.shouldRestoreDismissedCompanion(
                dismissedByUser = true,
                reopenAvailable = true,
            ),
        )
        assertFalse(
            CompanionControlLifecyclePolicy.shouldRestoreDismissedCompanion(
                dismissedByUser = false,
                reopenAvailable = false,
            ),
        )
    }

    @Test
    fun displayTeardownPreservesReopenOnlyForAnActiveDismissedSession() {
        assertTrue(
            CompanionControlLifecyclePolicy.shouldPreserveReopenNotification(
                streamActive = true,
                dismissedByUser = true,
            ),
        )
        assertFalse(
            CompanionControlLifecyclePolicy.shouldPreserveReopenNotification(
                streamActive = false,
                dismissedByUser = true,
            ),
        )
        assertFalse(
            CompanionControlLifecyclePolicy.shouldPreserveReopenNotification(
                streamActive = true,
                dismissedByUser = false,
            ),
        )
    }
}

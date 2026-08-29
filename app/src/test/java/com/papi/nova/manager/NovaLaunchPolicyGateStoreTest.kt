package com.papi.nova.manager

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLaunchPolicyGateStoreTest {
    @After
    fun tearDown() {
        NovaLaunchPolicyGateStore.clearForTests()
    }

    @Test
    fun decisionIsOneShotAndBoundToExactLaunchFingerprint() {
        val decision = NovaLaunchPolicyGateStore.Decision(
            optimizationJson = "{\"resolved_profile\":{}}",
            profilePreference = "high_fps",
            resolvedProfileTrusted = true
        )
        val mismatchedToken = NovaLaunchPolicyGateStore.issue("host-a|game-a|120", decision)
        val matchingToken = NovaLaunchPolicyGateStore.issue("host-a|game-a|120", decision)

        assertNull(NovaLaunchPolicyGateStore.consume(mismatchedToken, "host-a|game-b|120"))
        assertEquals(decision, NovaLaunchPolicyGateStore.consume(matchingToken, "host-a|game-a|120"))
        assertNull(NovaLaunchPolicyGateStore.consume(matchingToken, "host-a|game-a|120"))
        assertTrue(decision.resolvedProfileTrusted)
    }
}

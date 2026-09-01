package com.papi.nova.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarisLiveStatusRefreshPolicyTest {
    @Test
    fun activeStreamPollFitsInsidePolarisDoctorEvidenceWindow() {
        assertTrue(PolarisLiveStatusRefreshPolicy.ACTIVE_STREAM_POLL_INTERVAL_MS > 0L)
        assertTrue(
            PolarisLiveStatusRefreshPolicy.ACTIVE_STREAM_POLL_INTERVAL_MS <
                POLARIS_CURRENT_DOCTOR_EVIDENCE_MAX_AGE_MS
        )
    }

    @Test
    fun gameUsesTheLivePolicyForInitialAndRecurringScheduling() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        val policyReferences = Regex(
            "PolarisLiveStatusRefreshPolicy\\.ACTIVE_STREAM_POLL_INTERVAL_MS"
        ).findAll(game).count()

        assertEquals(2, policyReferences)
        assertFalse(game.contains("POLARIS_SESSION_STATUS_REFRESH_MS"))
    }

    private companion object {
        const val POLARIS_CURRENT_DOCTOR_EVIDENCE_MAX_AGE_MS = 2_000L
    }
}

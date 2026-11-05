package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTimePacingStatusTest {
    private val grammar = Regex(
        "st1;requested=(off|auto|source_time);" +
            "effective=(legacy|pending|source_time_fixed|source_time_vrr|unavailable);" +
            "vrr_requested=[01];vrr_effective=[01];fallback=[a-z_]+",
    )

    @Test
    fun theWireFormKeepsTheSharedKeyOrder() {
        val status = SourceTimePacingStatus(
            SourceTimeRequest.AUTO,
            SourceTimeEffective.SOURCE_TIME_FIXED,
            SourceTimeReason.DISPLAY_FIXED_REFRESH,
        )
        assertEquals(
            "st1;requested=auto;effective=source_time_fixed;vrr_requested=0;vrr_effective=0;" +
                "fallback=display_fixed_refresh",
            status.toFramePacingState(),
        )
    }

    @Test
    fun everyCombinationFitsTheHostFieldAndTheGrammar() {
        for (requested in SourceTimeRequest.values()) {
            for (effective in SourceTimeEffective.values()) {
                for (reason in SourceTimeReason.values()) {
                    for (vrr in listOf(false, true)) {
                        val wire = SourceTimePacingStatus(requested, effective, reason, vrr, vrr).toFramePacingState()
                        assertTrue(wire, wire.length <= SourceTimePacingStatus.MAX_WIRE_LENGTH)
                        assertTrue(wire, wire.all { it.code in 0x21..0x7e })
                        assertTrue(wire, grammar.matches(wire))
                    }
                }
            }
        }
    }

    @Test
    fun aRendererThatCannotPaceSaysSoWhateverWasAsked() {
        for (requested in SourceTimeRequest.values()) {
            val status = SourceTimePacingStatus.notParticipating(requested)
            assertEquals(SourceTimeEffective.UNAVAILABLE, status.effective)
            assertEquals("renderer_not_participating", status.fallback.wire)
            assertEquals(false, status.vrrRequested)
        }
    }
}

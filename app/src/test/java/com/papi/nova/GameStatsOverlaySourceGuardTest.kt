package com.papi.nova

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStatsOverlaySourceGuardTest {
    @Test
    fun statsOverlayToggleIsPersistedAndReArmsTheDecoderText() {
        val source = readGameSource()
        val toggle = source.section("fun toggleHUD()", " fun switchTouchSensitivity()")

        assertTrue(
            "the Command Center toggle persists the setting; it used to flip an in-memory flag that Settings never saw",
            toggle.contains("putBoolean(PreferenceConfiguration.ENABLE_PERF_OVERLAY_STRING, enabled)")
        )
        assertTrue(
            "the decoder builds the overlay text only while it is wanted, so the toggle must re-arm it or a stream that started with the setting off shows an empty overlay",
            toggle.contains("syncPerfTextWanted()")
        )
    }

    @Test
    fun novaHudNoLongerForcesTheDecoderToBuildLegacyText() {
        val source = readGameSource()
        val sync = source.section("private fun syncPerfTextWanted()", "fun copyNovaHudDiagnostics()")
        val perfUpdate = source.section("override fun onPerfUpdate(text:String)", "override fun onPerfSample(sample:PerfOverlaySample)")

        assertFalse(
            "only the legacy overlay needs the string; the HUD reads the structured sample the decoder emits regardless",
            sync.contains("novaHud")
        )
        assertFalse(
            "the text callback feeds the legacy TextView and nothing else",
            perfUpdate.contains("novaHud")
        )
    }

    private fun readGameSource(): String =
        String(Files.readAllBytes(Path.of("src/main/java/com/papi/nova/Game.kt")), StandardCharsets.UTF_8)

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }
}

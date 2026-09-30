package com.papi.nova.ui

import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Play Setup's copy says each thing once, in one name, as the rest of the panel says it (N19, N29). */
class NovaPlaySetupPlanCopyTest {
    private fun summary(selected: String, requested: String = "Requested: Quality · 120 FPS") = NovaLaunchProfileSummary(
        primaryLaunchLabel = "Launch at 120 FPS",
        requestedLine = requested,
        selectedLine = selected,
        reasonLine = "",
        limitingLine = "",
        noticeDetail = "",
        noticeRecommendation = "",
        noticeTone = NovaLaunchProfileNoticeTone.HEALTHY,
        noticeLabel = "",
        freshnessLine = "",
        historyLines = emptyList(),
        showRetryHighFps = false,
        retryHighFpsLabel = "",
    )

    private fun plan(summary: NovaLaunchProfileSummary, lines: List<String>) = novaPlaySetupPlan(
        modeLabel = "Private Stream",
        lines = lines,
        summary = summary,
        lastSessionKey = "Last Session",
        limitedByKey = "Limited By",
        askedKey = "Asked / Granted",
        profileKey = "Profile",
        grantedFormat = "Granted: %s",
    )

    @Test
    fun theGrantThePlansOwnLineStatesIsNotSaidAgainUnderIt() {
        val selected = "Resolved: 3840×2160 @ 120 FPS · 300 Mbps"
        val same = plan(summary(selected), listOf(novaPlaySetupValue(selected)))
        val asked = same.facts.single { it.key == "Asked / Granted" }
        assertEquals("Quality · 120 FPS", asked.value)
        assertFalse("the Granted line repeated the plan's first line: ${asked.detail}", asked.detail.contains("Granted"))

        val other = plan(summary(selected), listOf("1920×1080 @ 60 FPS"))
        assertTrue(other.facts.single { it.key == "Asked / Granted" }.detail.startsWith("Granted: 3840×2160"))
    }

    @Test
    fun theHostProfileNamesItsDisplayModeWithOneMultiplicationSign() {
        val settings = PolarisClientSettings(
            desired = PolarisClientSettings.Desired(displayMode = "3840x2160x120", targetBitrateKbps = 300_000),
        )
        val state = NovaGameDetailUiState.from(
            game = PolarisGame(id = "g", name = "Control", source = "steam"),
            defaultToVirtualDisplay = false,
            clientSettings = settings,
            profilePreference = "auto",
        )
        assertEquals("3840×2160 at 120\u00a0Hz · 300\u00a0Mbps", state.hostProfileLabel)
    }

    @Test
    fun thePlanNamesTheModeInFullAndNeverTheAppsOwnDefault() {
        val activity = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val intro = activity.substringAfter("private fun buildLaunchIntro(").substringBefore("private fun lastPlayedText(")
        assertFalse("This app's default named a mode that would not happen", intro.contains("preferred_mode"))
        assertTrue(
            "the host's mode is a sentence the host's own sentence can follow",
            strings.contains("name=\"nova_polaris_sync_host_mode_detail\">The host defaults to %1\$s.</string>"),
        )
        assertTrue(
            "Play Setup names Host Virtual Display in full, as Where It Runs lists it",
            activity.contains("virtualDisplayModeLabel = modeLabel(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY)") &&
                activity.contains("label = modeLabel(PolarisGame.MODE_HOST_VIRTUAL_DISPLAY)"),
        )
    }

    @Test
    fun frameRateReadsAutoAtRestWhileAutoIsChosen() {
        val activity = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText()
        val row = activity.substringAfter("row = NovaPlaySetupRow.FRAME_RATE,").substringBefore("options = buildList {")
        assertTrue(
            "the row said 60 FPS while its strip marked Auto (N29)",
            row.contains("value = if (chosenFps == null) {") &&
                row.contains("R.string.nova_play_setup_frame_rate_auto_value"),
        )
    }
}

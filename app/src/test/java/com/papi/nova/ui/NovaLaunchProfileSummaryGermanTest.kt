package com.papi.nova.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Launch and the plan say comes from string resources, so a German device reads German (N22):
 * the builder wrote every line in English, the last of them "Launch at %s FPS".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLaunchProfileSummaryGermanTest {
    private val autoPreset = JSONObject(
        """{"source":"deterministic_preset_v1","resolved_profile":{"policy_version":1,"preset":"auto","fields":{
            "display_mode":{"value":"3840x2160x120"},"display_width":{"value":3840},"display_height":{"value":2160},
            "target_fps":{"value":120},"target_bitrate_kbps":{"value":300000},"hdr":{"value":false}}}}""",
    )

    @Test
    fun inEnglishLaunchSaysTheRateAlone() {
        val summary = buildTestLaunchProfileSummary(autoPreset, clientAskedHdr = false)!!
        assertEquals("Launch at 120 FPS", summary.primaryLaunchLabel)
        assertEquals("Resolved: 3840×2160 @ 120 FPS · 300 Mbps · SDR (HDR not requested)", summary.selectedLine)
    }

    @Test
    @Config(qualifiers = "de")
    fun onAGermanDeviceLaunchAndThePlanSpeakGerman() {
        val summary = buildTestLaunchProfileSummary(autoPreset, clientAskedHdr = false)!!
        assertEquals("Mit 120 FPS starten", summary.primaryLaunchLabel)
        assertEquals("Festgelegt: 3840×2160 @ 120 FPS · 300 Mbps · SDR (HDR nicht angefragt)", summary.selectedLine)
        assertEquals("Für diesen Start festgelegt", summary.freshnessLine)
        assertTrue(summary.noticeRecommendation.startsWith("HDR ist in den Einstellungen dieses Geräts aus"))
    }

    @Test
    @Config(qualifiers = "de")
    fun aKnownIssueIsNamedInGermanAndAnUnknownOneKeepsItsOwnName() {
        val text = testLaunchProfileText()
        assertEquals("Netzwerk", novaLaunchIssueLabel("network", text))
        assertEquals("History Safe Profile", novaLaunchIssueLabel("history_safe_profile", text))
    }
}

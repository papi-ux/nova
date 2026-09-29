package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.panel.setPanelContent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The game page's status line says what holds the launch back. When PyroWave's bitrate verdict
 * fired it took the place of the host's own limiting line, so a limit the host reported went
 * unsaid (#10). Both are said now, the host's first, through the page as the activity composes it,
 * and "Limited by" once: the two lines together had read "LIMITED BY: NETWORK · LIMITED BY BITRATE".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w833dp-h468dp")
class NovaGameDetailStatusLimitComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val uiState = NovaGameDetailUiState.from(
        game = PolarisGame(id = "game-1", name = "Control", source = "steam", launcherSource = "steam"),
        defaultToVirtualDisplay = false,
        clientSettings = PolarisClientSettings(),
        profilePreference = "auto",
    )

    private fun summary(limitingLine: String) = NovaLaunchProfileSummary(
        primaryLaunchLabel = "Launch Quality · 120 FPS",
        requestedLine = "",
        selectedLine = "3840x2160 · 120 FPS",
        reasonLine = "",
        limitingLine = limitingLine,
        noticeDetail = "",
        noticeRecommendation = "",
        noticeTone = NovaLaunchProfileNoticeTone.WARNING,
        noticeLabel = "",
        freshnessLine = "",
        historyLines = emptyList(),
        showRetryHighFps = false,
        retryHighFpsLabel = "",
    )

    /** The host's own plan, as the summary builder makes it from the host's answer, limited by [issue]. */
    private fun hostSummary(issue: String?) = buildTestLaunchProfileSummary(
        JSONObject(
            """{
                "source":"history_safe",
                "display_mode":"1920x1080x60",
                "effective_target_fps":60,
                "target_bitrate_kbps":20000,
                "preferred_codec":"hevc",
                ${if (issue != null) "\"limiting_factor\":\"$issue\"," else ""}
                "profile_state":{"state":"stable","label":"Quality",
                    "current_profile":{"display_mode":"1920x1080x60","target_fps":60}}
            }""",
        ),
    )!!

    private fun statusLine(limitingLine: String, shortfallMbps: Int): String =
        statusLine(summary(limitingLine), shortfallMbps)

    private fun statusLine(summary: NovaLaunchProfileSummary, shortfallMbps: Int): String {
        rule.setPanelContent {
            NovaGameDetailContentUnderTest(
                uiState = uiState,
                optimizationState = NovaGameDetailOptimizationState(profileSummary = summary),
                playSetupBitrateShortfallMbps = shortfallMbps,
            )
        }
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS * 2)
        rule.waitForIdle()
        return rule.onAllNodes(hasAnyAncestor(hasTestTag("nova-game-detail-status")), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .joinToString(" ") { it.text }
            .replace('\n', ' ')
    }

    @Test
    fun theHostsOwnLimitStaysWhenTheBitrateVerdictFiresAndComesFirst() {
        val line = statusLine(limitingLine = "Host held it to 60 FPS", shortfallMbps = 469)
        val host = line.indexOf("HOST HELD IT TO 60 FPS")
        val bitrate = line.indexOf("LIMITED BY BITRATE")
        assertTrue("the host's own limit is said: $line", host >= 0)
        assertTrue("and the bitrate's: $line", bitrate >= 0)
        assertTrue("the host's first: $line", host < bitrate)
    }

    @Test
    fun theHostsReasonAndTheBitrateSayLimitedByOnce() {
        val summary = hostSummary("network")
        assertEquals("the builder's own line", "Limited by: Network", summary.limitingLine)
        val line = statusLine(summary, shortfallMbps = 469)
        assertTrue("both, joined: $line", line.contains("LIMITED BY NETWORK AND BITRATE"))
        assertEquals("said once: $line", 1, Regex("LIMITED BY").findAll(line).count())
    }

    @Test
    fun eitherAloneKeepsItsOwnLine() {
        val host = statusLine(hostSummary("network"), shortfallMbps = 0)
        assertTrue(host, host.contains("LIMITED BY: NETWORK"))
        assertFalse(host, host.contains("BITRATE"))
    }

    @Test
    fun theBitrateAloneSaysSo() {
        val bitrate = statusLine(hostSummary(null), shortfallMbps = 469)
        assertTrue(bitrate, bitrate.contains("LIMITED BY BITRATE"))
        assertEquals(bitrate, 1, Regex("LIMITED BY").findAll(bitrate).count())
    }
}

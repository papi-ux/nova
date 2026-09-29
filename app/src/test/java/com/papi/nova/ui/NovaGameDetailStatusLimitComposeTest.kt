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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The game page's status line says what holds the launch back. When PyroWave's bitrate verdict
 * fired it took the place of the host's own limiting line, so a limit the host reported went
 * unsaid (#10). Both are said now, the host's first, through the page as the activity composes it.
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

    private fun statusLine(limitingLine: String, shortfallMbps: Int): String {
        rule.setPanelContent {
            NovaGameDetailContentUnderTest(
                uiState = uiState,
                optimizationState = NovaGameDetailOptimizationState(profileSummary = summary(limitingLine)),
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
}

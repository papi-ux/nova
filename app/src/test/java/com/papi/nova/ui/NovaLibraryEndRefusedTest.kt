package com.papi.nova.ui

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.advance
import com.papi.nova.ui.panel.setPanelContent
import com.papi.nova.utils.ServerHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A refused End keeps the host's reason, and offers Try Again only where asking again can work
 * (XR3). A session another device started can never be ended from this one, and one whose details
 * Nova no longer holds cannot be asked about at all: both say why and lose End, and Resume takes
 * the focus End held, so the strip keeps a ring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp")
class NovaLibraryEndRefusedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val session = NovaLibraryActiveSessionUiState(24, "active", "Active Game", "Retroid Pocket", true, 0, false, false, 1920, 1080, 60f)
    private fun model() = NovaLibraryUiStateMapper.build(
        listOf(PolarisGame(id = "active", name = "Active Game", source = "steam", launcherSource = "steam")), "", NovaLibraryFilterState(), activeSession = session,
    )

    @Test
    fun aRefusalAskingAgainCannotFixKeepsItsReasonAndLosesEnd() {
        val line = context.getString(R.string.nova_library_end_started_elsewhere)
        val refused = NovaLibraryUiStateMapper.withEndStatus(
            model(), session, NovaLibraryEndStatus.Failed(24, line, canRetry = false), "Try Again",
        )
        assertEquals(line, refused.hero.eyebrow)
        assertNull("no Try Again where it cannot work", refused.hero.secondaryActionLabel)
        assertNull(refused.hero.secondaryAction)
        assertEquals("Resume stays", model().hero.actionLabel, refused.hero.actionLabel)
    }

    @Test
    fun theHostsOwnWordsAreSaidAndOnlyAnotherDevicesSessionLosesTryAgain() {
        val fallback = context.getString(R.string.nova_library_end_failed)
        val busy = novaLibraryEndRefused(24, ServerHelper.QuitRefusal("The host is busy"), fallback)
        assertEquals("The host is busy", busy.line)
        assertTrue(busy.canRetry)

        assertEquals("no words from the host, so Nova's own", fallback, novaLibraryEndRefused(24, ServerHelper.QuitRefusal(" "), fallback).line)

        val elsewhere = novaLibraryEndRefused(
            24,
            ServerHelper.QuitRefusal(context.getString(R.string.nova_library_end_started_elsewhere), startedElsewhere = true),
            fallback,
        )
        assertFalse(elsewhere.canRetry)
        assertEquals(context.getString(R.string.nova_library_end_started_elsewhere), elsewhere.line)
    }

    @Test
    fun aSessionAnotherDeviceStartedComesBackAsStartedElsewhereInPlainWords() {
        val http = mock(NvHTTP::class.java)
        `when`(http.getServerInfo(true)).thenReturn("<root/>")
        `when`(http.getCurrentGameOwned("<root/>")).thenReturn(false)
        val activity = rule.activity
        var refusal: ServerHelper.QuitRefusal? = null
        var answered = false

        ServerHelper.doQuit(activity, http, "Control") {
            refusal = it
            answered = true
        }
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!answered && System.nanoTime() < deadline) {
            Thread.sleep(10)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

        assertNotNull(refusal)
        assertTrue(refusal!!.startedElsewhere)
        assertEquals(context.getString(R.string.nova_library_end_started_elsewhere), refusal!!.reason)
    }

    private var liveHero by mutableStateOf(
        NovaLibraryHeroState(
            game = null,
            title = "Control Ultimate Edition",
            subtitle = "Running on pc-papi",
            caption = "",
            eyebrow = "Resume your stream",
            actionLabel = "Resume Stream",
            badges = emptyList(),
            reason = NovaLibraryHeroReason.ACTIVE_SESSION,
            primaryAction = NovaLibraryHeroPrimaryAction.RESUME,
            supportingLine = "",
            artworkFallbackTitle = "Control",
            artworkFallbackSubtitle = "",
            secondaryActionLabel = "End Session",
            secondaryAction = NovaLibraryHeroSecondaryAction.END_SESSION,
        ),
    )

    @Test
    fun afterARefusalWithNoTryAgainResumeHoldsTheStripsFocus() {
        val keys = rule.setPanelContent {
            Row(Modifier.fillMaxWidth().height(60.dp)) {
                NovaLibraryStripContinue(
                    hero = liveHero,
                    apiClient = PolarisApiClient(context, ""),
                    fit = NovaTopBarFit(continueTitleLines = 2),
                    onPrimaryAction = {},
                    onSecondaryAction = {},
                )
            }
        }
        rule.onNodeWithContentDescription("End Session").requestFocus()
        rule.mainClock.autoAdvance = false
        keys.press(NovaTestKeys.A)
        rule.advance(50)
        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.A)
        rule.advance(50)
        rule.onNodeWithText(context.getString(R.string.nova_library_ending_session)).assertExists()

        val line = context.getString(R.string.nova_library_end_started_elsewhere)
        liveHero = liveHero.copy(
            endStatus = NovaLibraryEndStatus.Failed(7, line, canRetry = false),
            eyebrow = line,
            secondaryActionLabel = null,
            secondaryAction = null,
        )
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        rule.onNodeWithTag(NOVA_LIBRARY_END_FAILED_TAG).assertExists()
        rule.onNodeWithContentDescription(context.getString(R.string.nova_panel_try_again)).assertDoesNotExist()
        rule.onNodeWithContentDescription("End Session").assertDoesNotExist()
        rule.onNodeWithContentDescription("Resume Stream").assertIsFocused()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

package com.papi.nova.ui

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.papi.nova.utils.ServerHelper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHapticsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    @After
    fun clearPreference() {
        prefs.edit().remove("nova_haptic_feedback").commit()
    }

    @Test
    fun toggleOffDoesNotAskTheViewForFeedback() {
        prefs.edit().putBoolean("nova_haptic_feedback", false).commit()
        val view = RecordingView(context)

        NovaHaptics.perform(view, HapticFeedbackConstants.CONFIRM)

        assertTrue(view.requests.isEmpty())
        assertTrue(view.flaggedRequests.isEmpty())
    }

    @Test
    fun toggleOnCallsTheViewWithoutFlagsEvenWhenThePlatformVetoesIt() {
        prefs.edit().putBoolean("nova_haptic_feedback", true).commit()
        val view = RecordingView(context)

        NovaHaptics.perform(view, HapticFeedbackConstants.CONFIRM)

        assertEquals(listOf(HapticFeedbackConstants.CONFIRM), view.requests)
        assertTrue("no retry with flags that bypass Android's veto", view.flaggedRequests.isEmpty())
    }

    @Test
    fun missingPreferenceKeepsTheXmlDefaultOn() {
        val view = RecordingView(context)

        NovaHaptics.perform(view, HapticFeedbackConstants.VIRTUAL_KEY)

        assertEquals(listOf(HapticFeedbackConstants.VIRTUAL_KEY), view.requests)
    }

    @Test
    fun pairingAndConnectionConfirmationsHonorTheToggle() {
        val view = RecordingView(context)
        prefs.edit().putBoolean("nova_haptic_feedback", false).commit()

        NovaConfirmation.haptic(view)
        assertTrue(view.requests.isEmpty())

        prefs.edit().putBoolean("nova_haptic_feedback", true).commit()
        NovaConfirmation.haptic(view)
        assertEquals(listOf(HapticFeedbackConstants.LONG_PRESS), view.requests)
    }

    // RP6, 2026-10-30: papi felt CONFIRM "but barely". Android plays LONG_PRESS as its heavy click
    // and CONFIRM as the ordinary click (#386). One request, with no flags that pass Android's veto.
    @Test
    fun aConfirmationIsOneClearPulseThroughTheSystemGate() {
        val view = RecordingView(context)

        NovaConfirmation.haptic(view)

        assertEquals(listOf(HapticFeedbackConstants.LONG_PRESS), view.requests)
        assertTrue(view.flaggedRequests.isEmpty())
    }

    // CONFIRM came in Android 11, so an older device used to feel nothing at all.
    @Test
    @Config(sdk = [25])
    fun aConfirmationIsFeltBeforeAndroid11Too() {
        val view = RecordingView(context)

        NovaConfirmation.haptic(view)

        assertEquals(listOf(HapticFeedbackConstants.LONG_PRESS), view.requests)
    }

    // The small haptic language of #421: three different feels, not three strengths of one.
    @Test
    fun everyCueHasItsOwnFeel() {
        val sdk = Build.VERSION_CODES.TIRAMISU
        assertEquals(NovaHapticFeel.Feedback(HapticFeedbackConstants.LONG_PRESS), novaHapticFeel(NovaHapticCue.CONNECTED, sdk))
        assertEquals(NovaHapticFeel.Feedback(HapticFeedbackConstants.REJECT), novaHapticFeel(NovaHapticCue.REFUSED, sdk))
        val end = novaHapticFeel(NovaHapticCue.END_CONFIRMED, sdk) as NovaHapticFeel.Pulses
        assertEquals("one long pulse, from no delay", 2, end.timingsMs.size)
        assertEquals(0L, end.timingsMs[0])
        assertTrue("long, against a click of a few ms", end.timingsMs[1] >= 200L)
        assertTrue("soft, well under full strength", end.amplitude in 1..127)
        assertEquals(3, NovaHapticCue.entries.map { novaHapticFeel(it, sdk) }.toSet().size)
    }

    @Test
    fun beforeAndroid11ARefusalIsStillADouble() {
        val refused = novaHapticFeel(NovaHapticCue.REFUSED, Build.VERSION_CODES.Q) as NovaHapticFeel.Pulses
        assertEquals("off, on, off, on", 4, refused.timingsMs.size)
        assertTrue("short", refused.timingsMs.sum() < 200L)
        assertEquals(NovaHapticFeel.Feedback(HapticFeedbackConstants.LONG_PRESS), novaHapticFeel(NovaHapticCue.CONNECTED, Build.VERSION_CODES.Q))
    }

    @Test
    fun aRefusalGoesThroughTheViewsSystemFeedback() {
        val view = RecordingView(context)
        NovaHaptics.play(context, view, NovaHapticCue.REFUSED)
        assertEquals(listOf(HapticFeedbackConstants.REJECT), view.requests)
        assertTrue(view.flaggedRequests.isEmpty())
    }

    @Test
    fun aConfirmedEndNeedsNoWindowAndVibratesThroughTheTouchUsage() {
        NovaHaptics.play(context, null, NovaHapticCue.END_CONFIRMED)
        val vibrator = shadowOf(context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        assertTrue("the End pulse plays with its stream's window gone", vibrator.isVibrating)
        assertEquals(android.os.VibrationAttributes.USAGE_TOUCH, (vibrator.vibrationAttributesFromLastVibration as android.os.VibrationAttributes).usage)
    }

    @Test
    fun aConfirmedEndHonorsTheToggleAndTheSystemSetting() {
        val vibrator = shadowOf(context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        prefs.edit().putBoolean("nova_haptic_feedback", false).commit()
        NovaHaptics.play(context, null, NovaHapticCue.END_CONFIRMED)
        assertFalse("Nova's own toggle", vibrator.isVibrating)

        prefs.edit().putBoolean("nova_haptic_feedback", true).commit()
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        NovaHaptics.play(context, null, NovaHapticCue.END_CONFIRMED)
        assertFalse("Android's touch feedback setting", vibrator.isVibrating)
    }

    @Test
    fun aQuitFeelsLikeWhatTheHostSaid() {
        assertEquals(NovaHapticCue.END_CONFIRMED, ServerHelper.quitCue(null))
        assertEquals(NovaHapticCue.REFUSED, ServerHelper.quitCue(ServerHelper.QuitRefusal("no")))
        assertEquals(NovaHapticCue.REFUSED, ServerHelper.quitCue(ServerHelper.QuitRefusal("elsewhere", startedElsewhere = true)))
        assertNull("still closing is neither yet", ServerHelper.quitCue(ServerHelper.QuitRefusal("closing", stillClosing = true)))
        assertNotEquals(ServerHelper.quitCue(null), ServerHelper.quitCue(ServerHelper.QuitRefusal("no")))
    }

    @Test
    fun aRefusalOnAReplacementStreamStaysQuiet() {
        val game = java.io.File("src/main/java/com/papi/nova/Game.kt").readText()
        val feel = game.substringAfter("private fun feelStreamRefused() {").substringBefore("private fun finishSecondScreen()")
        assertTrue(feel, feel.contains("if (!getIntent().getBooleanExtra(EXTRA_RECONNECT_REQUESTED, false))"))
        val reconnect = game.substringAfter("private fun reconnectAfterStreamError(").substringBefore("private fun endStreamAfterUnreachableHost(")
        assertFalse("the resilience path says nothing by touch", reconnect.contains("NovaHaptics") || reconnect.contains("feelStreamRefused"))
        assertEquals("the launch issue page and the terminated dialog", 2, Regex("\\nfeelStreamRefused\\(\\)").findAll(game).count())
    }

    @Test
    fun composeReadsTheToggleOnEveryRequestAndPreservesTheFeedbackType() {
        val delegate = RecordingHapticFeedback()
        val haptics = NovaHaptics.forCompose(context, delegate)
        prefs.edit().putBoolean("nova_haptic_feedback", false).commit()

        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        assertTrue(delegate.requests.isEmpty())

        prefs.edit().putBoolean("nova_haptic_feedback", true).commit()
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        assertEquals(
            listOf(HapticFeedbackType.Confirm, HapticFeedbackType.SegmentFrequentTick, HapticFeedbackType.Reject),
            delegate.requests,
        )

        prefs.edit().putBoolean("nova_haptic_feedback", false).commit()
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        assertEquals(3, delegate.requests.size)
    }

    @Test
    fun composeAlsoKeepsTheDefaultOn() {
        val delegate = RecordingHapticFeedback()

        NovaHaptics.forCompose(context, delegate).performHapticFeedback(HapticFeedbackType.Confirm)

        assertEquals(listOf(HapticFeedbackType.Confirm), delegate.requests)
    }

    private class RecordingView(context: Context) : View(context) {
        val requests = mutableListOf<Int>()
        val flaggedRequests = mutableListOf<Pair<Int, Int>>()

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            requests += feedbackConstant
            return false // The platform can refuse; Nova must not try to bypass it.
        }

        override fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean {
            flaggedRequests += feedbackConstant to flags
            return false
        }
    }

    private class RecordingHapticFeedback : HapticFeedback {
        val requests = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            requests += hapticFeedbackType
        }
    }
}

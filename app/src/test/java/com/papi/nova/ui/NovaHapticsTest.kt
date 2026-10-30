package com.papi.nova.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

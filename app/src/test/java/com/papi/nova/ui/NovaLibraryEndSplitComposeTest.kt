package com.papi.nova.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.advance
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End Session on the library's stage splits in its own slot (R3): one A arms it with Stay focused
 * and the rest of the hero's actions stepping aside, mashing A never ends the session, and End
 * after the guard ends it once, with no second confirm after it.
 */
@RunWith(RobolectricTestRunner::class)
// A handheld's landscape window, so the stage lays its hero out above the rail as on the RP6.
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaLibraryEndSplitComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var ends = 0
    private var resumes = 0

    private fun stage(): NovaTestKeys {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return rule.setPanelContent {
            Box(Modifier.fillMaxSize()) {
                val session = NovaLibraryActiveSessionUiState(24, "active", "Portal", "Test", true, 0, false, false, 1920, 1080, 60f)
                val hero = NovaLibraryUiStateMapper.build(emptyList(), "", NovaLibraryFilterState(), activeSession = session).hero
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                    NovaLibraryStripContinue(hero, PolarisApiClient(context, ""), NovaTopBarFit(),
                        onPrimaryAction = { resumes++ }, onSecondaryAction = { ends++ })
                }
            }
        }
    }

    @Test
    fun oneAArmsWithStayFocusedWhileTheOtherActionsStepAside() {
        val keys = stage()
        rule.onNodeWithText("End Session").requestFocus()
        rule.mainClock.autoAdvance = false

        keys.press(NovaTestKeys.A)
        rule.advance(50)

        rule.onNodeWithText("Stay").assertIsFocused()
        rule.onNodeWithContentDescription("Resume Stream").assertDoesNotExist()

        keys.press(NovaTestKeys.A)
        // Past the split's 160ms motion, so the pair has left the tree.
        rule.advance(NovaPanelMetrics.SplitMillis * 2L)

        assertEquals("A on Stay disarms; nothing ends", 0, ends)
        rule.onNodeWithContentDescription("Resume Stream").assertExists()
        rule.onNodeWithText("Stay").assertDoesNotExist()
    }

    @Test
    fun endIgnoresTheGuardWindowThenEndsOnceWithoutAskingAgain() {
        val keys = stage()
        rule.onNodeWithText("End Session").requestFocus()
        rule.mainClock.autoAdvance = false
        keys.press(NovaTestKeys.A)
        rule.advance(50)
        rule.onNodeWithText("Stay").assertIsFocused()

        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.A)
        assertEquals("a press inside the guard after arming ends nothing", 0, ends)

        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.A)
        rule.advance(50)

        assertEquals(1, ends)
        assertEquals(0, resumes)
    }

    @Test
    fun aTapArmsItToo() {
        stage()

        rule.onNodeWithText("End Session").performClick()

        rule.onNodeWithText("Stay").assertExists()
        rule.onNodeWithContentDescription("Resume Stream").assertDoesNotExist()
        assertEquals(0, ends)
    }
}

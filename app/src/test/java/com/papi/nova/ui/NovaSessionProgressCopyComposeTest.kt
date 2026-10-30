package com.papi.nova.ui

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.R
import com.papi.nova.ui.panel.advance
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stage the overlay has no words for says "Working on it" once, not as title and subtitle (#385). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSessionProgressCopyComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val working get() = rule.activity.getString(R.string.nova_stream_progress_working)

    @Test fun anUnknownStageSaysItsFallbackOnce() {
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(NovaSessionProgressUiState.from("a_stage_with_no_words", ""))
        }
        rule.onAllNodesWithText(working).assertCountEquals(1)
    }

    /** Game opens the overlay on this token, the first thing a stream ever shows (#422). */
    @Test fun theFirstStageOfAStreamIsContactingTheHost() {
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(
                NovaSessionProgressUiState.from("conn_establishing", rule.activity.getString(R.string.conn_establishing_msg)),
            )
        }
        rule.onNodeWithText("Connecting to host...").assertIsDisplayed()
        rule.onAllNodesWithText(working).assertCountEquals(0)
    }

    /** A host's heartbeat can carry a state with no words, such as paused, on the way back in (#422). */
    @Test fun aStateWithNoWordsDoesNotReplaceTheStageOnScreen() {
        // Gives the window the lifecycle owner the overlay composes against, as Game's own content does.
        rule.runOnUiThread { rule.activity.setContentView(FrameLayout(rule.activity)) }
        val overlay = SessionProgressOverlay(rule.activity)
        overlay.show()
        overlay.updateState("rtsp")
        rule.waitForIdle()
        overlay.updateState("paused", "")
        rule.waitForIdle()

        rule.onNodeWithText("Opening stream session...").assertIsDisplayed()
        rule.onAllNodesWithText(working).assertCountEquals(0)
    }

    @Test fun theStepsShowWhereTheStartIsAndTalkBackSaysIt() {
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(NovaSessionProgressUiState.from("game_launching"))
        }
        rule.onNodeWithText("✓ Contacting host", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("● Starting the game", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("○ Waiting for video", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("○ Video ready", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Step 2 of 4: Starting the game").assertExists()
    }

    @Test fun nothingIsLeftUnderWayOnceVideoIsReady() {
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(NovaSessionProgressUiState.from("input_ready"))
        }
        rule.onNodeWithText("✓ Video ready", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("●", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    /** A desktop stream never ran a compositor; only the stage that is true is named (#422). */
    @Test fun noStageOfTheTableIsTickedOffThatTheSessionMayNeverHaveRun() {
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(NovaSessionProgressUiState.from("video"))
        }
        rule.onAllNodesWithText("Starting compositor...", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aSpaceFollowsItsOwnStagesAndNotTheseSteps() {
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(NovaSessionProgressUiState.fromSpace("space_starting")!!)
        }
        rule.onAllNodesWithText("Contacting host", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun theElapsedLineCountsFromWhenTheStartBegan() {
        var now = 13_000L
        rule.mainClock.autoAdvance = false
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(
                NovaSessionProgressUiState.from("connecting"),
                startedAtMs = 1_000L,
                now = { now },
            )
        }
        rule.frames(1)
        rule.onNodeWithText("12 s elapsed").assertExists()

        now = 15_000L
        rule.advance(2_000L)
        rule.onNodeWithText("14 s elapsed").assertExists()
    }

    @Test fun noElapsedLineIsDrawnWithoutAStartTime() {
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(NovaSessionProgressUiState.from("connecting"))
        }
        rule.onAllNodesWithText("elapsed", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }
}

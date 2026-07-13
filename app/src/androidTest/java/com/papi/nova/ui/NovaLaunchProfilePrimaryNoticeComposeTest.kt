package com.papi.nova.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.compose.NovaActionButton
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class NovaLaunchProfilePrimaryNoticeComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactNoticeIsDpadReachableExpandableAndHorizontallyContained() {
        val detail = "Last stream: 115.6/120 FPS. 1% low: 110.8 FPS. Bad pacing: 0%."
        val recommendation =
            "Next launch keeps the verified profile while preserving every compact cockpit control."
        val summary = NovaLaunchProfileSummary(
            primaryLaunchLabel = "Launch 120 FPS",
            requestedLine = "Requested: High FPS stream / 120 FPS",
            selectedLine = "Selected: High FPS stream / 120 FPS",
            reasonLine = "Reason: diagnostic evidence needs attention",
            limitingLine = "Limited by: Host render telemetry with a deliberately long compact label",
            noticeDetail = detail,
            noticeRecommendation = recommendation,
            noticeTone = NovaLaunchProfileNoticeTone.WARNING,
            noticeLabel = "Heads up",
            freshnessLine = "",
            historyLines = emptyList(),
            showRetryHighFps = false,
            retryHighFpsLabel = "Try High FPS once"
        )

        val playFocusRequester = FocusRequester()
        composeRule.setContent {
            NovaComposeTheme {
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .testTag("compact-launch-root")
                ) {
                    LaunchProfilePrimaryNotice(summary)
                    NovaActionButton(
                        text = "Play",
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(playFocusRequester)
                    )
                }
            }
        }

        composeRule.runOnIdle {
            playFocusRequester.requestFocus()
        }
        composeRule.onNodeWithText("Play").performKeyInput {
            pressKey(Key.DirectionUp)
        }
        composeRule.onNodeWithText("More details").assertIsDisplayed().performKeyInput {
            pressKey(Key.DirectionCenter)
        }

        composeRule.onNodeWithText("Hide details").assertIsDisplayed()
        composeRule.onNodeWithText(detail).assertIsDisplayed()
        composeRule.onNodeWithText(recommendation).assertIsDisplayed()

        val rootBounds = composeRule.onNodeWithTag("compact-launch-root").fetchSemanticsNode().boundsInRoot
        val detailBounds = composeRule.onNodeWithText(detail).fetchSemanticsNode().boundsInRoot
        val recommendationBounds = composeRule.onNodeWithText(recommendation).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "detail must stay within compact root",
            detailBounds.left >= rootBounds.left && detailBounds.right <= rootBounds.right
        )
        assertTrue(
            "recommendation must stay within compact root",
            recommendationBounds.left >= rootBounds.left && recommendationBounds.right <= rootBounds.right
        )
    }
}

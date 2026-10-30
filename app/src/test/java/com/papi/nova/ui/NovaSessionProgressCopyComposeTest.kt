package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.papi.nova.R
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

    @Test fun anUnknownStageSaysItsFallbackOnce() {
        val working = rule.activity.getString(R.string.nova_stream_progress_working)
        rule.setPanelContent {
            NovaSessionProgressOverlayContent(NovaSessionProgressUiState.from("a_stage_with_no_words", ""))
        }
        rule.onAllNodesWithText(working).assertCountEquals(1)
    }
}

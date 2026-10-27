package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp")
class NovaHudLiveTuningLineComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun theLineShowsInPerformanceAndDebugOnlyAndLeavesWithItsState() {
        var mode by mutableStateOf(NovaHudMode.PERFORMANCE)
        var line by mutableStateOf<NovaLiveTuningLine?>(NovaLiveTuningLine(1, NovaLiveTuningLine.Kind.EASED, 29739, 0))
        rule.setContent { NovaComposeTheme { NovaStreamHudContent(NovaHudUiState.preview(mode).copy(liveTuningLine = line)) } }
        rule.onNodeWithText("Eased to 30 Mbps").assertIsDisplayed()
        mode = NovaHudMode.DEBUG
        rule.onNodeWithText("Eased to 30 Mbps").assertIsDisplayed()
        for (quiet in listOf(NovaHudMode.MINIMAL, NovaHudMode.SLIM)) {
            mode = quiet
            rule.onNodeWithText("Eased to 30 Mbps").assertDoesNotExist()
        }
        mode = NovaHudMode.PERFORMANCE
        line = NovaLiveTuningLine(2, NovaLiveTuningLine.Kind.FULL, 34988, 40_000)
        rule.onNodeWithText("Back to full quality").assertIsDisplayed()
        line = null
        rule.onNodeWithText("Back to full quality").assertDoesNotExist()
    }
}

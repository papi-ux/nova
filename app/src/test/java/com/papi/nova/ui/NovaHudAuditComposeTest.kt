package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp")
class NovaHudAuditComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun draw() = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
            NovaComposeTheme { NovaStreamHudContent(NovaHudUiState.preview(NovaHudMode.DEBUG), opacityScale = 0f) }
        }
    }
    @Test fun sampledMinimumIsNamedHonestly() {
        draw(); rule.onNodeWithText("WINDOW MIN").assertIsDisplayed()
        rule.onNodeWithText("1% LOW").assertDoesNotExist()
    }
    @Test fun missingFramesAreNamedAndKeptWithDelivery() {
        draw(); rule.onNodeWithText("FRAME LOSS").assertIsDisplayed()
        rule.onNodeWithText("DROPS").assertDoesNotExist()
    }
    @Test fun metricsExposeLabelValueAndUnitTogether() {
        draw(); rule.onNodeWithContentDescription("Rendered frame rate: 60 frames per second").assertExists()
    }
    @Test fun debugFactsRemainReadableAtLargeFontScale() {
        draw()
        val result = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("CODEC", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        assertTrue(result.single().layoutInput.style.fontSize.value >= 11f)
        assertFalse(result.single().hasVisualOverflow)
    }
    @Test fun textKeepsAReadabilityBackingAtZeroPanelOpacity() {
        draw()
        // The backing is a distinct surface; the adjustable glass can still become clear.
        rule.onNodeWithTag("nova_hud_readability_backing", useUnmergedTree = true).assertExists()
    }
}

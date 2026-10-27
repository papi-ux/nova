package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.compose.NovaFormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp")
class NovaHudQualityChipComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // The display chip comes from the model, so the label drawn here is the one the HUD paints.
    private val both = listOf(NovaHudQualityChip("HDR10", "Host stream: HDR10")) +
        NovaHudQualityChips.from(null, displayModeHz = 120f)

    @Test fun chipsShowInPerformanceAndDebugOnlyAndLeaveWithTheirFacts() {
        var mode by mutableStateOf(NovaHudMode.PERFORMANCE)
        var chips by mutableStateOf(both)
        rule.setContent { NovaComposeTheme { NovaStreamHudContent(NovaHudUiState.preview(mode).copy(qualityChips = chips)) } }
        for (shown in listOf(NovaHudMode.PERFORMANCE, NovaHudMode.DEBUG)) {
            mode = shown
            rule.onNodeWithContentDescription("Host stream: HDR10").assertIsDisplayed()
            rule.onNodeWithContentDescription("Display mode: 120 hertz").assertIsDisplayed()
        }
        // The one-glance layouts gain nothing over the game.
        for (quiet in listOf(NovaHudMode.MINIMAL, NovaHudMode.SLIM)) {
            mode = quiet
            rule.onAllNodesWithTag(NOVA_HUD_QUALITY_CHIP_TAG, useUnmergedTree = true).assertCountEquals(0)
        }
        mode = NovaHudMode.PERFORMANCE
        chips = emptyList()
        rule.onAllNodesWithTag(NOVA_HUD_QUALITY_CHIP_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    // A television's type floor, large accessibility text and a clear panel at once: the longer
    // chip stays one whole line inside the HUD in both layouts that carry chips. Legacy Robolectric
    // text metrics are not glyph widths, so the physical fit is still the device pass's to see.
    @Test fun bothChipsStayWholeOnTelevisionAtLargeTextWithAClearPanel() {
        var mode by mutableStateOf(NovaHudMode.PERFORMANCE)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                NovaComposeTheme {
                    CompositionLocalProvider(LocalNovaFormFactor provides NovaFormFactor.Television) {
                        NovaStreamHudContent(NovaHudUiState.preview(mode).copy(qualityChips = both),
                            Modifier.testTag("hud"), opacityScale = 0f)
                    }
                }
            }
        }
        val label = both.last().label
        for (shown in listOf(NovaHudMode.PERFORMANCE, NovaHudMode.DEBUG)) {
            mode = shown
            rule.onNodeWithText("HDR10", useUnmergedTree = true).assertIsDisplayed()
            val chip = rule.onNodeWithText(label, useUnmergedTree = true)
            val hud = rule.onNodeWithTag("hud").getUnclippedBoundsInRoot()
            val bounds = chip.getUnclippedBoundsInRoot()
            assertTrue("$shown: $label inside the HUD, $bounds in $hud",
                bounds.left >= hud.left && bounds.right <= hud.right && bounds.top >= hud.top && bounds.bottom <= hud.bottom)
            val layout = mutableListOf<TextLayoutResult>()
            chip.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertTrue("$shown: the television type floor", layout.single().layoutInput.style.fontSize.value >= 14f)
            assertEquals("$shown: the whole label on its one line", label.length, layout.single().getLineEnd(0, visibleEnd = true))
            assertFalse("$shown: nothing cut short", layout.single().isLineEllipsized(0))
        }
    }
}

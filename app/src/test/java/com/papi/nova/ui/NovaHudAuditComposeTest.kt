package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
        draw(); rule.onNodeWithText("FRAME LOSS\nLAST 1s").assertIsDisplayed()
        rule.onNodeWithText("DROPS").assertDoesNotExist()
    }
    @Test fun metricsExposeLabelValueAndUnitTogether() {
        draw(); rule.onAllNodesWithContentDescription("Rendered frame rate: 60 frames per second").assertCountEquals(2)
    }
    @Test fun debugFactsRemainReadableAtLargeFontScale() {
        draw()
        val result = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("CODEC", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        assertTrue(result.single().layoutInput.style.fontSize.value >= 11f)
        // Legacy Robolectric font metrics do not model glyph ink accurately. Check the
        // full logical line and ellipsis, then leave physical readability to the device pass.
        assertEquals(5, result.single().getLineEnd(0, visibleEnd = true))
        assertFalse(result.single().isLineEllipsized(0))
    }
    @Test fun textKeepsAReadabilityBackingAtZeroPanelOpacity() {
        draw()
        // The backing is a distinct surface; the adjustable glass can still become clear.
        assertTrue(rule.onAllNodesWithTag("nova_hud_readability_backing", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
    @Test fun hudActionsAreAccessibleWithoutAnnouncingEveryTick() {
        var resets = 0
        rule.setContent {
            NovaComposeTheme {
                NovaStreamHudContent(NovaHudUiState.preview(NovaHudMode.MINIMAL),
                    accessibilityActions = listOf(CustomAccessibilityAction("Reset HUD position") { resets++; true }))
            }
        }
        val hud = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Stream statistics"))
        val reset = hud.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        rule.runOnIdle { assertTrue(reset.action()) }
        assertEquals(1, resets)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
    }
}

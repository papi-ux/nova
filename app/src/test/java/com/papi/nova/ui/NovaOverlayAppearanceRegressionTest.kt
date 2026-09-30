package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.platform.testTag
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
@Config(sdk = [33], qualifiers = "w800dp-h480dp")
class NovaOverlayAppearanceRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun draw(scale: Float = 1f) = rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            NovaComposeTheme {
                NovaStreamHudContent(NovaHudUiState.preview(NovaHudMode.DEBUG),
                    Modifier.testTag("hud"), opacityScale = 0f)
            }
        }
    }

    @Test fun debugDoesNotFillHalfTheHandheldScreen() {
        draw()
        val bounds = rule.onNodeWithTag("hud").getUnclippedBoundsInRoot()
        assertTrue("compact Debug width: $bounds", bounds.width.value <= 340f)
        val host = rule.onNodeWithText("HOST").getUnclippedBoundsInRoot()
        val client = rule.onNodeWithText("CLIENT").getUnclippedBoundsInRoot()
        assertEquals("three columns share one row", host.top.value, client.top.value, 1f)
    }

    @Test fun largerTextKeepsTheThreeColumnsWhenThereIsRoom() {
        draw(1.3f)
        val host = rule.onNodeWithText("HOST").getUnclippedBoundsInRoot()
        val client = rule.onNodeWithText("CLIENT").getUnclippedBoundsInRoot()
        assertEquals(host.top.value, client.top.value, 1f)
    }

    @Test fun textUsesAnOutlineInsteadOfIndividualPaddedBlackBoxes() {
        draw()
        rule.onAllNodesWithTag("nova_hud_readability_backing", useUnmergedTree = true).assertCountEquals(0)
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("CODEC", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("the accessible size is retained", layouts.single().layoutInput.style.fontSize.value >= 11f)
        assertNotNull("clear HUD text still has a contrast outline", layouts.single().layoutInput.style.shadow)
    }
}

package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-port")
class NovaLibraryFooterBrandComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun portraitFooterNamesTheLibraryWithoutClaimingTheStageLayout() {
        rule.setPanelContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                // The real Activity passes compact=isLandscape, including portrait Regular
                // and Compact: the shared footer receives false for both layouts.
                NovaLibraryCinematicControllerHints(
                    hints = listOf(NovaControllerHint("A", "Select"), NovaControllerHint("B", "Back")),
                    semanticsDescription = "A Select · B Back", compact = false)
            }
        }
        rule.onNodeWithText("NOVA LIBRARY", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("POLARIS CINEMATIC STAGE", useUnmergedTree = true).assertDoesNotExist()
    }
}

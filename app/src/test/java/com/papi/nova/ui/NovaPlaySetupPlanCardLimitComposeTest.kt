package com.papi.nova.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A plan card that names what would hold the launch back still names the mode it runs in: the
 * codec preview's "Limited by bitrate" had taken the place of "Private Stream (GPU-native)", so the
 * preview and What Will Happen read as two different plans (in-game smoke #10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaPlaySetupPlanCardLimitComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theLimitSitsBesideTheModeNotInItsPlace() {
        rule.setPanelContent {
            Box(Modifier.width(560.dp)) {
                NovaPlaySetupPlanCard(
                    title = "What Will Happen",
                    value = "Private Stream (GPU-native)",
                    line = "3840×2160 @ 120 FPS · 300 Mbps · PyroWave (client choice)",
                    limit = "Limited by bitrate",
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Private Stream (GPU-native)", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Limited by bitrate", substring = true, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(NOVA_PLAY_SETUP_WARNING_MARK_TAG, useUnmergedTree = true).assertIsDisplayed()
    }
}

package com.papi.nova.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.preference.PreferenceManager
import com.papi.nova.ui.NovaMenuPreferences
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h480dp")
class NovaMenuOpacityPreferenceComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun savedOpacityChangesTheActualInStreamPanelWithoutReopening() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(rule.activity)
        prefs.edit().putInt(NovaMenuPreferences.KEY_OPACITY, 100).commit()
        rule.setContent {
            NovaComposeTheme {
                NovaPanelFrame(NovaEdge.Start, NovaPanelWidth.Standard, true, {}, {},
                    scrim = NovaScrim.Stream, overStream = true) { Text("Command Center") }
            }
        }
        for (percent in listOf(100, 64, 25, 0, 100)) {
            rule.runOnUiThread { prefs.edit().putInt(NovaMenuPreferences.KEY_OPACITY, percent).commit() }
            rule.waitForIdle()
            val fill = rule.onNode(SemanticsMatcher.keyIsDefined(NovaPanelPlacementKey))
                .fetchSemanticsNode().config[NovaPanelPlacementKey].fill
            assertEquals("drawn background at $percent%", percent / 100f, fill.alpha, 0.005f)
        }
    }
}

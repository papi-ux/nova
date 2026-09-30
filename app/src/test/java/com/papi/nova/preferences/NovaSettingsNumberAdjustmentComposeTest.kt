package com.papi.nova.preferences

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real Settings opener, whose unit and save callbacks the common-page test cannot prove. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaSettingsNumberAdjustmentComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val writes = mutableListOf<Pair<String, NovaSettingValue>>()

    private fun show(definition: NovaSettingDefinition): NovaTestKeys {
        val definitions = NovaSettingsDefinitionSet(listOf(NovaSettingCategory("numbers", "Numbers", "")), listOf(definition))
        val keys = rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), "numbers", ""),
                title = "Settings", subtitle = null, headerActions = emptyList(),
                onBack = {}, onOpenLegacy = {}, onSearch = {}, onClearSearch = {}, onCategory = {},
                onResetSetting = {}, onSetting = {},
                onValue = { changed, value, done -> writes += changed.key to value; done() },
            )
        }
        rule.onNodeWithTag("nova-settings-row-${definition.key}").performClick()
        rule.waitForIdle()
        return keys
    }

    @Test fun settingsBitrateExactEntryUsesMbpsAndWritesKbpsOnlyOnSave() {
        val key = PreferenceConfiguration.BITRATE_PREF_STRING
        val keys = show(NovaSettingDefinition(key, "Video bitrate", "", "numbers", NovaSettingType.Slider,
            defaultValue = NovaSettingValue.IntValue(200_000), min = 0, max = 500_000, step = 5000))
        rule.onNodeWithText("Exact value in Mbps").assertExists()
        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)
        rule.onNodeWithText("200").performTextReplacement("215.5")
        assertTrue(writes.isEmpty())
        keys.press(NovaTestKeys.DOWN)
        keys.pressTwiceInOneFrame(NovaTestKeys.CENTER)
        assertEquals(listOf(key to NovaSettingValue.IntValue(215_500)), writes)
    }

    @Test fun textSizeTouchDraftCanBeCancelledWithoutWritingSettings() {
        val keys = show(NovaSettingDefinition("nova_ui_font_scale_percent", "Nova Text Size", "", "numbers", NovaSettingType.Slider,
            defaultValue = NovaSettingValue.IntValue(100), min = 80, max = 130, step = 1, suffix = "%"))
        rule.onNodeWithTag("nova-number-track", useUnmergedTree = true).performTouchInput { click(Offset(width * .8f, centerY)) }
        rule.onNodeWithText("120%").assertExists()
        assertTrue(writes.isEmpty())
        keys.back()
        assertTrue(writes.isEmpty())
        rule.onNodeWithTag("nova-settings-row-nova_ui_font_scale_percent").performClick()
        rule.onNodeWithText("100%").assertExists()
    }
}

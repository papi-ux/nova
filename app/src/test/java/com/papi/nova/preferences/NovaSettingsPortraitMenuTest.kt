package com.papi.nova.preferences

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-port")
class NovaSettingsPortraitMenuTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun hidingNavigationKeepsTheChosenSettingsPaneAndItsEdits() {
        val definitions = NovaSettingsDefinitionSet(
            listOf(NovaSettingCategory("stream", "Stream", ""), NovaSettingCategory("input", "Input", "")),
            listOf(
                NovaSettingDefinition(key = "checkbox_enable_hdr", title = "HDR", summary = "",
                    categoryKey = "stream", type = NovaSettingType.Toggle,
                    defaultValue = NovaSettingValue.BooleanValue(false)),
                NovaSettingDefinition(key = "rumble", title = "Rumble", summary = "",
                    categoryKey = "input", type = NovaSettingType.Toggle,
                    defaultValue = NovaSettingValue.BooleanValue(false)),
            ),
        )
        var selected by mutableStateOf("stream")
        var values by mutableStateOf<Map<String, NovaSettingValue>>(emptyMap())
        rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, values, selected, ""),
                title = "Settings", subtitle = "Test", onBack = {}, onOpenLegacy = {},
                onSearch = {}, onClearSearch = {}, onCategory = { selected = it }, headerActions = emptyList(),
                onResetSetting = {}, onValue = { definition, value, done -> values = values + (definition.key to value); done() },
                onSetting = {},
            )
        }
        rule.onNodeWithText("Menu").assertIsDisplayed().performClick()
        rule.onNodeWithText("Input").performClick()
        rule.onNodeWithText("Hide menu").performClick()
        rule.onNodeWithText("Legacy").assertDoesNotExist()
        rule.onNodeWithText("Rumble").assertIsDisplayed().performClick()
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithText("Input").assertIsDisplayed()
        org.junit.Assert.assertEquals("input", selected)
        org.junit.Assert.assertEquals(NovaSettingValue.BooleanValue(true), values["rumble"])
    }
}

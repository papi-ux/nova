package com.papi.nova.preferences

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import android.view.KeyEvent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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

    @Test fun enlargedTextCanReachEveryCategoryInTheExpandedMenu() {
        val definitions = NovaSettingDefinitions.load(rule.activity)
        var selected by mutableStateOf(definitions.categories.first().key)
        rule.setPanelContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                NovaSettingsContent(
                    state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), selected, ""),
                    title = "Settings", subtitle = "Test", onBack = {}, onOpenLegacy = {},
                    onSearch = {}, onClearSearch = {}, onCategory = { selected = it }, headerActions = emptyList(),
                    onResetSetting = {}, onValue = { _, _, done -> done() }, onSetting = {},
                )
            }
        }
        rule.onNodeWithText("Menu").performClick()
        val last = definitions.categories.last()
        rule.onNodeWithTag("nova-settings-category-${last.key}").performScrollTo().assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(last.key, selected)
        rule.onNodeWithText("Hide menu").assertIsDisplayed().performClick()
        rule.onNodeWithText("Menu").assertIsDisplayed()
    }

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
        val keys = rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, values, selected, ""),
                title = "Settings", subtitle = "Test", onBack = {}, onOpenLegacy = {},
                onSearch = {}, onClearSearch = {}, onCategory = { selected = it }, headerActions = emptyList(),
                onResetSetting = {}, onValue = { definition, value, done -> values = values + (definition.key to value); done() },
                onSetting = {},
            )
        }
        rule.onNodeWithText("Menu").assertIsDisplayed().performClick()
        rule.onNode(hasText("Input") and hasClickAction()).performClick()
        rule.onNodeWithText("Hide menu").performClick()
        rule.onNodeWithText("Legacy").assertDoesNotExist()
        rule.onNodeWithText("Rumble").assertIsDisplayed().performClick()
        rule.onNodeWithText("Menu").performClick()
        rule.onNode(hasText("Input") and hasClickAction()).assertIsDisplayed()
        org.junit.Assert.assertEquals("input", selected)
        org.junit.Assert.assertEquals(NovaSettingValue.BooleanValue(true), values["rumble"])
        rule.onNodeWithTag("nova-portrait-menu-toggle").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        keys.gatedPress(KeyEvent.KEYCODE_BUTTON_B)
        rule.onNodeWithText("Menu").assertIsDisplayed().assertIsFocused()
        rule.onNodeWithText("Legacy").assertDoesNotExist()
        keys.press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("nova-settings-row-rumble").assertIsFocused()
        org.junit.Assert.assertEquals("input", selected)

    }
}

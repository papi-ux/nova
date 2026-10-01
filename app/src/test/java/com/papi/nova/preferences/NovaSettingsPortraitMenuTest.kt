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
import androidx.compose.ui.test.onNodeWithContentDescription
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

    @Test fun portraitMenuGroupsSearchAndLegacyAndShowsCategoriesBeforeShortcuts() {
        val definitions = NovaSettingDefinitions.load(rule.activity)
        var selected by mutableStateOf(definitions.categories.first().key)
        var legacy = 0
        rule.setPanelContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 0.8f)) {
                NovaSettingsContent(
                    state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), selected, ""),
                    title = "Settings", subtitle = "Test", onBack = {}, onOpenLegacy = { legacy++ },
                    onSearch = {}, onClearSearch = {}, onCategory = { selected = it }, headerActions = emptyList(),
                    onResetSetting = {}, onValue = { _, _, done -> done() }, onSetting = {},
                )
            }
        }
        rule.onNodeWithText("Menu").performClick()
        val search = rule.onNodeWithContentDescription("Search Settings").fetchSemanticsNode().boundsInRoot
        val legacyButton = rule.onNodeWithText("Legacy").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Search and Legacy belong to one navigation row",
            legacyButton.center.y in search.top..search.bottom)
        val navigation = rule.onNodeWithTag("nova-portrait-settings-navigation").fetchSemanticsNode().boundsInRoot
        val first = rule.onNodeWithTag("nova-settings-category-${definitions.categories.first().key}").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("nova-settings-category-${definitions.categories.last().key}").fetchSemanticsNode().boundsInRoot
        val quick = rule.onNodeWithTag("nova-settings-quick-nova_stream_preset").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Categories come before repeated stream shortcuts", last.bottom <= quick.top + 1f)
        org.junit.Assert.assertTrue("All default-size category rows are wholly in the bounded menu",
            first.top >= navigation.top - 1f && last.bottom <= navigation.bottom + 1f)
        rule.onNodeWithText("Legacy").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(1, legacy)
    }

    @Test
    @Config(sdk = [33], qualifiers = "w320dp-h720dp-port")
    fun narrowPortraitSearchAndSavedSetupActionsRemainReachable() {
        val definitions = NovaSettingDefinitions.load(rule.activity)
        var query by mutableStateOf("bitrate")
        var selected by mutableStateOf(definitions.categories.first().key)
        var legacy = 0
        var renames = 0
        var saves = 0
        rule.setPanelContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                NovaSettingsContent(
                    state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), selected, query),
                    title = "Saved setup", subtitle = "Test", onBack = {}, onOpenLegacy = { legacy++ },
                    onSearch = { query = it }, onClearSearch = { query = "" },
                    onCategory = { selected = it; query = "" },
                    headerActions = listOf(NovaSettingsHeaderAction("Rename") { renames++ },
                        NovaSettingsHeaderAction("Save") { saves++ }),
                    onResetSetting = {}, onValue = { _, _, done -> done() }, onSetting = {},
                )
            }
        }
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithText("Clear").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals("", query)
        rule.onNodeWithText("Legacy").assertIsDisplayed().performClick()
        rule.onNodeWithText("Rename").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Save").performScrollTo().assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(1, legacy)
        org.junit.Assert.assertEquals(1, renames)
        org.junit.Assert.assertEquals(1, saves)
        val last = definitions.categories.last()
        rule.onNodeWithTag("nova-settings-category-${last.key}").performScrollTo().assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(last.key, selected)
        rule.onNodeWithText("Hide menu").assertIsDisplayed().performClick()
        rule.onNodeWithText("Menu").assertIsDisplayed()
    }

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

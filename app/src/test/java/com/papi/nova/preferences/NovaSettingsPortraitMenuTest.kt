package com.papi.nova.preferences

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
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

    @Test fun portraitMenuIsAtTheLeftAndKeepsItsControllerStop() {
        val definitions = NovaSettingDefinitions.load(rule.activity)
        var selected by mutableStateOf(definitions.categories.first().key)
        var backs = 0
        val keys = rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), selected, ""),
                title = "Settings", subtitle = "Test", onBack = { backs++ }, onOpenLegacy = {},
                onSearch = {}, onClearSearch = {}, onCategory = { selected = it }, headerActions = emptyList(),
                onResetSetting = {}, onValue = { _, _, done -> done() }, onSetting = {},
            )
        }
        val menu = rule.onNodeWithTag("nova-portrait-menu-toggle")
        val menuBounds = menu.fetchSemanticsNode().boundsInRoot
        val titleBounds = rule.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        val backBounds = rule.onNodeWithText("Back").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("The portrait navigation opens from the left", menuBounds.right <= titleBounds.left)
        org.junit.Assert.assertTrue("Back remains a separate complete control", titleBounds.right <= backBounds.left)
        menu.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        keys.press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("Hide menu").assertIsDisplayed()
        menu.assertIsFocused()
        rule.onNodeWithText("Back").performClick()
        org.junit.Assert.assertEquals(1, backs)
    }

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
        val navigation = rule.onNodeWithTag("nova-portrait-settings-navigation").getUnclippedBoundsInRoot()
        val first = rule.onNodeWithTag("nova-settings-category-${definitions.categories.first().key}").getUnclippedBoundsInRoot()
        val last = rule.onNodeWithTag("nova-settings-category-${definitions.categories.last().key}").getUnclippedBoundsInRoot()
        val categoryLayout = rule.onNodeWithTag("nova-settings-category-${definitions.categories.last().key}").getUnclippedBoundsInRoot()
        val quickLayout = rule.onNodeWithTag("nova-settings-quick-nova_stream_preset").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("Categories come before repeated stream shortcuts: $categoryLayout / $quickLayout",
            categoryLayout.bottom <= quickLayout.top + 1.dp)
        org.junit.Assert.assertTrue("All default-size category rows are wholly in the bounded menu",
            first.top >= navigation.top - 1.dp && last.bottom <= navigation.bottom + 1.dp)
        rule.onNodeWithText("Legacy").assertIsDisplayed().performClick()
        rule.onNodeWithTag("nova-settings-quick-nova_stream_preset").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals(1, legacy)
    }


    @Test fun portraitControllerCanEnterEveryStreamShortcutFromTheSelectedPane() {
        val definitions = NovaSettingDefinitions.load(rule.activity)
        var selected by mutableStateOf(definitions.find("nova_stream_preset")!!.categoryKey)
        val keys = rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), selected, ""),
                title = "Settings", subtitle = "Test", onBack = {}, onOpenLegacy = {},
                onSearch = {}, onClearSearch = {}, onCategory = { selected = it }, headerActions = emptyList(),
                onResetSetting = {}, onValue = { _, _, done -> done() }, onSetting = {},
            )
        }
        rule.onNodeWithText("Menu").performClick()
        val last = rule.onNodeWithTag("nova-settings-category-${definitions.categories.last().key}")
        last.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        keys.press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("nova-settings-row-nova_stream_preset").assertIsFocused()
        keys.press(KeyEvent.KEYCODE_DPAD_UP)
        val state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), selected, "")
        for ((index, shortcut) in state.quickSettings.withIndex()) {
            if (index > 0) keys.press(KeyEvent.KEYCODE_DPAD_DOWN)
            rule.onNodeWithTag("nova-settings-quick-${shortcut.key}").assertIsFocused().assertIsDisplayed()
        }
        keys.press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("nova-settings-row-nova_stream_preset").assertIsFocused()
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
    private fun disabledRows(allDisabled: Boolean): com.papi.nova.ui.panel.NovaTestKeys {
        val definitions = NovaSettingsDefinitionSet(
            listOf(NovaSettingCategory("stream", "Stream", ""), NovaSettingCategory("input", "Input", "")),
            listOf(
                NovaSettingDefinition(key = "disabled", title = "Requires controller", summary = "Enable controller first",
                    categoryKey = "stream", type = NovaSettingType.Toggle, dependencyKey = "controller", defaultValue = NovaSettingValue.BooleanValue(false)),
                NovaSettingDefinition(key = PreferenceConfiguration.FPS_PREF_STRING, title = "Frame rate", summary = "",
                    categoryKey = "stream", type = NovaSettingType.Select, dependencyKey = if (allDisabled) "controller" else null,
                    defaultValue = NovaSettingValue.StringValue("60"), options = listOf(NovaSettingOption("60 FPS", "60"))),
            ),
        )
        val keys = rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, mapOf("controller" to NovaSettingValue.BooleanValue(false)), "stream", ""),
                title = "Settings", subtitle = "Test", onBack = {}, onOpenLegacy = {},
                onSearch = {}, onClearSearch = {}, onCategory = {}, headerActions = emptyList(),
                onResetSetting = {}, onValue = { _, _, done -> done() }, onSetting = {},
            )
        }
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithTag("nova-settings-category-input").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        keys.press(KeyEvent.KEYCODE_DPAD_DOWN)
        return keys
    }

    @Test fun disabledReasonRowRemainsReachableBeforeTheShortcutBoundary() {
        val keys = disabledRows(allDisabled = false)
        rule.onNodeWithTag("nova-settings-row-${PreferenceConfiguration.FPS_PREF_STRING}").assertIsFocused()
        keys.press(KeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("nova-settings-row-disabled").assertIsFocused().assertIsDisplayed()
        keys.press(KeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("nova-settings-quick-${PreferenceConfiguration.FPS_PREF_STRING}").assertIsFocused()
    }

    @Test fun allDisabledPaneStillHasAControllerEntryForItsReasons() {
        val keys = disabledRows(allDisabled = true)
        rule.onNodeWithTag("nova-settings-row-disabled").assertIsFocused().assertIsDisplayed()
        keys.press(KeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("nova-settings-quick-${PreferenceConfiguration.FPS_PREF_STRING}").assertIsFocused()
    }

}

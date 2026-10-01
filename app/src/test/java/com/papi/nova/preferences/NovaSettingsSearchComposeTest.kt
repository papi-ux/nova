package com.papi.nova.preferences

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import com.papi.nova.R
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the production Settings surface, including query-driven pane replacement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaSettingsSearchComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val definitions = NovaSettingsDefinitionSet(
        listOf(NovaSettingCategory("stream", "Stream", ""), NovaSettingCategory("input", "Input", "")),
        listOf(
            NovaSettingDefinition("frame_pacing", "Frame pacing", "", "stream", NovaSettingType.Toggle,
                defaultValue = NovaSettingValue.BooleanValue(false)),
            NovaSettingDefinition("checkbox_enable_rumble", "Controller rumble", "", "input", NovaSettingType.Toggle,
                defaultValue = NovaSettingValue.BooleanValue(false)),
        ),
    )
    private var query by mutableStateOf("")
    private var selected by mutableStateOf("stream")
    private var values by mutableStateOf<Map<String, NovaSettingValue>>(emptyMap())
    private val writes = mutableListOf<String>()
    private var backs = 0

    private fun show(): NovaTestKeys {
        val keys = rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, values, selected, query),
                title = "Settings", subtitle = "", onBack = { backs++ }, onOpenLegacy = {},
                onSearch = { query = it }, onClearSearch = { query = "" }, onCategory = { selected = it },
                headerActions = emptyList(), onResetSetting = {}, onSetting = {},
                onValue = { definition, value, done ->
                    writes += definition.key
                    values = values + (definition.key to value)
                    done()
                },
            )
        }
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS + 32)
        rule.waitForIdle()
        return keys
    }

    private fun search() = rule.onNodeWithContentDescription(rule.activity.getString(R.string.nova_settings_search_hint))
    private val editable = SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)

    @Test fun touchOpensSearchAndKeepsTypingAcrossTheResultsPaneChange() {
        show()
        search().performTouchInput { click() }
        search().assert(editable).assertIsFocused()
        search().performTextInput("frame")
        rule.frames(8)
        search().assert(editable).assertIsFocused().performTextInput(" pacing")
        rule.frames(8)
        assertEquals("frame pacing", query)
        rule.onNodeWithTag("nova-settings-row-frame_pacing").assertIsDisplayed()
        assertEquals("typing does not leave Settings", 0, backs)
    }

    @Test fun controllerCanTypeAFullQueryAndClearItWithoutLeavingSettings() {
        val keys = show()
        search().requestFocus()
        keys.press(NovaTestKeys.CENTER)
        search().assert(editable).performTextInput("rum")
        rule.frames(8)
        search().assertIsFocused().performTextInput("ble")
        rule.frames(8)
        assertEquals("rumble", query)
        rule.onNodeWithTag("nova-settings-row-checkbox_enable_rumble").assertIsDisplayed()
        // The first Back closes editing, the second clears the search, both in the actual screen.
        keys.back()
        keys.back()
        rule.frames(8)
        assertEquals("", query)
        assertEquals("stream", selected)
        assertEquals(0, backs)
    }

    @Test fun touchCanChangeAMatchingSettingOutsideTheSelectedCategoryAndClearSearch() {
        show()
        search().performTouchInput { click() }
        search().assert(editable).performTextInput("rumble")
        rule.frames(8)
        rule.onNodeWithTag("nova-settings-row-checkbox_enable_rumble").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf("checkbox_enable_rumble"), writes)
        assertEquals(NovaSettingValue.BooleanValue(true), values["checkbox_enable_rumble"])
        rule.onNodeWithText("Clear", ignoreCase = true).performClick()
        rule.frames(8)
        assertEquals("", query)
        assertEquals("stream", selected)
        rule.onNodeWithTag("nova-settings-row-frame_pacing").assertIsDisplayed()
    }
}

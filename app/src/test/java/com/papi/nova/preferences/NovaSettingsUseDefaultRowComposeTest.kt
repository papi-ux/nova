package com.papi.nova.preferences

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

/**
 * A remote has no X, and a setting's Reset is for touch only, so a profile's override could not be
 * dropped from a remote (C02). The page a setting opens carries a last row, Use Preset Default,
 * while the profile overrides it: Center on it resets the setting and goes back to its row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaSettingsUseDefaultRowComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val definitions = NovaSettingsDefinitionSet(
        listOf(NovaSettingCategory("stream", "Stream", "")),
        listOf(
            NovaSettingDefinition(
                key = "frame_pacing", title = "Frame pacing", summary = "%s", categoryKey = "stream",
                type = NovaSettingType.Select, defaultValue = NovaSettingValue.StringValue("latency"),
                options = listOf("warp2", "warp", "latency", "balanced", "cap-fps", "smoothness")
                    .map { NovaSettingOption(it.replaceFirstChar(Char::uppercase), it) },
            ),
            NovaSettingDefinition(
                key = PreferenceConfiguration.BITRATE_PREF_STRING, title = "Bitrate", summary = "", categoryKey = "stream",
                type = NovaSettingType.Slider, defaultValue = NovaSettingValue.IntValue(20_000),
                min = 500, max = 300_000, step = 500,
            ),
        ),
    )

    private var resettable by mutableStateOf<Set<String>>(emptySet())
    private val resets = mutableListOf<String>()

    private fun show(): NovaTestKeys {
        val keys = rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), "stream", "", resettableKeys = resettable),
                title = "Settings",
                subtitle = "Profile overrides",
                onBack = {},
                onOpenLegacy = {},
                onSearch = {},
                onClearSearch = {},
                onCategory = {},
                headerActions = emptyList(),
                onResetSetting = { resets += it.key },
                onValue = { _, _, done -> done() },
                onSetting = {},
            )
        }
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS + 32)
        rule.waitForIdle()
        return keys
    }

    // Its words as a player reads them, so the test reads the same on the code before the row.
    private fun useDefault() = "Use Preset Default"

    @Test
    fun anOverriddenSettingsPageEndsInUseDefaultAndCenterResetsIt() {
        resettable = setOf("frame_pacing")
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        rule.frames(8)
        rule.onNodeWithTag("nova-settings-row-frame_pacing").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        rule.onNodeWithText("Latency").assertIsFocused()

        // Past the six options, the last row, composed as the list scrolls to it.
        repeat(8) { keys.press(NovaTestKeys.DOWN) }
        rule.frames(8)
        rule.onNodeWithText(useDefault()).assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)

        assertEquals(listOf("frame_pacing"), resets)
        rule.onNodeWithText(useDefault()).assertDoesNotExist()
    }

    @Test
    fun anExactValuePageCarriesItToo() {
        resettable = setOf(PreferenceConfiguration.BITRATE_PREF_STRING)
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.DOWN)
        rule.frames(8)
        rule.onNodeWithTag("nova-settings-row-${PreferenceConfiguration.BITRATE_PREF_STRING}").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        rule.onNodeWithText(useDefault()).assertExists()
    }

    @Test
    fun aSettingThePresetDoesNotOverrideHasNoSuchRow() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        rule.frames(8)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        rule.onNodeWithText("Balanced").assertExists()
        rule.onNodeWithText(useDefault()).assertDoesNotExist()
    }
}

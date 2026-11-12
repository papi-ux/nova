package com.papi.nova.preferences

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
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
 * Back on the rows after a list page, with animations off (#481).
 *
 * On the RP6, with every animation scale at 0, choosing Balanced for Video frame pacing saved it
 * and returned to the rows with focus on the header's Back instead of the row. A page that pops
 * keeps the pane's focus while it animates out, and the rows' gate lets the row take focus back
 * then; with no animation the page has already gone, the pane holds nothing, and the gate refused
 * the row. These run the pane with every animation finished at once, as the device did.
 * NovaSettingsPaneComposeTest walks the same rows with animations on.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaSettingsChoiceReturnComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>(
        effectContext = object : MotionDurationScale {
            override val scaleFactor: Float = 0f
        },
    )

    private val categories = listOf(
        NovaSettingCategory("stream", "Stream", "How the stream looks"),
        NovaSettingCategory("input", "Input", ""),
    )
    private val settings = listOf(
        NovaSettingDefinition(
            key = "checkbox_enable_hdr", title = "HDR", summary = "", categoryKey = "stream",
            type = NovaSettingType.Toggle, defaultValue = NovaSettingValue.BooleanValue(false),
        ),
        NovaSettingDefinition(
            key = PreferenceConfiguration.FPS_PREF_STRING, title = "Frame rate", summary = "%s", categoryKey = "stream",
            type = NovaSettingType.Select, defaultValue = NovaSettingValue.StringValue("60"),
            options = listOf("30", "60", "90", "120").map { NovaSettingOption("$it FPS", it) },
        ),
        NovaSettingDefinition(
            key = "frame_pacing", title = "Frame pacing", summary = "%s", categoryKey = "stream",
            type = NovaSettingType.Select, defaultValue = NovaSettingValue.StringValue("latency"),
            options = listOf("warp2", "warp", "latency", "balanced", "cap-fps", "smoothness")
                .map { NovaSettingOption(it.replaceFirstChar(Char::uppercase), it) },
        ),
        NovaSettingDefinition(
            key = "input-on", title = "Mouse", summary = "", categoryKey = "input",
            type = NovaSettingType.Toggle,
        ),
    )
    private val definitions = NovaSettingsDefinitionSet(categories, settings)

    private var values by mutableStateOf<Map<String, NovaSettingValue>>(emptyMap())
    private var selected by mutableStateOf("stream")

    private fun state() = NovaSettingsUiStateFactory.build(definitions, values, selected, "")

    private fun show(): NovaTestKeys {
        val keys = rule.setPanelContent {
            val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = 900 }
            CompositionLocalProvider(LocalConfiguration provides config) {
                NovaSettingsContent(
                    state = state(),
                    title = "Settings",
                    subtitle = "Pane",
                    onBack = {},
                    onOpenLegacy = {},
                    onSearch = {},
                    onClearSearch = {},
                    onCategory = { selected = it },
                    headerActions = emptyList(),
                    onResetSetting = {},
                    onValue = { definition, value, done ->
                        values = values + (definition.key to value)
                        done()
                    },
                    onSetting = {},
                    returnToRow = null,
                )
            }
        }
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS + 32)
        rule.waitForIdle()
        return keys
    }

    private fun category(key: String) = rule.onNodeWithTag("nova-settings-category-$key")
    private fun row(key: String) = rule.onNodeWithTag("nova-settings-row-$key")
    private fun settle() = rule.frames(8)

    /** From the rail into the rows, down to Frame pacing, and A to open its list. */
    private fun openFramePacing(keys: NovaTestKeys) {
        keys.press(NovaTestKeys.RIGHT)
        repeat(2) { keys.press(NovaTestKeys.DOWN) }
        settle()
        row("frame_pacing").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        settle()
        rule.onNodeWithText("Latency").assertIsFocused()
    }

    @Test
    fun choosingAnOptionReturnsFocusToTheRowThatOpenedTheList() {
        val keys = show()
        openFramePacing(keys)
        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)
        settle()
        assertEquals(NovaSettingValue.StringValue("balanced"), values["frame_pacing"])
        row("frame_pacing").assertIsFocused()

        // And the next key moves on from the row, not from the header.
        keys.press(NovaTestKeys.UP)
        settle()
        row(PreferenceConfiguration.FPS_PREF_STRING).assertIsFocused()
    }

    @Test
    fun bFromTheListAlsoReturnsToTheRow() {
        val keys = show()
        openFramePacing(keys)
        keys.back()
        settle()
        row("frame_pacing").assertIsFocused()
        assertEquals("B chose nothing", null, values["frame_pacing"])
    }

    @Test
    fun browsingTheRailStillNeverPullsFocusIntoThePane() {
        val keys = show()
        openFramePacing(keys)
        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)
        settle()
        row("frame_pacing").assertIsFocused()

        keys.press(NovaTestKeys.LEFT)
        settle()
        category("stream").assertIsFocused()
        keys.press(NovaTestKeys.DOWN)
        settle()
        category("input").assertIsFocused()
        assertEquals("the highlighted category previews its pane", "input", selected)
        rule.onNode(isFocused() and hasTestTag("nova-settings-row-input-on")).assertDoesNotExist()

        keys.press(NovaTestKeys.UP)
        settle()
        category("stream").assertIsFocused()
        rule.onNode(isFocused() and hasTestTag("nova-settings-row-frame_pacing")).assertDoesNotExist()
    }
}

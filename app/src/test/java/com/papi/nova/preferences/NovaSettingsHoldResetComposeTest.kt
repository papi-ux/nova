package com.papi.nova.preferences

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.advance
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A switch or a choice that changes in its own row opens no page, so a preset's override of it
 * could be dropped only with X or a touch Reset, which a TV remote has neither of (C02). A hold of
 * OK, or A on a controller, splits the row in place into Keep, focused, and Use Preset Default,
 * and the hint bar names that hold on an overridden row for the keys last pressed. Driven through
 * the Settings screen itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaSettingsHoldResetComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val definitions = NovaSettingsDefinitionSet(
        listOf(NovaSettingCategory("stream", "Stream", "")),
        listOf(
            NovaSettingDefinition(
                key = "checkbox_enable_hdr", title = "HDR", summary = "", categoryKey = "stream",
                type = NovaSettingType.Toggle, defaultValue = NovaSettingValue.BooleanValue(false),
            ),
            NovaSettingDefinition(
                key = PreferenceConfiguration.FPS_PREF_STRING, title = "Frame rate", summary = "%s", categoryKey = "stream",
                type = NovaSettingType.Select, defaultValue = NovaSettingValue.StringValue("60"),
                options = listOf("30", "60", "90", "120").map { NovaSettingOption("$it FPS", it) },
            ),
        ),
    )

    private var resettable by mutableStateOf(setOf("checkbox_enable_hdr", PreferenceConfiguration.FPS_PREF_STRING))
    private val resets = mutableListOf<String>()
    private val writes = mutableListOf<Pair<String, NovaSettingValue>>()
    private var values by mutableStateOf<Map<String, NovaSettingValue>>(emptyMap())

    private fun show(): NovaTestKeys {
        val keys = rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, values, "stream", "", resettableKeys = resettable),
                title = "Settings",
                subtitle = "Profile overrides",
                onBack = {},
                onOpenLegacy = {},
                onSearch = {},
                onClearSearch = {},
                onCategory = {},
                headerActions = emptyList(),
                onResetSetting = {
                    resets += it.key
                    resettable = resettable - it.key
                },
                onValue = { definition, value, done ->
                    writes += definition.key to value
                    values = values + (definition.key to value)
                    done()
                },
                onSetting = {},
            )
        }
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS + 32)
        rule.waitForIdle()
        return keys
    }

    private fun row(key: String) = rule.onNodeWithTag("nova-settings-row-$key")

    // Its words as a player reads them, so the test reads the same on the code before the split.
    private fun button(text: String) = rule.onNode(hasText(text) and hasClickAction())

    // The hint bar, by what it says: on a value row A reads Toggle or Next, not Select, and B is
    // always there, as a controller's Back or a remote's Go back.
    private val hintBar = SemanticsMatcher("the hint bar") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(" Back") || it.contains(" Go back") }
    }

    private fun hints(): String = rule.onNode(hintBar).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    /** Holds OK past the hold's half second, or for [millis], then lets it go. */
    private fun hold(keys: NovaTestKeys, millis: Long = NOVA_HOLD_MS) {
        rule.mainClock.autoAdvance = false
        keys.down(NovaTestKeys.CENTER)
        rule.advance(millis)
        rule.frames(4)
        keys.up(NovaTestKeys.CENTER)
        rule.frames(4)
    }

    /** Which half of the split has focus, for a message. */
    private fun focusedHalf(): String = listOf("Keep", "Use Preset Default").firstOrNull { label ->
        runCatching { button(label).assertIsFocused() }.isSuccess
    } ?: "neither"

    private fun controllerKey(keys: NovaTestKeys, code: Int) {
        val now = SystemClock.uptimeMillis()
        val source = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD
        rule.runOnUiThread {
            keys.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, 99, 0, 0, source))
            keys.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, 99, 0, 0, source))
        }
        rule.waitForIdle()
    }

    @Test
    fun holdingOkOnAnOverriddenSwitchSplitsItAndUsePresetDefaultResetsIt() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        rule.waitForIdle()
        row("checkbox_enable_hdr").assertIsFocused()
        assertTrue("a remote's hint names the hold: ${hints()}", hints().contains("Hold OK Reset"))

        hold(keys)
        assertTrue("the hold changed nothing: $writes", writes.isEmpty())
        button("Keep").assertIsFocused()
        button("Use Preset Default").assertExists()

        // A, Right, A confirms, once the split's guard has passed.
        rule.advance(NovaPanelMetrics.SplitGuardMillis)
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        assertEquals(listOf("checkbox_enable_hdr"), resets)
        assertTrue(writes.isEmpty())
        button("Use Preset Default").assertDoesNotExist()
        row("checkbox_enable_hdr").assertIsFocused()
    }

    @Test
    fun holdingOkOnAnOverriddenChoiceSplitsItAndKeepLeavesItAsItWas() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.DOWN)
        rule.waitForIdle()
        row(PreferenceConfiguration.FPS_PREF_STRING).assertIsFocused()

        hold(keys)
        assertTrue("the hold did not step the value: $writes", writes.isEmpty())
        button("Keep").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        assertTrue(resets.isEmpty())
        assertTrue(writes.isEmpty())
        button("Keep").assertDoesNotExist()
        row(PreferenceConfiguration.FPS_PREF_STRING).assertIsFocused()

        // B takes the split away too, and the row keeps focus.
        hold(keys)
        button("Keep").assertIsFocused()
        keys.back()
        rule.frames(8)
        button("Keep").assertDoesNotExist()
        row(PreferenceConfiguration.FPS_PREF_STRING).assertIsFocused()
        assertTrue(resets.isEmpty())
    }

    @Test
    fun aControllersHintNamesAHoldOfAAndAShortPressStillChangesTheSwitch() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        controllerKey(keys, KeyEvent.KEYCODE_DPAD_DOWN)
        controllerKey(keys, KeyEvent.KEYCODE_DPAD_UP)
        row("checkbox_enable_hdr").assertIsFocused()
        val onController = hints()
        assertTrue(onController, onController.contains("Hold A Reset"))
        assertFalse("not a remote's OK: $onController", onController.contains("Hold OK"))

        keys.press(NovaTestKeys.CENTER)
        rule.waitForIdle()
        assertEquals(listOf("checkbox_enable_hdr" to NovaSettingValue.BooleanValue(true)), writes)
        button("Keep").assertDoesNotExist()
    }

    @Test
    fun aRowThePresetDoesNotOverrideNeitherSplitsNorNamesTheHold() {
        resettable = emptySet()
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        rule.waitForIdle()
        assertFalse(hints(), hints().contains("Hold"))
        hold(keys)
        button("Keep").assertDoesNotExist()
        assertEquals("the release of a plain press changes it, as ever", listOf("checkbox_enable_hdr" to NovaSettingValue.BooleanValue(true)), writes)
    }

    // A split's halves act on release, however long the key is held, as every other split's do.
    // The hold had started again on Use Preset Default and armed the split over itself: focus went
    // back to Keep and nothing was reset.
    @Test
    fun holdingOkOnUsePresetDefaultResetsItAsAPressDoes() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        rule.waitForIdle()
        hold(keys)
        button("Keep").assertIsFocused()
        rule.advance(NovaPanelMetrics.SplitGuardMillis + 100)
        keys.press(NovaTestKeys.RIGHT)
        rule.frames(4)
        button("Use Preset Default").assertIsFocused()

        hold(keys)
        rule.frames(8)
        assertEquals("focus after the hold: ${focusedHalf()}", listOf("checkbox_enable_hdr"), resets)
        assertTrue(writes.isEmpty())
        button("Use Preset Default").assertDoesNotExist()
        row("checkbox_enable_hdr").assertIsFocused()
    }

    @Test
    fun holdingOkOnKeepKeepsItAsAPressDoes() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        rule.waitForIdle()
        hold(keys)
        button("Keep").assertIsFocused()

        hold(keys)
        rule.frames(8)
        button("Keep").assertDoesNotExist()
        row("checkbox_enable_hdr").assertIsFocused()
        assertTrue(resets.isEmpty())
        assertTrue(writes.isEmpty())
    }

    // The hint bar named the hold while focus was on Keep or Use Preset Default, where doing what
    // it said put focus back on Keep and reset nothing. It names it on the row itself only.
    @Test
    fun theHintNamesTheHoldOnTheRowAndOnNeitherHalfOfItsSplit() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        rule.waitForIdle()
        hold(keys)
        button("Keep").assertIsFocused()
        assertFalse("on Keep: ${hints()}", hints().contains("Hold"))

        rule.advance(NovaPanelMetrics.SplitGuardMillis + 100)
        keys.press(NovaTestKeys.RIGHT)
        rule.frames(4)
        button("Use Preset Default").assertIsFocused()
        assertFalse("on Use Preset Default: ${hints()}", hints().contains("Hold"))

        keys.press(NovaTestKeys.LEFT)
        keys.press(NovaTestKeys.CENTER)
        rule.frames(8)
        row("checkbox_enable_hdr").assertIsFocused()
        assertTrue("back on the row: ${hints()}", hints().contains("Hold OK Reset"))
    }

    // Focus that moves on while OK is still held takes the hold with it: the row it left never
    // splits and pulls focus back to its Keep.
    @Test
    fun aHoldCutShortByFocusMovingOnSplitsNothing() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        rule.waitForIdle()
        row("checkbox_enable_hdr").assertIsFocused()
        rule.mainClock.autoAdvance = false
        keys.down(NovaTestKeys.CENTER)
        rule.advance(100)
        keys.press(NovaTestKeys.DOWN)
        rule.advance(NOVA_HOLD_MS)
        rule.frames(4)
        button("Keep").assertDoesNotExist()
        row(PreferenceConfiguration.FPS_PREF_STRING).assertIsFocused()
        keys.up(NovaTestKeys.CENTER)
        rule.frames(4)
        assertTrue(writes.isEmpty())
        assertTrue(resets.isEmpty())
    }

    // The hold's own release, however long after the split's guard it comes, answers nothing.
    @Test
    fun aLongHoldsReleaseLeavesTheSplitStanding() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        rule.waitForIdle()
        hold(keys, millis = 2_000L)
        rule.frames(8)
        button("Keep").assertIsFocused()
        button("Use Preset Default").assertExists()
        assertTrue(resets.isEmpty())
        assertTrue(writes.isEmpty())
    }

    private companion object {
        // Past the half second the hold takes.
        const val NOVA_HOLD_MS = 600L
    }
}

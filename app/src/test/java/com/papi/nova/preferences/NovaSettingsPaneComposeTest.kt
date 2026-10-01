package com.papi.nova.preferences

import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.panel.NovaTestKeys
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
 * The Settings pane on the panel foundation: the rail keeps first focus, in-place choices change
 * in their rows, lists open as pages in the pane, B walks back one level at a time, and the quick
 * strip wraps instead of cutting a pill.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaSettingsPaneComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val categories = listOf(
        NovaSettingCategory("stream", "Stream", "How the stream looks"),
        NovaSettingCategory("input", "Input", ""),
        NovaSettingCategory("empty", "Empty", ""),
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
            key = PreferenceConfiguration.BITRATE_PREF_STRING, title = "Bitrate", summary = "", categoryKey = "stream",
            type = NovaSettingType.Slider, defaultValue = NovaSettingValue.IntValue(20_000),
            min = 500, max = 300_000, step = 500,
        ),
        NovaSettingDefinition(
            key = "pref_debug_info", title = "Debug info", summary = "", categoryKey = "stream",
            type = NovaSettingType.Action,
        ),
        NovaSettingDefinition(
            key = "input-off", title = "Needs rumble", summary = "", categoryKey = "input",
            type = NovaSettingType.Toggle, dependencyKey = "rumble",
        ),
        NovaSettingDefinition(
            key = "input-on", title = "Mouse", summary = "", categoryKey = "input",
            type = NovaSettingType.Toggle,
        ),
    )
    private val definitions = NovaSettingsDefinitionSet(categories, settings)

    private var values by mutableStateOf<Map<String, NovaSettingValue>>(mapOf("rumble" to NovaSettingValue.BooleanValue(false)))
    private var selected by mutableStateOf("stream")
    private val writes = mutableListOf<Pair<String, NovaSettingValue>>()
    private var backs = 0
    private var actions = mutableListOf<String>()

    private var resettable by mutableStateOf<Set<String>>(emptySet())
    private val resets = mutableListOf<String>()

    private fun state() = NovaSettingsUiStateFactory.build(definitions, values, selected, "", resettableKeys = resettable)

    private fun show(widthDp: Int = 900, returnToRow: String? = null): NovaTestKeys {
        val keys = rule.setPanelContent {
            val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = widthDp }
            CompositionLocalProvider(LocalConfiguration provides config) {
                NovaSettingsContent(
                    state = state(),
                    title = "Settings",
                    subtitle = "Pane",
                    onBack = { backs++ },
                    onOpenLegacy = {},
                    onSearch = {},
                    onClearSearch = {},
                    onCategory = { selected = it },
                    headerActions = emptyList(),
                    onResetSetting = { resets += it.key },
                    onValue = { definition, value, done ->
                        writes += definition.key to value
                        values = values + (definition.key to value)
                        done()
                    },
                    onSetting = { actions += it.key },
                    returnToRow = returnToRow,
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

    @Test
    fun aWindowUnder560dpTallDrawsThePaneAtTheCompactDensity() {
        show()
        val results = mutableListOf<TextLayoutResult>()
        rule.onNode(hasText("HDR") and hasAnyAncestor(hasTestTag("nova-settings-row-checkbox_enable_hdr")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("a pane row's title in the compact size", 14.sp, results.first().layoutInput.style.fontSize)
    }

    @Test
    fun theRailsRowsAreThePanesRows() {
        show()
        val results = mutableListOf<TextLayoutResult>()
        rule.onNode(hasText("Input") and hasAnyAncestor(hasTestTag("nova-settings-category-input")), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("a category reads at the pane rows' title size", 14.sp, results.first().layoutInput.style.fontSize)
        val input = category("input").getBoundsInRoot()
        val empty = category("empty").getBoundsInRoot()
        assertTrue("a category is at least a compact row tall", (input.bottom - input.top) >= 44.dp)
        assertEquals("categories sit the pane's compact row gap apart", 4f, (empty.top - input.bottom).value, 0.5f)
    }

    // B from the Legacy screen a row opened came back on the rail's first category, with the row
    // out of sight in the pane (audit M13).
    @Test
    fun aReturnToARowPutsFocusOnThatRowNotOnTheRail() {
        show(returnToRow = "frame_pacing")
        settle()
        row("frame_pacing").assertIsFocused()
        assertEquals("stream", selected)
    }

    // Reset was 40dp tall on a handheld and 44dp elsewhere (audit C24). It keeps that look, and a
    // finger has 48dp to hit.
    @Test
    fun resetKeepsItsLookAndAFingerHasTheFull48dp() {
        resettable = setOf("checkbox_enable_hdr")
        show()
        val reset = rule.onNode(hasText("Reset") and hasClickAction()).getBoundsInRoot()
        assertTrue("Reset's touch target is at least 48dp tall: $reset", reset.bottom - reset.top >= 48.dp)
    }

    // A compact row is 44dp, and Reset's 48dp target was its real height: the row grew 4dp when an
    // override's Reset appeared, and every row under it moved down (audit C24). The target reaches
    // past the button instead, so the row keeps its height and a tap just off the button resets.
    @Test
    fun aResetDoesNotGrowItsRowOrMoveTheRowsUnderIt() {
        show()
        val next = row(PreferenceConfiguration.FPS_PREF_STRING)
        val before = next.getUnclippedBoundsInRoot().top
        resettable = setOf("checkbox_enable_hdr")
        settle()
        assertEquals("the row under the reset one stays put", before.value, next.getUnclippedBoundsInRoot().top.value, 0.5f)

        // The button is drawn 40dp tall around its label; a tap 3dp above it still resets.
        val label = rule.onNode(hasText("Reset") and hasAnyAncestor(hasClickAction()), useUnmergedTree = true).getUnclippedBoundsInRoot()
        with(rule.density) {
            val x = ((label.left + label.right) / 2).toPx()
            val buttonTop = (label.top + label.bottom) / 2 - 20.dp
            rule.onRoot().performTouchInput { click(Offset(x, (buttonTop - 3.dp).toPx())) }
        }
        rule.waitForIdle()
        assertEquals("a tap just above the button still resets", listOf("checkbox_enable_hdr"), resets)

        val source = java.io.File("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt").readText()
            .substringAfter("private fun NovaSettingResetButton(").substringBefore("\n}\n")
        assertTrue("the target reaches past the button rather than sizing its row", source.contains("Modifier.novaTouchReach(reach, target)"))
        assertFalse("its 48dp is not layout height", source.contains(".heightIn(min = NovaSettingsMetrics.touchTargetMinDp().dp)"))
    }

    // The hint bar named X and L1/R1 to a TV remote, which has neither (audit C04). After a key from
    // a remote it names OK and Back, as the Library does; a controller's key brings its keys back.
    @Test
    fun afterARemotesKeyTheHintBarNamesARemotesKeys() {
        resettable = setOf("checkbox_enable_hdr")
        val keys = show()
        fun hints(): String = rule.onNode(hasContentDescription("Select", substring = true))
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        fun keyFrom(source: Int) {
            val now = SystemClock.uptimeMillis()
            rule.runOnUiThread {
                keys.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0, 0, 99, 0, 0, source))
                keys.view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0, 99, 0, 0, source))
            }
            settle()
        }
        val remote = rule.activity.getString(com.papi.nova.R.string.nova_controller_hint_remote_center)
        val x = rule.activity.getString(com.papi.nova.R.string.nova_controller_hint_x)
        val shoulders = rule.activity.getString(com.papi.nova.R.string.nova_controller_hint_lb_rb)

        keyFrom(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD)
        assertTrue(hints(), hints().contains("$x ") && hints().contains(shoulders))

        keyFrom(InputDevice.SOURCE_DPAD)
        val onRemote = hints()
        assertTrue(onRemote, onRemote.startsWith("$remote "))
        assertFalse("a remote has no X: $onRemote", onRemote.contains("$x "))
        assertFalse("nor shoulders: $onRemote", onRemote.contains(shoulders))

        keyFrom(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD)
        assertTrue(hints(), hints().contains("$x ") && hints().contains(shoulders))
    }

    @Test
    fun settingsOpensOnTheRailAndBrowsingItNeverPullsFocusIntoThePane() {
        val keys = show()
        category("stream").assertIsFocused()

        keys.press(NovaTestKeys.DOWN)
        settle()
        category("input").assertIsFocused()
        row("input-on").assertExists()
        assertEquals("the highlighted category previews its pane", "input", selected)
        rule.onNode(isFocused() and hasTestTag("nova-settings-row-input-on")).assertDoesNotExist()
    }

    @Test
    fun rightEntersThePaneAndLeftFromAPlainRowReturnsToTheRail() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        settle()
        row("checkbox_enable_hdr").assertIsFocused()

        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.DOWN)
        settle()
        row("frame_pacing").assertIsFocused()
        keys.press(NovaTestKeys.LEFT)
        settle()
        category("stream").assertIsFocused()

        keys.press(NovaTestKeys.RIGHT)
        settle()
        row("frame_pacing").assertIsFocused()
    }

    @Test
    fun rightSkipsADisabledRowButTheDisabledRowSaysWhy() {
        val keys = show()
        keys.press(NovaTestKeys.DOWN)
        settle()
        keys.press(NovaTestKeys.RIGHT)
        settle()
        row("input-on").assertIsFocused()
        rule.onNodeWithText("Turn on the setting this depends on to change it", substring = true).assertExists()
    }

    @Test
    fun anInPlaceChoiceChangesWithLeftAndRightAndKeepsFocus() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.DOWN)
        settle()
        row(PreferenceConfiguration.FPS_PREF_STRING).assertIsFocused()

        keys.press(NovaTestKeys.RIGHT)
        settle()
        assertEquals(NovaSettingValue.StringValue("90"), values[PreferenceConfiguration.FPS_PREF_STRING])
        row(PreferenceConfiguration.FPS_PREF_STRING).assertIsFocused()

        keys.press(NovaTestKeys.LEFT)
        keys.press(NovaTestKeys.LEFT)
        settle()
        assertEquals(NovaSettingValue.StringValue("30"), values[PreferenceConfiguration.FPS_PREF_STRING])
        // An ordered scale stops at its end, and Left never leaves the row for the rail.
        keys.press(NovaTestKeys.LEFT)
        settle()
        assertEquals(NovaSettingValue.StringValue("30"), values[PreferenceConfiguration.FPS_PREF_STRING])
        row(PreferenceConfiguration.FPS_PREF_STRING).assertIsFocused()
    }

    @Test
    fun aToggleFlipsWithAAndLeftMeansOffAndRightMeansOn() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        settle()
        keys.press(NovaTestKeys.CENTER)
        settle()
        assertEquals(NovaSettingValue.BooleanValue(true), values["checkbox_enable_hdr"])
        keys.press(NovaTestKeys.LEFT)
        settle()
        assertEquals(NovaSettingValue.BooleanValue(false), values["checkbox_enable_hdr"])
        keys.press(NovaTestKeys.RIGHT)
        settle()
        assertEquals(NovaSettingValue.BooleanValue(true), values["checkbox_enable_hdr"])
        row("checkbox_enable_hdr").assertIsFocused()
    }

    @Test
    fun aListOpensAsAPageOnItsCurrentValueAndBLeavesItUnchanged() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        repeat(2) { keys.press(NovaTestKeys.DOWN) }
        settle()
        keys.press(NovaTestKeys.CENTER)
        settle()
        rule.onNodeWithText("Latency").assertIsFocused()
        val current = rule.onNodeWithText("Latency").fetchSemanticsNode().config
        assertTrue("the current option carries the one mark", current[SemanticsProperties.Selected])

        keys.back()
        settle()
        row("frame_pacing").assertIsFocused()
        assertTrue("B chose nothing", writes.isEmpty())

        keys.press(NovaTestKeys.CENTER)
        settle()
        keys.press(NovaTestKeys.DOWN)
        keys.press(NovaTestKeys.CENTER)
        settle()
        assertEquals(NovaSettingValue.StringValue("balanced"), values["frame_pacing"])
        row("frame_pacing").assertIsFocused()
    }

    @Test
    fun bAtTheRowsGoesToTheRailAndBOnTheRailLeaves() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        settle()
        keys.back()
        settle()
        category("stream").assertIsFocused()
        assertEquals(0, backs)

        keys.back()
        settle()
        assertEquals(1, backs)
    }

    @Test
    fun theStepperStepsInPlaceAndAOpensTheExactPage() {
        val keys = show()
        keys.press(NovaTestKeys.RIGHT)
        repeat(3) { keys.press(NovaTestKeys.DOWN) }
        settle()
        row(PreferenceConfiguration.BITRATE_PREF_STRING).assertIsFocused()
        keys.press(NovaTestKeys.RIGHT)
        settle()
        assertEquals(NovaSettingValue.IntValue(20_500), values[PreferenceConfiguration.BITRATE_PREF_STRING])
        assertEquals(
            "20.5 Mbps",
            row(PreferenceConfiguration.BITRATE_PREF_STRING).fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )

        keys.press(NovaTestKeys.CENTER)
        settle()
        rule.onNode(androidx.compose.ui.test.hasTestTag(com.papi.nova.ui.panel.NovaPageBackTag) and androidx.compose.ui.test.hasText("Bitrate")).assertExists()
        keys.back()
        settle()
        row(PreferenceConfiguration.BITRATE_PREF_STRING).assertIsFocused()
    }

    @Test
    fun shouldersStepThroughTheCategoriesFromTheRail() {
        val keys = show()
        keys.press(KeyEvent.KEYCODE_BUTTON_R1)
        settle()
        assertEquals("input", selected)
        category("input").assertIsFocused()
        keys.press(KeyEvent.KEYCODE_BUTTON_L1)
        settle()
        assertEquals("stream", selected)
    }

    @Test
    @Config(qualifiers = "w480dp-h900dp")
    fun theQuickStripKeepsEveryPillWholeAndReachable() {
        show(widthDp = 480)
        rule.onNodeWithText("Menu").performClick()
        val root = rule.onRoot().getBoundsInRoot()
        val quick = listOf(PreferenceConfiguration.FPS_PREF_STRING, "frame_pacing", PreferenceConfiguration.BITRATE_PREF_STRING)
        quick.forEach { key ->
            val bounds = rule.onNodeWithTag("nova-settings-quick-$key").performScrollTo().assertIsDisplayed().getBoundsInRoot()
            assertTrue("pill $key ends inside the screen: $bounds", bounds.right <= root.right - 20.dp)
            assertTrue("pill $key starts inside the screen: $bounds", bounds.left >= root.left + 20.dp)
        }
    }

    @Test
    @Config(qualifiers = "w480dp-h900dp")
    fun inTheNarrowLayoutDownFromTheCategoriesEntersTheRows() {
        val keys = show(widthDp = 480)
        rule.onNodeWithText("Menu").performClick()
        category("empty").performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        keys.press(NovaTestKeys.DOWN)
        settle()
        row("checkbox_enable_hdr").assertIsFocused()
        // Portrait first closes the menu; another B at the rows leaves.
        keys.back()
        settle()
        assertEquals(0, backs)
        keys.back()
        settle()
        assertEquals(1, backs)
    }

    @Test
    fun aShortWindowLeavesTheQuickStripOutAndGivesItsHeightToTheRows() {
        // 520dp tall is the compact density: the strip repeats values the pane already shows, and on
        // a 468dp handheld it took two lines above the rows.
        show()
        rule.onNodeWithTag("nova-settings-quick-${PreferenceConfiguration.FPS_PREF_STRING}").assertDoesNotExist()
        category(selected).assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w900dp-h720dp")
    fun aQuickPillTakesFocusToItsRowAndOpensItsPage() {
        val keys = show()
        keys.press(NovaTestKeys.UP)
        settle()
        rule.onNodeWithTag("nova-settings-quick-${PreferenceConfiguration.FPS_PREF_STRING}").assertIsFocused()
        // The strip follows the quick settings' order: frame rate, bitrate, then frame pacing.
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.RIGHT)
        settle()
        rule.onNodeWithTag("nova-settings-quick-frame_pacing").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        settle()
        rule.onNodeWithText("Latency").assertIsFocused()
        keys.back()
        settle()
        row("frame_pacing").assertIsFocused()
    }
}

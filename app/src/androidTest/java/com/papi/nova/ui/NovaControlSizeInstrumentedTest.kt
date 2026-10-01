package com.papi.nova.ui

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.preferences.*
import com.papi.nova.ui.compose.NovaActionSurface
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.panel.NovaPanelDensityHost
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaValueRow
import com.papi.nova.ui.panel.NovaValueStyle
import com.papi.nova.ui.panel.NovaOption
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Owned emulator: the real shared controls and Settings renderer, with device-only writes. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovaControlSizeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val key = "nova_control_size"

    @Test fun choicesResizeButtonsKeepTextAndAnswerOutsideTheirVisualTouchBounds() = withRestoredChoice {
        val prefs = PreferenceManager.getDefaultSharedPreferences(compose.activity)
        var taps = 0
        var physicalDensity = 0f
        lateinit var input: InputModeManager
        compose.setContent {
            NovaComposeTheme {
                input = LocalInputModeManager.current
                physicalDensity = LocalDensity.current.density
                Column(Modifier.padding(24.dp)) {
                    NovaActionSurface(onClick = {}, modifier = Modifier.width(160.dp).testTag("sized-button"), minHeight = 80.dp) { color, _ ->
                        Text("Sample", color = color, fontSize = 16.sp, modifier = Modifier.testTag("size-label"))
                    }
                    Spacer(Modifier.height(24.dp))
                    NovaActionSurface(onClick = { taps++ }, modifier = Modifier.width(160.dp).testTag("small-button"),
                        minHeight = 30.dp, contentPadding = PaddingValues(2.dp)) { color, _ ->
                        Text("Touch", color = color, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(60.dp))
                }
            }
        }
        compose.runOnIdle { input.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
        val standard = compose.onNodeWithTag("sized-button").fetchSemanticsNode().boundsInRoot.height
        fun textHeight(): Int {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("size-label", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single().size.height
        }
        val originalText = textHeight()
        for ((choice, factor) in listOf("compact" to (.72f / .88f), "standard" to 1f, "large" to (1.15f / .88f))) {
            compose.onNodeWithTag("sized-button").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.runOnIdle { prefs.edit().putString(key, choice).commit() }
            compose.waitForIdle()
            val button = compose.onNodeWithTag("sized-button")
            assertEquals(choice, standard * factor, button.fetchSemanticsNode().boundsInRoot.height, 1f)
            assertEquals("text remains its chosen size at $choice", originalText, textHeight())
            button.assertIsFocused()
            sizeShot("buttons-$choice")
            val bounds = compose.onNodeWithTag("small-button").fetchSemanticsNode().boundsInRoot
            assertTrue("the fixture reaches the automatic 48dp touch expansion", bounds.height < 44 * physicalDensity)
            val before = taps
            compose.onRoot().performTouchInput {
                // 23dp from centre is inside the original 48dp target and outside a
                // 48dp target incorrectly shrunk to Compact's 42.24dp.
                click(Offset(bounds.center.x, bounds.center.y + 23 * physicalDensity))
            }
            compose.waitForIdle()
            assertEquals("the area below the visual button still answers touch at $choice", before + 1, taps)
            compose.runOnIdle { input.requestInputMode(InputMode.Keyboard) }
        }
    }

    @Test fun compactPickerEdgesReachTheirOwnChoicesInsteadOfTheParentNextAction() = withRestoredChoice {
        val prefs = PreferenceManager.getDefaultSharedPreferences(compose.activity)
        prefs.edit().putString(key, "compact").commit()
        var cycle by mutableStateOf(1)
        var segment by mutableStateOf(2)
        var edgePixels = 0f
        var arrowHalfWidthPixels = 0f
        compose.setContent {
            NovaComposeTheme {
                NovaPanelDensityHost {
                    val density = LocalDensity.current
                    // Exercise the real 48dp hit boundary, including the short landscape
                    // control. This is outside a minimum target shrunk to 42.24dp.
                    edgePixels = with(density) { 23.dp.toPx() }
                    arrowHalfWidthPixels = with(density) { (NovaPanelMetrics.ArrowTarget / 2).toPx() }
                    Column(Modifier.padding(24.dp).width(300.dp)) {
                        NovaValueRow("Cycle", listOf(NovaOption(0, "Low"), NovaOption(1, "Middle"), NovaOption(2, "High")),
                            current = cycle, onChange = { cycle = it }, style = NovaValueStyle.Cycler,
                            modifier = Modifier.testTag("edge-cycler"))
                        Spacer(Modifier.height(24.dp))
                        NovaValueRow("Segments", listOf(NovaOption(0, "One"), NovaOption(1, "Two"), NovaOption(2, "Three")),
                            current = segment, onChange = { segment = it }, style = NovaValueStyle.Segmented,
                            modifier = Modifier.testTag("edge-segments"))
                    }
                }
            }
        }
        compose.waitForIdle()
        val middle = compose.onNodeWithText("Middle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            click(Offset(middle.left - arrowHalfWidthPixels, middle.center.y + edgePixels))
        }
        compose.waitForIdle()
        assertEquals("the previous-arrow edge lowers the value instead of running Next on the row", 0, cycle)
        val two = compose.onNodeWithText("Two", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(two.center.x, two.center.y + edgePixels)) }
        compose.waitForIdle()
        assertEquals("the segment edge chooses Two instead of wrapping the parent's Three to One " +
            "(label=$two, row=${compose.onNodeWithTag("edge-segments").fetchSemanticsNode().boundsInRoot}, edge=$edgePixels)", 1, segment)
        sizeShot("picker-touch-boundary")
    }

    @Test fun settingsChoiceChangesInPlaceAndKeepsControllerFocus() = withRestoredChoice {
        val prefs = PreferenceManager.getDefaultSharedPreferences(compose.activity)
        val definitions = NovaSettingDefinitions.load(compose.activity)
        var values by mutableStateOf(definitions.settings.mapNotNull { definition ->
            definition.defaultValue?.let { definition.key to it }
        }.toMap())
        var selected by mutableStateOf("category_nova")
        lateinit var input: InputModeManager
        compose.setContent {
            NovaComposeTheme {
                input = LocalInputModeManager.current
                NovaSettingsContent(
                    state = NovaSettingsUiStateFactory.build(definitions, values, selected, ""),
                    title = "Settings", subtitle = "Device appearance", onBack = {}, onOpenLegacy = {},
                    onSearch = {}, onClearSearch = {}, onCategory = { selected = it }, headerActions = emptyList(),
                    onResetSetting = {}, onValue = { definition, value, done ->
                        values = values + (definition.key to value)
                        if (definition.key == key) prefs.edit().putString(key, (value as NovaSettingValue.StringValue).value).commit()
                        done()
                    }, onSetting = {},
                )
            }
        }
        compose.runOnIdle { input.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
        // Settings deliberately rejects direct outside focus requests into its pane. Enter
        // through its controller path, then reach Control Size like a player does.
        if (compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            compose.onNodeWithTag("nova-portrait-menu-toggle")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        } else {
            val first = definitions.categories.first().key
            compose.onNodeWithTag("nova-settings-category-$first")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            repeat(definitions.categories.indexOfFirst { it.key == "category_nova" }) {
                compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                compose.waitForIdle()
            }
            compose.onNodeWithTag("nova-settings-category-category_nova").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        }
        fun choiceHasFocus() = compose.onAllNodes(hasTestTag("nova-settings-row-$key") and isFocused())
            .fetchSemanticsNodes().isNotEmpty()
        repeat(10) {
            if (!choiceHasFocus()) {
                compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                compose.waitForIdle()
            }
        }
        val row = compose.onNodeWithTag("nova-settings-row-$key")
        row.assertIsDisplayed().assertIsFocused()
        for ((direction, choice, stored) in listOf(
            Triple(Key.DirectionLeft, "Compact", "compact"),
            Triple(Key.DirectionRight, "Standard", "standard"),
            Triple(Key.DirectionRight, "Large", "large"),
        )) {
            row.performKeyInput { pressKey(direction) }
            compose.waitForIdle()
            row.assertIsFocused()
            assertEquals(stored, prefs.getString(key, null))
            compose.onNodeWithText(choice).assertIsDisplayed()
            sizeShot("settings-$stored")
        }
        row.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals("the ordered choices stop at Large", "large", prefs.getString(key, null))
    }

    private fun withRestoredChoice(block: () -> Unit) {
        val arguments = InstrumentationRegistry.getArguments()
        val config = compose.activity.resources.configuration
        when (arguments.getString("orientation")) {
            "portrait" -> assertEquals(Configuration.ORIENTATION_PORTRAIT, config.orientation)
            "landscape" -> assertEquals(Configuration.ORIENTATION_LANDSCAPE, config.orientation)
        }
        arguments.getString("fontScale")?.toFloatOrNull()?.let {
            assertEquals("the fixture uses the requested system font scale", it, config.fontScale, .01f)
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(compose.activity)
        val previous = prefs.getString(key, null)
        prefs.edit().putString(key, "standard").commit()
        try { block() } finally {
            prefs.edit().apply { if (previous == null) remove(key) else putString(key, previous) }.commit()
        }
    }
}

private fun sizeShot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val suffix = InstrumentationRegistry.getArguments().getString("shotSuffix", "normal")
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "control-size")
    check(directory.mkdirs() || directory.isDirectory)
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    File(directory, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}

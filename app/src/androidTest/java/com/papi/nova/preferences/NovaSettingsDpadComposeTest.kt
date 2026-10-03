package com.papi.nova.preferences

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.NovaComposeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovaSettingsDpadComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var inputMode: InputModeManager

    @Test fun leftReturnsToThePaneOwnerAndRightRestoresItsLastRow() {
        showSettings()
        category("stream").performSemanticsAction(SemanticsActions.RequestFocus)
        category("stream").performKeyInput { pressKey(Key.DirectionRight) }
        row("stream-0").assertIsFocused()
        repeat(3) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        row("stream-3").assertIsFocused()
        row("stream-3").performKeyInput { pressKey(Key.DirectionLeft) }
        category("stream").assertIsFocused()
        category("stream").performKeyInput { pressKey(Key.DirectionRight) }
        row("stream-3").assertIsFocused()
    }

    @Test fun categoryFocusPreviewsItsPaneAndRightSkipsDisabledRows() {
        showSettings()
        category("stream").performSemanticsAction(SemanticsActions.RequestFocus)
        category("stream").performKeyInput { pressKey(Key.DirectionDown) }
        category("input").assertIsFocused()
        row("input-1").assertExists()
        row("stream-0").assertDoesNotExist()
        category("input").performKeyInput { pressKey(Key.DirectionRight) }
        row("input-1").assertIsFocused()
        row("input-1").performKeyInput { pressKey(Key.DirectionLeft) }
        category("input").assertIsFocused()
    }

    @Test fun quickSettingsAreReachableAndDownReturnsToTheCategory() {
        showSettings(heightDp = 800)
        category("stream").performSemanticsAction(SemanticsActions.RequestFocus)
        category("stream").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("nova-settings-quick-quick-0").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("nova-settings-quick-quick-1").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        category("stream").assertIsFocused()
    }

    @Test fun aRememberedRowIsRestoredAfterSwitchingCategoriesAndScrolling() {
        showSettings()
        category("stream").performSemanticsAction(SemanticsActions.RequestFocus)
        category("stream").performKeyInput { pressKey(Key.DirectionRight) }
        // A lazy row outside the viewport is not composed yet. Reach it with real navigation,
        // exercising the production scroll-and-focus path rather than requesting a missing node.
        repeat(18) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        row("stream-18").assertIsFocused().assertIsDisplayed()
        row("stream-18").performKeyInput { pressKey(Key.DirectionLeft) }
        category("stream").assertIsFocused()
        category("stream").performKeyInput { pressKey(Key.DirectionDown) }
        category("input").assertIsFocused()
        category("input").performKeyInput { pressKey(Key.DirectionUp) }
        category("stream").performKeyInput { pressKey(Key.DirectionRight) }
        row("stream-18").assertIsFocused().assertIsDisplayed()
    }

    @Test fun anEmptyPaneDoesNotSendRightToAnUnrelatedControl() {
        showSettings()
        category("empty").performSemanticsAction(SemanticsActions.RequestFocus)
        category("empty").performKeyInput { pressKey(Key.DirectionRight) }
        category("empty").assertIsFocused()
    }

    @Test fun aShortWindowHidesTheRepeatedQuickStripAndRightEntersThePane() {
        showSettings(heightDp = 420)
        compose.onNodeWithTag("nova-settings-quick-quick-0").assertDoesNotExist()
        category("stream").performSemanticsAction(SemanticsActions.RequestFocus)
        category("stream").performKeyInput { pressKey(Key.DirectionRight) }
        row("stream-0").assertIsFocused()
        row("stream-0").performKeyInput { pressKey(Key.DirectionLeft) }
        category("stream").assertIsFocused()
    }

    @Test fun touchingAnotherCategoryHandsTheNextControllerMoveToItsPane() {
        showSettings(heightDp = 420)
        category("stream").performSemanticsAction(SemanticsActions.RequestFocus)
        touchCategory("input")
        category("input").assertIsSelected()
        row("input-1").assertExists()
        row("stream-0").assertDoesNotExist()
        shot("rail-touch-selected")
        controllerRight()
        shot("rail-touch-then-right")
        row("input-1").assertIsFocused()
        category("input").assertIsSelected()
        row("input-1").performKeyInput { pressKey(Key.DirectionLeft) }
        category("input").assertIsFocused()
    }

    @Test fun touchingACategoryFromThePanePreservesItsRememberedControllerRow() {
        showSettings(heightDp = 420)
        category("stream").performSemanticsAction(SemanticsActions.RequestFocus)
        category("stream").performKeyInput { pressKey(Key.DirectionRight) }
        repeat(3) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        row("stream-3").assertIsFocused()
        touchCategory("input")
        category("input").assertIsSelected()
        shot("pane-touch-selected")
        controllerRight()
        shot("pane-touch-then-right")
        row("input-1").assertIsFocused()
        touchCategory("stream")
        category("stream").assertIsSelected()
        controllerRight()
        row("stream-3").assertIsFocused()
        row("stream-3").performKeyInput { pressKey(Key.DirectionLeft) }
        category("stream").assertIsFocused()
    }

    private fun category(key: String) = compose.onNodeWithTag("nova-settings-category-$key")
    private fun row(key: String) = compose.onNodeWithTag("nova-settings-row-$key")

    private fun touchCategory(key: String) {
        val bounds = category(key).fetchSemanticsNode().boundsInRoot
        val origin = IntArray(2)
        compose.runOnIdle {
            compose.activity.findViewById<android.view.View>(android.R.id.content).getLocationOnScreen(origin)
        }
        val x = origin[0] + bounds.center.x
        val y = origin[1] + bounds.center.y
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        for ((action, delay) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 120L)) {
            if (delay > 0) SystemClock.sleep(delay)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Real touchscreen event injected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Real touchscreen entered touch mode", InputMode.Touch, inputMode.inputMode) }
    }

    private fun controllerRight() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Real D-pad entered keyboard mode", InputMode.Keyboard, inputMode.inputMode) }
    }

    private fun shot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "native-smoke/settings-handoff").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun showSettings(heightDp: Int? = null) {
        var selected by mutableStateOf("stream")
        val categories = listOf(
            NovaSettingCategory("stream", "Client Stream Defaults", "Stream settings"),
            NovaSettingCategory("input", "Input & Controllers", "Controller settings"),
            NovaSettingCategory("empty", "Empty category", "No rows"),
        )
        fun definition(category: String, index: Int) = NovaSettingDefinition(
            key = "$category-$index", title = "$category row $index", summary = "",
            categoryKey = category, type = NovaSettingType.Action,
            dependencyKey = if (category == "input" && index == 0) "disabled" else null,
        )
        val settings = (0..20).map { definition("stream", it) } + (0..4).map { definition("input", it) }
        val quick = (0..2).map { definition("quick", it) }
        compose.setContent {
            val wideConfig = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 900
                if (heightDp != null) screenHeightDp = heightDp
            }
            CompositionLocalProvider(LocalConfiguration provides wideConfig) {
                NovaComposeTheme {
                    inputMode = LocalInputModeManager.current
                    NovaSettingsContent(
                        state = NovaSettingsUiState(
                            categories, selected, "", 0, quick,
                            settings.filter { it.categoryKey == selected },
                            mapOf("disabled" to NovaSettingValue.BooleanValue(false)),
                        ),
                        title = "Settings", subtitle = "D-pad regression",
                        onBack = {}, onOpenLegacy = {}, onSearch = {}, onClearSearch = {},
                        onCategory = { selected = it }, headerActions = emptyList(),
                        onResetSetting = {}, onValue = { _, _, done -> done() }, onSetting = {},
                    )
                }
            }
        }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS + 32)
        compose.waitForIdle()
    }
}

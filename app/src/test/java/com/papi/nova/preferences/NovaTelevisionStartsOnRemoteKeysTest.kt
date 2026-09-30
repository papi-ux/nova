package com.papi.nova.preferences

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.ui.NovaLibraryActivity
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.panel.setPanelContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A Shield opened with its remote showed A, B, X and L1/R1 until the first D-pad press, although a
 * remote has none of them (C04). Settings and the Library start from the input the player has: a
 * connected gamepad names a controller's keys, on a television too, and without one a television
 * names its remote's. The last key decides after that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaTelevisionStartsOnRemoteKeysTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun television(on: Boolean) {
        shadowOf(rule.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, on)
    }

    /**
     * The input devices Android reports: always a remote, the Shield's, as a keyboard with a D-pad,
     * and with [gamepad] a controller too.
     */
    private fun devices(gamepad: Boolean) {
        val input = mock(InputManager::class.java)
        val remote = mock(InputDevice::class.java)
        `when`(remote.sources).thenReturn(InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD)
        val pad = mock(InputDevice::class.java)
        `when`(pad.sources).thenReturn(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)
        `when`(input.inputDeviceIds).thenReturn(if (gamepad) intArrayOf(3, 7) else intArrayOf(3))
        `when`(input.getInputDevice(3)).thenReturn(remote)
        `when`(input.getInputDevice(7)).thenReturn(pad)
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).setSystemService(Context.INPUT_SERVICE, input)
    }

    private val definitions = NovaSettingsDefinitionSet(
        listOf(NovaSettingCategory("stream", "Stream", "")),
        listOf(
            NovaSettingDefinition(
                key = "checkbox_enable_hdr", title = "HDR", summary = "", categoryKey = "stream",
                type = NovaSettingType.Toggle, defaultValue = NovaSettingValue.BooleanValue(false),
            ),
        ),
    )

    private fun settingsHints(): String {
        rule.setPanelContent {
            NovaSettingsContent(
                state = NovaSettingsUiStateFactory.build(definitions, emptyMap(), "stream", "", resettableKeys = setOf("checkbox_enable_hdr")),
                title = "Settings",
                subtitle = "",
                onBack = {},
                onOpenLegacy = {},
                onSearch = {},
                onClearSearch = {},
                onCategory = {},
                headerActions = emptyList(),
                onResetSetting = {},
                onValue = { _, _, done -> done() },
                onSetting = {},
            )
        }
        rule.mainClock.advanceTimeBy(NOVA_FIRST_FOCUS_SETTLE_MS + 32)
        rule.waitForIdle()
        return rule.onNode(hasContentDescription("Select", substring = true))
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
    }

    @Test
    fun onATelevisionSettingsNamesTheRemotesKeysBeforeAnyKey() {
        television(true)
        devices(gamepad = false)
        val hints = settingsHints()
        val ok = rule.activity.getString(R.string.nova_controller_hint_remote_center)
        assertTrue("OK first, before a key: $hints", hints.startsWith("$ok "))
        assertFalse("no X: $hints", hints.contains(rule.activity.getString(R.string.nova_controller_hint_x) + " "))
        assertFalse("no shoulders: $hints", hints.contains(rule.activity.getString(R.string.nova_controller_hint_lb_rb)))
    }

    @Test
    fun elsewhereSettingsStartsOnAControllersKeys() {
        television(false)
        devices(gamepad = false)
        val hints = settingsHints()
        assertTrue(hints, hints.startsWith(rule.activity.getString(R.string.nova_panel_key_a) + " "))
    }

    // A Shield played with a controller: the controller's keys, X and L1/R1 with them.
    @Test
    fun onATelevisionWithAControllerSettingsNamesTheControllersKeys() {
        television(true)
        devices(gamepad = true)
        val hints = settingsHints()
        assertTrue("A first: $hints", hints.startsWith(rule.activity.getString(R.string.nova_panel_key_a) + " "))
        assertTrue("X: $hints", hints.contains(rule.activity.getString(R.string.nova_controller_hint_x) + " "))
    }

    // A handheld's controls are a gamepad; with one or not, a handheld is never a remote first.
    @Test
    fun aHandheldStartsOnAControllersKeysWithOrWithoutAGamepad() {
        television(false)
        devices(gamepad = true)
        val hints = settingsHints()
        assertTrue(hints, hints.startsWith(rule.activity.getString(R.string.nova_panel_key_a) + " "))
        assertFalse(libraryStartsRemote())
    }

    // After the first hints, the last key decides: a remote's press on a television with a
    // controller turns them into the remote's.
    @Test
    fun theLastKeyDecidesAfterThat() {
        television(true)
        devices(gamepad = true)
        settingsHints()
        val now = SystemClock.uptimeMillis()
        val remoteKey = { action: Int ->
            KeyEvent(now, now, action, KeyEvent.KEYCODE_DPAD_DOWN, 0, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD)
        }
        rule.runOnUiThread {
            rule.activity.window.decorView.dispatchKeyEvent(remoteKey(KeyEvent.ACTION_DOWN))
            rule.activity.window.decorView.dispatchKeyEvent(remoteKey(KeyEvent.ACTION_UP))
        }
        rule.waitForIdle()
        val hints = rule.onNode(hasContentDescription("Select", substring = true))
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("OK first after a remote's key: $hints", hints.startsWith(rule.activity.getString(R.string.nova_controller_hint_remote_center) + " "))
    }

    @Test
    fun onATelevisionTheLibraryStartsOnTheRemotesKeysUnlessAControllerIsConnected() {
        television(true)
        devices(gamepad = false)
        assertTrue(libraryStartsRemote())
        devices(gamepad = true)
        assertFalse("a controller is connected", libraryStartsRemote())
        television(false)
        devices(gamepad = false)
        assertFalse(libraryStartsRemote())
    }

    // A hostless create leaves before loading anything, after the hint bar's input is set.
    private fun libraryStartsRemote(): Boolean {
        val controller = Robolectric.buildActivity(NovaLibraryActivity::class.java).create()
        try {
            @Suppress("UNCHECKED_CAST")
            val remote = NovaLibraryActivity::class.java.getDeclaredField("lastInputRemote\$delegate").run {
                isAccessible = true
                get(controller.get()) as MutableState<Boolean>
            }
            return remote.value
        } finally {
            controller.destroy()
        }
    }
}

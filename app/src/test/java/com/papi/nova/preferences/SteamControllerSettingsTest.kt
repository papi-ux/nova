package com.papi.nova.preferences

import android.content.Context
import android.content.pm.PackageManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.input.ControllerPacket
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [30], shadows = [ShadowMoonBridge::class])
@RunWith(RobolectricTestRunner::class)
class SteamControllerSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun bluetoothIsOptInOnShieldEraAndroidToo() {
        val config = PreferenceConfiguration.readPreferences(context, prefs)
        assertFalse(config.steamControllerBluetooth)
        assertFalse(config.steamControllerGameTouchpad)
        assertArrayEquals(IntArray(4), config.steamControllerGripMappings)
    }
    @Test fun mappingsHaveExplicitPositionsAndUnknownValuesCannotInventButtons() {
        prefs.edit().putString("steam_controller_trackpads", "game")
            .putBoolean("steam_controller_bluetooth", true)
            .putString("steam_controller_r4", "a").putString("steam_controller_l4", "lb")
            .putString("steam_controller_r5", "not-a-button").putString("steam_controller_l5", "rs").commit()
        val config = PreferenceConfiguration.readPreferences(context, prefs)
        assertTrue(config.steamControllerBluetooth); assertTrue(config.steamControllerGameTouchpad)
        assertArrayEquals(intArrayOf(ControllerPacket.A_FLAG, ControllerPacket.LB_FLAG, 0, ControllerPacket.RS_CLK_FLAG), config.steamControllerGripMappings)
    }
    @Test fun motionControlRemainsAvailableOnAndroid11AndConnectionPermissionIsGlobal() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        val all = NovaSettingDefinitions.load(context)
        val visible = NovaSettingsAvailability.filter(context, all)
        assertTrue(visible.settings.any { it.key == "checkbox_gamepad_motion_sensors" })
        assertTrue(visible.settings.any { it.key == "steam_controller_bluetooth" })
        assertFalse(visible.settings.any { it.key == "steam_controller_permission" })
        assertFalse(NovaSettingsAvailability.filterForProfileEditor(all).settings.any { it.key == "steam_controller_bluetooth" })
    }
    @Test fun bothSettingsScreensReadTheSameChoicesAndNextStreamTiming() {
        val all = NovaSettingDefinitions.load(context)
        val pads = all.settings.single { it.key == "steam_controller_trackpads" }
        assertEquals(listOf("mouse", "game"), pads.options.map { it.value })
        assertEquals(NovaSettingApplyTiming.NextStream, pads.applyTiming)
        assertEquals(listOf("off", "a", "b", "x", "y", "lb", "rb", "ls", "rs"),
            all.settings.single { it.key == "steam_controller_l4" }.options.map { it.value })
    }
}

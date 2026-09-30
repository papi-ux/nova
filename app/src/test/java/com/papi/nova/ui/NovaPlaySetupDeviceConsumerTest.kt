package com.papi.nova.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.ViewModelStore
import androidx.preference.PreferenceManager
import com.papi.nova.preferences.*
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.ui.panel.*
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Render the production row projection, and use its callbacks through the real VM and disk store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaPlaySetupDeviceConsumerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    @After fun stop() { models.clear(); NovaTierRuntime.installForTest(null) }
    @Test fun everyGameQualityChangesTheDeviceTierAndKeepsTheCustomPin() {
        ProfilesManager.instance = null
        NovaTierRuntime.installForTest(NovaTierInputs(NovaSize(1920,1080), listOf(60,120), NovaDistance.HAND,
            NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",listOf(
                NovaDecodePoint(NovaSize(1920,1080),120),NovaDecodePoint(NovaSize(3840,2160),60)))))))
        val prefs = PreferenceManager.getDefaultSharedPreferences(rule.activity)
        prefs.edit().clear().putString(NovaSettingsMigration.TIER,"custom")
            .putString("list_resolution","1920x1080").putString("list_fps","60")
            .putInt("seekbar_bitrate_kbps",201124).putBoolean(NovaSettingsMigration.CUSTOM_AUTO,false).commit()
        lateinit var model: NovaSettingsViewModel
        rule.runOnIdle {
            model = NovaSettingsViewModel(NovaSettingDefinitions.load(rule.activity), NovaSettingsRepository.createForTest(
                rule.activity,prefs,File(rule.activity.filesDir,"play-${UUID.randomUUID()}.preferences_pb")),
                initialCategoryKey="category_stream_quality")
            models.put("device", model)
        }
        val deadline = System.nanoTime()+5_000_000_000L
        while (model.streamTiers.value==null && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertNotNull(model.streamTiers.value)
        val rows = buildNovaDevicePlaySetupRows(model.uiState.value) { definition,value -> model.setValue(definition,value) }
        assertTrue("the real device settings have six rows", rows.size>=6)
        val quality = rows.first { it.label=="Quality" }
        var writes = 0
        val keys = rule.setPanelContent {
            val state by model.uiState.collectAsState()
            Column {
                buildNovaDevicePlaySetupRows(state) { definition,value -> writes++; model.setValue(definition,value) }.forEach { row ->
                    NovaPlaySetupSettingRow(row, {}, Modifier.testTag("device-${row.label}"))
                }
            }
        }
        rule.frames(8)
        rule.onNodeWithTag("device-Quality").requestFocus()
        keys.press(NovaTestKeys.LEFT); rule.frames(10)
        assertEquals("Left reached the actual device-setting callback",1,writes)
        val savedBy = System.nanoTime()+5_000_000_000L
        while (model.pictureTier==NovaTier.CUSTOM && System.nanoTime()<savedBy) {
            shadowOf(Looper.getMainLooper()).idle();rule.frames(1);Thread.sleep(5)
        }
        assertNotEquals(NovaTier.CUSTOM,model.pictureTier)
        assertEquals(201124,prefs.getInt("seekbar_bitrate_kbps",0))
        assertFalse(prefs.getBoolean(NovaSettingsMigration.CUSTOM_AUTO,true))
        assertFalse(prefs.contains("nova_stream_preset"))
        assertEquals("Custom",quality.value)
    }
}

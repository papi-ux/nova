package com.papi.nova.preferences

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.preference.PreferenceManager
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.frames
import com.papi.nova.ui.panel.setPanelContent
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real screen callbacks, ViewModel and disk-backed repository; no test-only onValue reducer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaTierSettingsComposeTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val models=ViewModelStore()
    @After fun clear() { models.clear(); NovaTierRuntime.installForTest(null) }
    private fun show(): Pair<NovaTestKeys,NovaSettingsViewModel> {
        val context=rule.activity
        ProfilesManager.instance=null
        NovaTierRuntime.installForTest(NovaTierInputs(NovaSize(1920,1080),listOf(60,120),NovaDistance.HAND,
            NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",listOf(
                NovaDecodePoint(NovaSize(1920,1080),120),NovaDecodePoint(NovaSize(3840,2160),60)))))))
        val prefs=PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().putString(NovaSettingsMigration.TIER,"custom")
            .putString("list_resolution","1920x1080").putString("list_fps","60")
            .putInt("seekbar_bitrate_kbps",201124).putBoolean(NovaSettingsMigration.CUSTOM_AUTO,false).commit()
        lateinit var vm:NovaSettingsViewModel
        rule.runOnIdle {
            vm=NovaSettingsViewModel(NovaSettingDefinitions.load(context),NovaSettingsRepository.createForTest(
                context,prefs,File(context.filesDir,"compose-${UUID.randomUUID()}.preferences_pb")),
                initialCategoryKey="category_stream_quality")
            models.put("settings",vm)
        }
        val end=System.nanoTime()+5_000_000_000L
        while(vm.streamTiers.value==null && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertNotNull(vm.streamTiers.value)
        val keys=rule.setPanelContent {
            NovaSettingsScreen(vm,"Settings","",{}, {}, {},returnToRow="nova_stream_preset")
        }
        rule.frames(10)
        return keys to vm
    }
    private fun awaitRecommended(vm:NovaSettingsViewModel) {
        val deadline=System.nanoTime()+5_000_000_000L
        while(vm.pictureTier!=NovaTier.RECOMMENDED && System.nanoTime()<deadline) {
            shadowOf(Looper.getMainLooper()).idle();rule.frames(1);Thread.sleep(5)
        }
        assertEquals(NovaTier.RECOMMENDED,vm.pictureTier)
        rule.frames(4)
    }
    @Test fun aThenAChoosesRecommendedWhileTheOriginalCustomPinSurvives() {
        val (keys,vm)=show()
        val prefs=PreferenceManager.getDefaultSharedPreferences(rule.activity)
        rule.onNodeWithTag("nova-settings-row-nova_stream_preset").assertIsFocused()
        keys.press(NovaTestKeys.CENTER);rule.frames(8)
        rule.onNodeWithText("Recommended",substring=false).assertIsFocused()
        assertEquals("custom",prefs.getString(NovaSettingsMigration.TIER,null))
        keys.press(NovaTestKeys.CENTER);rule.frames(8)
        awaitRecommended(vm)
        assertEquals(201124,prefs.getInt("seekbar_bitrate_kbps",0))
        assertFalse(prefs.getBoolean(NovaSettingsMigration.CUSTOM_AUTO,true))
        rule.onNodeWithTag("nova-settings-row-nova_stream_preset").assertIsFocused()
        assertFalse(prefs.contains("nova_stream_preset"))
    }
    @Test fun xOnQualityUsesRecommendedAndBackKeepsThePaneRow() {
        val (keys,vm)=show()
        keys.press(android.view.KeyEvent.KEYCODE_BUTTON_X);rule.frames(8)
        awaitRecommended(vm)
        rule.onNodeWithTag("nova-settings-row-nova_stream_preset").assertIsFocused()
        keys.press(NovaTestKeys.CENTER);rule.frames(8)
        keys.back();rule.frames(8)
        rule.onNodeWithTag("nova-settings-row-nova_stream_preset").assertIsFocused()
    }
}

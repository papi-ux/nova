package com.papi.nova.preferences

import android.content.Context
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaStreamSettingsIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    @Before fun clear() {
        prefs.edit().clear().commit()
        context.getSharedPreferences(NovaCapabilityProbe.STORE,0).edit().clear().commit()
        ProfilesManager.instance = null
    }

    private class FixtureStore(initial: Map<String, NovaSettingValue>) : NovaSettingsStore {
        var values = initial
        override suspend fun storedStreamKeys() = values.keys
        override suspend fun snapshot(definitions: NovaSettingsDefinitionSet) = definitions.settings.mapNotNull { d -> d.defaultValue?.let { d.key to it } }.toMap() + values
        override suspend fun set(definition: NovaSettingDefinition, value: NovaSettingValue) { values = values + (definition.key to value) }
        override suspend fun updateAtomically(updates: List<Pair<NovaSettingDefinition,NovaSettingValue>>, removeKeys: Set<String>) {
            values = (values - removeKeys) + updates.associate { it.first.key to it.second }
        }
        override suspend fun reset(definition: NovaSettingDefinition) { values = values - definition.key }
        override suspend fun overrideKeys(definitions: NovaSettingsDefinitionSet) = emptySet<String>()
        override suspend fun resettableKeys(definitions: NovaSettingsDefinitionSet) = emptySet<String>()
        override suspend fun deviceTierInputs() = NovaTierInputs(NovaSize(1920,1080),listOf(60,120),NovaDistance.HAND,
            NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture", listOf(
                NovaDecodePoint(NovaSize(1280,720),120),NovaDecodePoint(NovaSize(1920,1080),120),
                NovaDecodePoint(NovaSize(3840,2160),60))))))
    }
    private fun defaults() = mapOf(NovaSettingsMigration.TIER to NovaSettingValue.StringValue("recommended"),
        NovaSettingsMigration.AUTO to NovaSettingValue.BooleanValue(true),
        NovaSettingsMigration.CUSTOM_EXISTS to NovaSettingValue.BooleanValue(false))
    private fun awaitUi(done: () -> Boolean) {
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while(!done() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertTrue("Asynchronous settings operation did not finish",done())
    }
    private fun edit(vm:NovaSettingsViewModel,definition:NovaSettingDefinition,value:NovaSettingValue) {
        var done=false
        vm.setValue(definition,value) { done=true }
        awaitUi { done }
    }

    @Test fun freshHasNoCustomAndAutoFollowsEditsUntilBitrateIsMoved() {
        val store = FixtureStore(defaults())
        val definitions = NovaSettingDefinitions.load(context)
        val vm = NovaSettingsViewModel(definitions,store); awaitUi { vm.streamTiers.value!=null }
        assertNull(vm.streamTiers.value!!.custom)
        assertEquals("Auto · 30 Mbps",vm.bitrateText)
        edit(vm,definitions.require("list_fps"),NovaSettingValue.StringValue("60"))
        assertEquals(NovaTier.CUSTOM,vm.pictureTier)
        assertEquals(NovaSettingValue.IntValue(20000),store.values["seekbar_bitrate_kbps"])
        edit(vm,definitions.require("seekbar_bitrate_kbps"),NovaSettingValue.IntValue(37000))
        edit(vm,definitions.require("list_resolution"),NovaSettingValue.StringValue("1280x720"))
        assertEquals(NovaSettingValue.IntValue(37000),store.values["seekbar_bitrate_kbps"])
        assertEquals("37 Mbps",vm.bitrateText)
        edit(vm,NovaStreamSettings.definition(NovaSettingsMigration.AUTO)!!,NovaSettingValue.BooleanValue(true))
        assertEquals(NovaSettingValue.IntValue(10000),store.values["seekbar_bitrate_kbps"])
    }

    @Test fun tierSwitchAndStandardEditsKeepDiyEditorValuesSeparate() {
        val custom = defaults() + mapOf("list_resolution" to NovaSettingValue.StringValue("1920x1080"),
            "list_fps" to NovaSettingValue.StringValue("60"),"video_format" to NovaSettingValue.StringValue("forceh265"),
            "seekbar_bitrate_kbps" to NovaSettingValue.IntValue(47000),"edit_diy_w_h" to NovaSettingValue.StringValue("2400x1080"),
            "custom_refresh_rate" to NovaSettingValue.StringValue("90"))
        val store = FixtureStore(custom)
        val defs = NovaSettingDefinitions.load(context)
        val vm = NovaSettingsViewModel(defs,store);awaitUi { vm.streamTiers.value!=null }
        edit(vm,NovaStreamSettings.definition(NovaSettingsMigration.TIER)!!,NovaSettingValue.StringValue("max"))
        for (key in NovaSettingsMigration.STREAM_KEYS) assertEquals(custom[key],store.values[key])
        edit(vm,defs.require("list_resolution"),NovaSettingValue.StringValue("1280x720"))
        assertEquals(custom["edit_diy_w_h"],store.values["edit_diy_w_h"])
        assertEquals(custom["custom_refresh_rate"],store.values["custom_refresh_rate"])
        assertEquals(NovaSettingValue.IntValue(10000),store.values["seekbar_bitrate_kbps"])
    }

    @Test fun thirdCrashResetRecordsActualPointAndRetainsCustomStore() {
        prefs.edit().putString("list_resolution","3840x2160").putString("list_fps","60")
            .putInt("seekbar_bitrate_kbps",87000).putString("video_format","forceh265")
            .putString("edit_diy_w_h","3840x2160").putString("custom_refresh_rate","60").commit()
        NovaSettingsMigration.apply(context)
        val before = prefs.all.filterKeys { it in NovaSettingsMigration.STREAM_KEYS }
        NovaCapabilityProbe.recordCrashCandidate(context,NovaFailedDecodePoint(NovaCodecChoice.HEVC,NovaSize(3840,2160),60))
        PreferenceConfiguration.resetStreamingSettings(context)
        assertEquals(before,prefs.all.filterKeys { it in NovaSettingsMigration.STREAM_KEYS })
        assertEquals("recommended",prefs.getString(NovaSettingsMigration.TIER,null))
        val failures = JSONObject(context.getSharedPreferences(NovaCapabilityProbe.STORE,0).getString("failures","")!!)
            .getJSONArray("points")
        assertEquals("HEVC",failures.getJSONObject(0).getString("codec"))
        assertEquals(3840,failures.getJSONObject(0).getInt("width"))
        assertEquals(60,failures.getJSONObject(0).getInt("fps"))
    }

    @Test fun classicAndSavedSetupWritesWinOverGeneratedTierWithoutChangingBase() {
        NovaSettingsMigration.apply(context)
        NovaStreamSettings.writeManualBitrate(prefs,37000)
        NovaStreamSettings.classicWrite(prefs,"seekbar_bitrate_kbps")
        assertEquals("custom",prefs.getString(NovaSettingsMigration.TIER,null))
        assertFalse(prefs.getBoolean(NovaSettingsMigration.AUTO,true))
        NovaStreamSettings.select(context,NovaTier.RECOMMENDED)
        val profile = SettingsProfile(UUID.randomUUID(),"Stream fixture",0,0,mapOf("list_fps" to "90"))
        val manager = ProfilesManager.getInstance();manager.add(profile);manager.setActive(profile.getUuid())
        assertEquals("custom",manager.getOverlayingSharedPreferences(context).getString(NovaSettingsMigration.TIER,null))
        assertEquals("recommended",prefs.getString(NovaSettingsMigration.TIER,null))
    }
    @Test fun capabilityCacheReusesOnlyMatchingFingerprintVersionAndDecodersAndKeepsFailuresFresh() {
        val cache = context.getSharedPreferences(NovaCapabilityProbe.STORE,0)
        val point = NovaDecodePoint(NovaSize(1920,1080),120)
        val codec = NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",listOf(point))
        var calls=0
        val query = { calls++;listOf(codec) }
        assertEquals(listOf(codec),NovaCapabilityProbe.cachedCapabilities(cache,"os1:v1:fixture",emptyList(),query).codecs)
        val failure = NovaFailedDecodePoint(NovaCodecChoice.HEVC,NovaSize(1920,1080),120)
        val cached = NovaCapabilityProbe.cachedCapabilities(cache,"os1:v1:fixture",listOf(failure),query)
        assertEquals(1,calls)
        assertEquals(listOf(failure),cached.failed)
        NovaCapabilityProbe.cachedCapabilities(cache,"os2:v1:fixture",emptyList(),query)
        NovaCapabilityProbe.cachedCapabilities(cache,"os2:v2:fixture",emptyList(),query)
        NovaCapabilityProbe.cachedCapabilities(cache,"os2:v2:replacement",emptyList(),query)
        assertEquals(4,calls)
        cache.edit().putString("points","broken").commit()
        NovaCapabilityProbe.cachedCapabilities(cache,"os2:v2:replacement",emptyList(),query)
        assertEquals(5,calls)
    }

    @Test fun decoderResetAlsoChangesAnActiveSavedSetupWithoutWipingItsCustomValues() {
        val custom = mapOf<String,Any>("list_resolution" to "3840x2160", "list_fps" to "60",
            "seekbar_bitrate_kbps" to 87000, "video_format" to "forceh265",
            "edit_diy_w_h" to "3840x2160", "custom_refresh_rate" to "60")
        val manager=ProfilesManager.getInstance();manager.load(context)
        val profile=SettingsProfile(UUID.randomUUID(),"Crash fixture",0,0,custom)
        manager.add(profile);manager.setActive(profile.getUuid())
        NovaCapabilityProbe.recordCrashCandidate(context,NovaFailedDecodePoint(NovaCodecChoice.HEVC,NovaSize(3840,2160),60))
        PreferenceConfiguration.resetStreamingSettings(context)
        assertEquals("recommended",manager.getOverlayingSharedPreferences(context).getString(NovaSettingsMigration.TIER,null))
        custom.forEach { (key,value) -> assertEquals(value,profile.getOptions()!![key]) }
        manager.awaitDeferredWritesForTest()
        ProfilesManager.instance=null
        val restored=ProfilesManager.getInstance();restored.load(context)
        assertEquals("recommended",restored.getOverlayingSharedPreferences(context).getString(NovaSettingsMigration.TIER,null))
        NovaStreamSettings.select(context,NovaTier.CUSTOM)
        assertEquals("custom",restored.getOverlayingSharedPreferences(context).getString(NovaSettingsMigration.TIER,null))
        custom.forEach { (key,value) ->
            val actual=restored.getActive()!!.getOptions()!![key]
            if (value is Number) assertEquals(value.toInt(),(actual as Number).toInt()) else assertEquals(value,actual)
        }
    }

}

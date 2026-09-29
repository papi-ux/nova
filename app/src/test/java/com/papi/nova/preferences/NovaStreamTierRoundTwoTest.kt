package com.papi.nova.preferences

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.manager.NovaTierLaunchPolicy
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import com.papi.nova.shadows.ShadowMoonBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,shadows=[ShadowMoonBridge::class])
class NovaStreamTierRoundTwoTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val prefs get()=PreferenceManager.getDefaultSharedPreferences(context)
    @Before fun reset() { prefs.edit().clear().commit();ProfilesManager.instance=null;NovaTierRuntime.installForTest(null) }
    private fun mode(id:Int,w:Int,h:Int,hz:Float):Display.Mode = ReflectionHelpers.callStaticMethod(
        ShadowDisplayManager::class.java,"displayModeOf",from(Int::class.javaPrimitiveType,id),
        from(Int::class.javaPrimitiveType,w),from(Int::class.javaPrimitiveType,h),from(Float::class.javaPrimitiveType,hz))
    private fun panel(vararg modes:Display.Mode,room:Boolean=false):NovaTierInputs {
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY,*modes)
        shadowOf(context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
            .setCurrentModeType(if(room) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL)
        return NovaCapabilityProbe.deviceInputs(context)
    }
    private fun capable(input:NovaTierInputs)=input.copy(capabilities=NovaDeviceCapabilities(listOf(
        NovaCodecCapability(NovaCodecChoice.HEVC,"fixture-hevc",listOf(
            NovaDecodePoint(NovaSize(3840,2160),60),NovaDecodePoint(NovaSize(2560,1440),120))))))
    private fun room(link:NovaLink=NovaLink.WIFI)=capable(NovaTierInputs(NovaSize(3840,2160),listOf(60),NovaDistance.ROOM,
        NovaDeviceCapabilities(emptyList()),link))
    private fun awaitUi(done:()->Boolean) {
        val end=System.nanoTime()+5_000_000_000
        while(!done() && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertTrue("Settings operation did not complete",done())
    }
    @Test fun resumeKeepsExactModeWhileShortcutAndSpaceStayUnlocked() {
        assertTrue(NovaTierLaunchPolicy.sessionModeLocked(false,true,1280,720))
        assertTrue(NovaTierLaunchPolicy.sessionModeLocked(true,false,1920,1080))
        assertFalse(NovaTierLaunchPolicy.sessionModeLocked(false,false,1920,1080))
        assertFalse(NovaTierLaunchPolicy.sessionModeLocked(false,true,0,0))
        assertFalse(NovaTierLaunchPolicy.displayLocked(NovaTierLaunchPolicy.sessionModeLocked(false,true,1280,720),true))
    }
    @Test fun resumeAndSpaceAreNotRefusedBecauseADeviceTierIsUnavailable() {
        assertFalse(NovaTierLaunchPolicy.needsGeneratedTier(NovaTier.RECOMMENDED,false,true,false))
        assertFalse(NovaTierLaunchPolicy.needsGeneratedTier(NovaTier.RECOMMENDED,false,false,true))
        assertTrue(NovaTierLaunchPolicy.needsGeneratedTier(NovaTier.RECOMMENDED,false,false,false))
    }
    @Test fun s20ModesKeepFullRefreshAtTheHandheldCandidateSize() {
        val inputs=panel(mode(0,2400,1080,120f),mode(2,2400,1080,60f),mode(3,3200,1440,60f))
        val tiers=NovaStreamTiers.forDevice(capable(inputs))
        assertEquals(NovaSize(3200,1440),inputs.panel)
        assertEquals(NovaSize(2400,1080),tiers.recommended.size)
        assertEquals(120,tiers.recommended.fps)
    }
    @Test fun dciModeDoesNotTurnAnUhdTvIntoATwentyFourHzPanel() {
        val inputs=panel(mode(0,3840,2160,60f),mode(2,4096,2160,24f),mode(3,1920,1080,120f),room=true)
        val tiers=NovaStreamTiers.forDevice(capable(inputs).copy(link=NovaLink.ETHERNET))
        assertEquals(NovaSize(3840,2160),inputs.panel)
        assertEquals(60,tiers.recommended.fps)
        assertTrue(tiers.mergedMax)
    }
    @Test fun shieldsReportedModesOffer1080p120AndFourK60() {
        var id=0
        val modes=(listOf(59.94f,23.976f,24f,25f,29.97f,30f,50f,60f).map { mode(id++,3840,2160,it) } +
            listOf(23.976f,24f,29.97f,30f,50f,59.94f,60f,120f).map { mode(id++,1920,1080,it) } +
            listOf(50f,59.94f,60f).map { mode(id++,1280,720,it) }+mode(id++,720,480,60f)).toTypedArray()
        val inputs=capable(panel(*modes,room=true)).copy(link=NovaLink.WIFI)
        val tiers=NovaStreamTiers.forDevice(inputs)
        assertEquals(60,tiers.max.fps)
        val pinned=NovaStreamTiers.resolve(inputs,NovaTier.RECOMMENDED,pins=NovaStreamPins(size=NovaSize(1920,1080),fps=120))
        assertEquals(120,pinned.fps)
        assertEquals(NovaSize(1920,1080),pinned.size)
    }
    @Test fun heldWifiNamesFourKAsMaxAndExplicitBitratePinWins() {
        assertEquals(NovaFourK.IsMax,NovaStreamTiers.forDevice(room()).fourK)
        assertEquals(NovaFourK.IsRecommended,NovaStreamTiers.forDevice(room(NovaLink.ETHERNET)).fourK)
        val pinned=NovaStreamTiers.resolve(room(),NovaTier.RECOMMENDED,pins=NovaStreamPins(bitrateKbps=80000))
        assertEquals(80000,pinned.bitrateKbps)
        assertFalse(pinned.limits.any { it.code=="wifi_hold" })
    }
    @Test fun unchangedInvalidationKeepsThePublishedSnapshot()=runBlocking {
        val first=NovaTierRuntime.prepare(context)
        NovaTierRuntime.invalidate(context)
        assertTrue("An unchanged callback must not invalidate an already usable plan",NovaTierRuntime.isPrepared())
        val second=NovaTierRuntime.prepare(context)
        assertSame(first,second)
    }
    @Test fun profileEditorSaveRetainsAutomaticRecomputedBitrate() {
        val profile=SettingsProfile(UUID.randomUUID(),"Couch",0,0,mapOf("list_resolution" to "2560x1440"))
        val memory=context.getSharedPreferences("profile-editor",0)
        NovaSettingsMigration.writeDifference(memory,profile.getOptions()!!)
        val defs=NovaSettingDefinitions.load(context)
        val vm=NovaSettingsViewModel(defs,NovaSharedPreferencesSettingsStore(memory))
        awaitUi { vm.bitrateText.isNotEmpty() }
        var done=false
        vm.setValue(defs.require("list_fps"),NovaSettingValue.StringValue("120")) { done=true }
        awaitUi { done }
        assertTrue(memory.getBoolean(NovaSettingsMigration.CUSTOM_AUTO,false))
        profile.setOptions(memory.all.filterValues { it!=null }.mapValues { it.value!! })
        assertEquals(true,profile.getOptions()!![NovaSettingsMigration.CUSTOM_AUTO])
        assertEquals(60000,profile.getOptions()!!["seekbar_bitrate_kbps"])
    }
    @Test fun explicitTierInEditorWinsOverItsChangedCustomValues() {
        val profile=SettingsProfile(UUID.randomUUID(),"Couch",0,0,mapOf("list_resolution" to "1920x1080"))
        profile.setOptions(profile.getOptions()!! + mapOf("list_fps" to "120",NovaSettingsMigration.TIER to "max"))
        assertEquals("max",profile.getOptions()!![NovaSettingsMigration.TIER])
    }
    @Test fun modeOnlyHostImportRecomputesAutoFromTheNewPoint() {
        for(recommended in listOf(false,true)) {
            prefs.edit().clear().putString("list_resolution","1920x1080").putString("list_fps","60").commit()
            NovaSettingsMigration.apply(context)
            NovaTierRuntime.installForTest(capable(room().copy(panel=NovaSize(1920,1080),refreshRates=listOf(60,120),distance=NovaDistance.HAND)))
            if(recommended) NovaStreamSettings.select(context,NovaTier.RECOMMENDED)
            assertTrue(PreferenceConfiguration.applyPolarisStreamingProfile(context,"3840x2160x60",0))
            val actual=PreferenceConfiguration.readPreferences(context)
            assertEquals(3840,actual.width);assertEquals(60f,actual.fps,0f)
            assertEquals(80000,actual.bitrate)
            assertTrue(prefs.getBoolean(NovaSettingsMigration.CUSTOM_AUTO,false))
        }
    }
    @Test fun classicResolutionChangeWritesBitrateBeforeSizeAndKeepsAuto() {
        prefs.edit().putString("list_resolution","1920x1080").putString("list_fps","60").putInt("seekbar_bitrate_kbps",20000).commit()
        NovaSettingsMigration.apply(context)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { p,k -> NovaStreamSettings.classicWrite(p,k) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            val fragment=StreamSettings.SettingsFragment()
            val reset=fragment.javaClass.getDeclaredMethod("resetBitrateToDefault",SharedPreferences::class.java,String::class.java,String::class.java)
            reset.isAccessible=true;reset.invoke(fragment,prefs,"2560x1440",null)
            prefs.edit().putString("list_resolution","2560x1440").apply()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(40000,prefs.getInt("seekbar_bitrate_kbps",0))
            assertTrue(prefs.getBoolean(NovaSettingsMigration.CUSTOM_AUTO,false))
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    @Test fun measuredAvcWinsOverClaimedOnlyHevcInAuto() {
        val input=room(NovaLink.ETHERNET).copy(capabilities=NovaDeviceCapabilities(listOf(
            NovaCodecCapability(NovaCodecChoice.HEVC,"claimed",listOf(NovaDecodePoint(NovaSize(3840,2160),60,false))),
            NovaCodecCapability(NovaCodecChoice.AVC,"measured",listOf(NovaDecodePoint(NovaSize(1920,1080),60))))))
        val plan=NovaStreamTiers.forDevice(input).recommended
        assertEquals(NovaCodecChoice.AVC,plan.codec)
        assertEquals(NovaSize(1920,1080),plan.size)
        assertFalse(plan.reasons.any { it.code=="decoder_claimed" })
    }
    @Test fun hostPyrowaveAdviceIsScopedToItsSizeAndRateAndHighCapAllowsFourK() {
        val input=room(NovaLink.ETHERNET).copy(codec=NovaCodecChoice.PYROWAVE,
            pyrowave=NovaPyrowaveSupport(available=true),host=NovaHostTierLimits(pyrowaveRaiseGoalKbps=250000,
                pyrowaveFourKCapKbps=300000,pyrowaveAdviceSize=NovaSize(3840,2160),pyrowaveAdviceFps=60))
        val tiers=NovaStreamTiers.generate(input)
        assertEquals(250000,tiers.recommended.bitrateKbps)
        assertEquals(NovaBitrateBasis.PYROWAVE_MODEL,tiers.saver.bitrateBasis)
        assertTrue(tiers.max.available)
        assertEquals(NovaSize(3840,2160),tiers.max.size)
    }
    @Test fun probeFailurePublishesAnExplicitRetryableResult()=runBlocking {
        val bad=object:android.content.ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getSystemService(name:String):Any? = throw IllegalStateException("fixture failure")
        }
        val result=NovaTierRuntime.prepare(bad)
        assertFalse(result.tiers.recommended.available)
        assertEquals("probe_failed",result.tiers.recommended.limits.single().code)
        NovaTierRuntime.invalidate(context)
        assertNotEquals("failed",NovaTierRuntime.prepare(context).tiers.inputsHash)
    }

    @Test fun earlierBetaSchemaRepairsOnlyMissingCustomMetadata() {
        val old=mapOf(NovaSettingsMigration.SCHEMA to 2,NovaSettingsMigration.TIER to "custom",
            NovaSettingsMigration.AUTO to false,"list_resolution" to "2560x1440","seekbar_bitrate_kbps" to 37000)
        val migrated=NovaSettingsMigration.migrate(old)
        assertEquals(old,migrated.filterKeys { it in old.keys } + (NovaSettingsMigration.SCHEMA to 2))
        assertEquals(false,migrated[NovaSettingsMigration.CUSTOM_AUTO])
        assertEquals(true,migrated[NovaSettingsMigration.CUSTOM_EXISTS])
        assertEquals(3,migrated[NovaSettingsMigration.SCHEMA])
        assertEquals(migrated,NovaSettingsMigration.migrate(migrated))
    }

    @Test fun sizePinKeepsAutoCodecSelectionInsteadOfPinningTheOriginalDecoder() {
        val input=room(NovaLink.ETHERNET).copy(panel=NovaSize(1920,1080),refreshRates=listOf(60,120),distance=NovaDistance.HAND,
            capabilities=NovaDeviceCapabilities(listOf(
                NovaCodecCapability(NovaCodecChoice.HEVC,"hevc",listOf(NovaDecodePoint(NovaSize(1920,1080),120))),
                NovaCodecCapability(NovaCodecChoice.AVC,"avc",listOf(NovaDecodePoint(NovaSize(2560,1440),120))))))
        assertEquals(NovaCodecChoice.HEVC,NovaStreamTiers.forDevice(input).recommended.codec)
        val pinned=NovaStreamTiers.resolve(input,NovaTier.RECOMMENDED,pins=NovaStreamPins(size=NovaSize(2560,1440)))
        assertEquals(NovaCodecChoice.AVC,pinned.codec)
        assertEquals(NovaSize(2560,1440),pinned.size)
        assertEquals(120,pinned.fps)
    }

}

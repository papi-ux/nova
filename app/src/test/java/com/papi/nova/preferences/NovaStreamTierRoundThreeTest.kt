package com.papi.nova.preferences

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.EditProfileActivity
import com.papi.nova.api.*
import com.papi.nova.manager.*
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import com.papi.nova.shadows.ShadowMoonBridge
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,shadows=[ShadowMoonBridge::class])
class NovaStreamTierRoundThreeTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val prefs get()=PreferenceManager.getDefaultSharedPreferences(context)
    @Before fun reset() { prefs.edit().clear().commit();ProfilesManager.instance=null;NovaTierRuntime.installForTest(null) }
    @After fun clean() { NovaTierRuntime.installForTest(null);ProfilesManager.instance=null }
    private fun mode(id:Int,w:Int,h:Int,hz:Float):Display.Mode=ReflectionHelpers.callStaticMethod(
        ShadowDisplayManager::class.java,"displayModeOf",from(Int::class.javaPrimitiveType,id),
        from(Int::class.javaPrimitiveType,w),from(Int::class.javaPrimitiveType,h),from(Float::class.javaPrimitiveType,hz))
    private fun resetRate(fragment:StreamSettings.SettingsFragment,prefs:android.content.SharedPreferences,res:String?,fps:String?) {
        StreamSettings.SettingsFragment::class.java.getDeclaredMethod("resetBitrateToDefault",
            android.content.SharedPreferences::class.java,String::class.java,String::class.java)
            .apply { isAccessible=true }.invoke(fragment,prefs,res,fps)
    }
    private fun edit(old:Map<String,Any>, block:(EditProfileActivity,StreamSettings.SettingsFragment,SettingsProfile)->Unit) {
        prefs.edit().putString("list_resolution","2560x1440").putString("list_fps","90").commit()
        NovaSettingsFeatureFlags.setComposeSettingsEnabled(context,false)
        val profile=SettingsProfile(UUID.randomUUID(),"Partial",0,0,old)
        ProfilesManager.getInstance().add(profile)
        val activity=Robolectric.buildActivity(EditProfileActivity::class.java,
            Intent(context,EditProfileActivity::class.java).putExtra("profileUuid",profile.getUuid().toString())).setup()
        try {
            val host=activity.get()
            val fragment=host.supportFragmentManager.fragments.filterIsInstance<StreamSettings.SettingsFragment>().single()
            // Keep only the setup's actual overrides, as a sparse imported setup does.
            host.getInMemoryPrefs().edit().clear().commit()
            NovaSettingsMigration.writeDifference(host.getInMemoryPrefs(),profile.getOptions()!!)
            block(host,fragment,profile)
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun classicFpsChangeDoesNotPinInheritedResolutionAndUsesEffectiveBase() {
        edit(mapOf("list_fps" to "60")) { activity,fragment,profile ->
            val memory=activity.getInMemoryPrefs()
            resetRate(fragment,memory,null,"120")
            profile.setOptions(memory.all.mapValues { it.value!! })
            assertFalse(profile.getOptions()!!.containsKey("list_resolution"))
            assertEquals("120",profile.getOptions()!!["list_fps"])
            assertEquals(PreferenceConfiguration.getDefaultBitrate("2560x1440","120"),profile.getOptions()!!["seekbar_bitrate_kbps"])
        }
    }
    @Test fun classicSizeChangeDoesNotPinInheritedFps() {
        edit(mapOf("list_resolution" to "1280x720")) { activity,fragment,profile ->
            val memory=activity.getInMemoryPrefs();resetRate(fragment,memory,"1920x1080",null)
            profile.setOptions(memory.all.mapValues { it.value!! })
            assertFalse(profile.getOptions()!!.containsKey("list_fps"))
            assertEquals(PreferenceConfiguration.getDefaultBitrate("1920x1080","90"),profile.getOptions()!!["seekbar_bitrate_kbps"])
        }
    }
    @Test fun classicManualBitrateAfterFpsEditClearsAutoForBothWriters() {
        for(key in listOf(PreferenceConfiguration.BITRATE_PREF_STRING,PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING)) {
            edit(mapOf("list_fps" to "60",NovaSettingsMigration.CUSTOM_AUTO to true)) { activity,fragment,profile ->
                val memory=activity.getInMemoryPrefs();resetRate(fragment,memory,null,"120")
                val pref=fragment.findPreference<Preference>(key)!!
                val accepted=pref.onPreferenceChangeListener?.onPreferenceChange(pref,
                    if(key==PreferenceConfiguration.BITRATE_PREF_STRING) 60000 else "60") ?: true
                assertTrue(accepted)
                if(key==PreferenceConfiguration.BITRATE_PREF_STRING) memory.edit().putInt(key,60000).commit()
                profile.setOptions(memory.all.mapValues { it.value!! })
                assertEquals(key,60000,profile.getOptions()!!["seekbar_bitrate_kbps"])
                assertEquals(key,false,profile.getOptions()!![NovaSettingsMigration.CUSTOM_AUTO])
                assertEquals(key,false,profile.getOptions()!![NovaSettingsMigration.AUTO])
            }
        }
    }
    @Test fun revertingDisplayDuringProbeIsNotLost()=runBlocking {
        val full=mode(0,3840,2160,60f);val small=mode(0,1920,1080,60f)
        ShadowDisplayManager.setSupportedModes(0,full)
        val armed=AtomicBoolean(false);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val watched=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getSystemService(name:String):Any? {
                if(name==Context.ACTIVITY_SERVICE && armed.compareAndSet(true,false)) {
                    entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                }
                return super.getSystemService(name)
            }
        }
        NovaTierRuntime.prepare(watched)
        ShadowDisplayManager.setSupportedModes(0,small);armed.set(true)
        val worker=Executors.newSingleThreadExecutor()
        try {
            NovaTierRuntime.invalidate(watched)
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            ShadowDisplayManager.setSupportedModes(0,full)
            NovaTierRuntime.invalidate(watched)
            release.countDown()
            val done=worker.submit<NovaTierRuntime.Snapshot> { runBlocking { NovaTierRuntime.prepare(watched) } }
            assertEquals(NovaSize(3840,2160),done.get(5,TimeUnit.SECONDS).inputs.panel)
            assertEquals(NovaSize(3840,2160),NovaTierRuntime.snapshot()!!.inputs.panel)
        } finally { release.countDown();worker.shutdownNow() }
    }
    @Test fun invalidationDoesNotReadDeviceServicesOnCallingThread()=runBlocking {
        val calledOnMain=AtomicBoolean(false)
        val watched=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getSystemService(name:String):Any? {
                if(Looper.myLooper()==Looper.getMainLooper()) calledOnMain.set(true)
                return super.getSystemService(name)
            }
        }
        NovaTierRuntime.prepare(watched)
        NovaTierRuntime.invalidate(watched)
        assertFalse(calledOnMain.get())
        NovaTierRuntime.prepare(watched)
        Unit
    }
    private fun owner(request:Int=30000):PolarisSessionStatus {
        val encoder=NovaBitrateAdvice.encoderForRequest(request)
        return PolarisSessionStatus("streaming",streamingActive=true,appSessionId="round-three",sessionGeneration=1,
            ownedByClient=true,liveTuningPresent=true,encoder=PolarisSessionStatus.EncoderStatus(codec="hevc"),
            liveTuning=LiveTuningStatus(true,"stable",true,"",500000,encoder,encoder,"a".repeat(64),"host",1,1,"round-three"),
            bitrateUnits=PolarisBitrateUnits(request,encoder,encoder,512,10,splitKbps=request))
    }
    @Test fun rapidStepsUseAcknowledgedTargetWhileEncoderStatsLag()=runBlocking {
        val transport=object:NovaLiveBitrateTransport {
            var current=owner();val writes=mutableListOf<Int>()
            override fun status():PolarisSessionStatus {
                current=current.copy(liveTuning=current.liveTuning!!.copy(sequence=current.liveTuning!!.sequence+1))
                return current
            }
            override fun setBitrate(kbps:Int,observed:PolarisSessionStatus):Boolean {
                writes+=kbps;current=current.copy(liveTuning=current.liveTuning!!.copy(requestedBitrateKbps=kbps));return true
            }
        }
        val controller=NovaLiveBitrateController(transport,"round-three",1,true,true)
        controller.observe(transport.status())
        assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
        controller.observe(transport.status())
        assertEquals(35000,controller.state.value.requestedKbps)
        assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
        assertEquals(listOf(35000,40000).map { NovaBitrateAdvice.encoderForRequest(it) },transport.writes)
    }
    private fun screen()=NovaTierInputs(NovaSize(3840,2160),listOf(60,120),NovaDistance.ROOM,
        NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",listOf(NovaDecodePoint(NovaSize(3840,2160),120))))),
        NovaLink.ETHERNET,displayModes=listOf(NovaDisplayMode(NovaSize(3840,2160),60),NovaDisplayMode(NovaSize(1920,1080),120)))
    @Test fun panelRefreshLimitIsNotAttributedToDecoder() {
        val plan=NovaStreamTiers.generate(screen()).recommended
        assertEquals(60,plan.fps)
        assertTrue(plan.reasons.any { it.code=="native_panel" })
        assertTrue(plan.limits.any { it.code=="panel_fps" })
        assertFalse(plan.limits.any { it.code=="decoder_limit" })
    }
    @Test fun claimedOnlyHevcRemainsUsableWhenAvcHasMeasuredPoints() {
        val input=screen().copy(capabilities=NovaDeviceCapabilities(listOf(
            NovaCodecCapability(NovaCodecChoice.HEVC,"claimed-hevc",listOf(NovaDecodePoint(NovaSize(3840,2160),60,false))),
            NovaCodecCapability(NovaCodecChoice.AVC,"measured-avc",listOf(NovaDecodePoint(NovaSize(1920,1080),120))))))
        val plan=NovaStreamTiers.generate(input).recommended
        assertEquals(NovaCodecChoice.HEVC,plan.codec);assertEquals(NovaSize(3840,2160),plan.size)
        assertTrue(plan.reasons.any { it.code=="decoder_claimed" && it.message.contains("advertised",true) && it.message.contains("unmeasured",true) })
    }
    @Test fun roomWithoutUhdAspectUsesItsActualAspectFamily() {
        ShadowDisplayManager.setSupportedModes(0,mode(0,2560,1600,60f),mode(1,1920,1200,120f))
        shadowOf(context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        val input=NovaCapabilityProbe.deviceInputs(context)
        assertEquals(NovaSize(2560,1600),input.panel)
        assertEquals(2,input.displayModes.size)
    }
}

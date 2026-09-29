package com.papi.nova.preferences

import android.content.Context
import android.media.MediaCodecInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.binding.video.MediaCodecHelper
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodecList
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=android.app.Application::class,shadows=[com.papi.nova.shadows.ShadowMoonBridge::class])
class NovaStreamTierReviewRegressionTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    private val prefs get()=PreferenceManager.getDefaultSharedPreferences(context)
    @Before fun reset() {
        prefs.edit().clear().commit()
        context.getSharedPreferences(NovaCapabilityProbe.STORE,0).edit().clear().commit()
        ProfilesManager.instance=null
        ShadowMediaCodecList.reset()
        NovaTierRuntime.installForTest(null)
    }
    @org.junit.After fun clean() { NovaTierRuntime.installForTest(null);ProfilesManager.instance=null }
    private fun input(failed: List<NovaFailedDecodePoint> = emptyList()): NovaTierInputs {
        val points=listOf(NovaDecodePoint(NovaSize(1280,720),120),NovaDecodePoint(NovaSize(1920,1080),120),
            NovaDecodePoint(NovaSize(2560,1440),120),NovaDecodePoint(NovaSize(3840,2160),60))
        return NovaTierInputs(NovaSize(1920,1080),listOf(60,120),NovaDistance.HAND,
            NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture-hevc",points),
                NovaCodecCapability(NovaCodecChoice.AVC,"fixture-avc",points)),failed))
    }
    @Test fun probeEnumeratesBeforeHelperInitialization() {
        MediaCodecHelper::class.java.getDeclaredField("initialized").apply { isAccessible=true }.setBoolean(null,false)
        val caps=org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(android.media.MediaFormat.createVideoFormat("video/hevc",1920,1080))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                profile=MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                level=MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
            })).setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)).build()
        val codec=org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder().setName("c2.qti.hevc.decoder")
            .setIsEncoder(false).setIsVendor(true).setIsHardwareAccelerated(true).setIsSoftwareOnly(false)
            .setCapabilities(caps).build()
        val encoder=org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder().setName("fixture.hevc.encoder")
            .setIsEncoder(true).setIsVendor(true).setIsHardwareAccelerated(true).setIsSoftwareOnly(false)
            .setCapabilities(caps).build()
        val broken=org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder().setName("OMX.qcom.video.decoder.hevcswvdec")
            .setIsEncoder(false).setIsVendor(true).setIsHardwareAccelerated(true).setIsSoftwareOnly(false)
            .setCapabilities(caps).build()
        ShadowMediaCodecList.addCodec(encoder)
        ShadowMediaCodecList.addCodec(broken)
        ShadowMediaCodecList.addCodec(codec)
        assertEquals("c2.qti.hevc.decoder",NovaCapabilityProbe.inspect(context,NovaSize(1920,1080),120).codecs.single().decoder)
        assertTrue(NovaCapabilityProbe.inspect(context,NovaSize(1920,1080),50).codecs.single().points.any { it.fps==50 })
    }
    @Test fun emptyProbeCannotDestroyGoodCache() {
        val cache=context.getSharedPreferences(NovaCapabilityProbe.STORE,0)
        NovaCapabilityProbe.cachedCapabilities(cache,"good",emptyList()) { input().capabilities.codecs }
        val before=cache.all
        NovaCapabilityProbe.cachedCapabilities(cache,"temporarily-empty",emptyList()) { emptyList() }
        assertEquals(before,cache.all)
    }
    @Test fun runtimeReadDoesNotProbeBindersOnMain() {
        prefs.edit().putInt(NovaSettingsMigration.SCHEMA,2).putString(NovaSettingsMigration.TIER,"recommended").commit()
        val watched=spy(context)
        repeat(3) { NovaStreamSettings.resolveInto(watched,prefs,PreferenceConfiguration()) }
        verify(watched,never()).getSystemService(anyString())
    }
    @Test fun oneCodecFailureCannotRelaunchSameAutomaticPoint() {
        val failed=NovaFailedDecodePoint(NovaCodecChoice.HEVC,NovaSize(1920,1080),120)
        val plan=NovaStreamTiers.forDevice(input(listOf(failed))).recommended
        assertTrue("Auto must step below the failed size or rate",plan.width<1920 || plan.height<1080 || plan.fps<120)
    }
    @Test fun seededFreshInstallAndXmlDefaultsStillHaveNoCustomRung() {
        prefs.edit().putBoolean("nova_hide_system_bars",true).commit()
        NovaSettingsMigration.apply(context)
        PreferenceManager.setDefaultValues(context,R.xml.preferences,true)
        assertEquals("recommended",prefs.getString(NovaSettingsMigration.TIER,null))
        assertNull(NovaStreamSettings.custom(prefs.all))
    }
    @Test fun customUsesLaunchKeysAndKeepsEditorValuesSeparate() {
        val plan=NovaStreamSettings.custom(mapOf("list_resolution" to "2560x1440","list_fps" to "120",
            "edit_diy_w_h" to "1920x1080","custom_refresh_rate" to "59.94"))!!
        assertEquals(NovaSize(2560,1440),plan.size)
        assertEquals(120,plan.fps)
    }
    @Test fun absentBaseBitrateRemainsAbsentSoSavedSetupKeepsItsDefault() {
        val migrated=NovaSettingsMigration.migrate(mapOf("list_resolution" to "1920x1080","list_fps" to "60"))
        assertFalse(migrated.containsKey("seekbar_bitrate_kbps"))
    }
    @Test fun selectingTierDoesNotAddStreamOwnershipToAudioOnlySetup() {
        val manager=ProfilesManager.getInstance()
        val audio=SettingsProfile(UUID.randomUUID(),"Audio",0,0,mapOf("audio_config" to "51"))
        manager.add(audio);manager.setActive(audio.getUuid())
        NovaStreamSettings.select(context,NovaTier.RECOMMENDED)
        assertFalse(audio.getOptions()!!.containsKey(NovaSettingsMigration.TIER))
    }
    @Test fun futureSchemaIsNeverRewritten() {
        val old=mapOf(NovaSettingsMigration.SCHEMA to 4,NovaSettingsMigration.TIER to "recommended")
        assertEquals(old,NovaSettingsMigration.migrate(old))
    }
    @Test fun smallerUnprobedSizeIsCoveredByLargerPoint() {
        assertTrue(input().capabilities.covered(NovaCodecChoice.HEVC,NovaSize(1600,900),120))
    }
    @Test fun generatorDoesNotInventNinetyFpsOnSixtyOneTwentyPanel() {
        val input=input().copy(capabilities=NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",
            listOf(NovaDecodePoint(NovaSize(1920,1080),90))))))
        assertEquals(60,NovaStreamTiers.forDevice(input).recommended.fps)
    }
    @Test fun claimedMetadataProducesAnExplicitlyLabelledUsablePlan() {
        val input=input().let { it.copy(capabilities=it.capabilities.copy(codecs=it.capabilities.codecs.map { c ->
            c.copy(points=c.points.map { p -> p.copy(covered=false) }) })) }
        val plan=NovaStreamTiers.forDevice(input).recommended
        assertTrue(plan.available);assertTrue(plan.bitrateKbps>0)
        assertTrue(plan.reasons.any { it.code=="decoder_claimed" })
    }
    @Test fun pinsKeepWifiHoldAndDoNotLowerUnpinnedFps() {
        val room=input().copy(panel=NovaSize(3840,2160),refreshRates=listOf(60),distance=NovaDistance.ROOM,link=NovaLink.WIFI)
        assertEquals(50000,NovaStreamTiers.resolve(room,NovaTier.RECOMMENDED,pins=NovaStreamPins(fps=60)).bitrateKbps)
        val pinned=NovaStreamTiers.resolve(input(),NovaTier.RECOMMENDED,pins=NovaStreamPins(size=NovaSize(3840,2160)))
        assertEquals(120,pinned.fps);assertEquals(NovaSize(2560,1440),pinned.size)
    }
    @Test fun fourKClaimAgreesWithGeneratedPlanAfterHevcFailure() {
        val tiers=NovaStreamTiers.forDevice(input(listOf(NovaFailedDecodePoint(NovaCodecChoice.HEVC,NovaSize(3840,2160),60))))
        assertTrue(tiers.fourK is NovaFourK.Unavailable)
        assertEquals("decoder_failed",(tiers.fourK as NovaFourK.Unavailable).because.code)
    }

    @Test fun memoizedRecommendedReachesActualPreferenceReaderWithoutReprobing() {
        NovaSettingsMigration.apply(context)
        NovaTierRuntime.installForTest(input())
        val actual=PreferenceConfiguration.readPreferences(context)
        assertEquals(1920,actual.width);assertEquals(1080,actual.height)
        assertEquals(120f,actual.fps,0.0f);assertEquals(30000,actual.bitrate)
        assertEquals(PreferenceConfiguration.FormatOption.AUTO,actual.videoFormat)
    }
    @Test fun runtimePreparesMetadataOffMainAndReusesTheSnapshot()=kotlinx.coroutines.runBlocking {
        var services=0
        val watched=object:android.content.ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getSystemService(name:String):Any? {
                assertNotEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper())
                services++
                return super.getSystemService(name)
            }
        }
        val first=NovaTierRuntime.prepare(watched);val calls=services
        assertTrue(calls>0)
        assertSame(first,NovaTierRuntime.prepare(watched));assertEquals(calls,services)
    }
    @Test fun savedSizeOnlySetupKeepsItsDerivedBitrateAcrossMigration() {
        prefs.edit().putString("list_resolution","1920x1080").putString("list_fps","60").commit()
        val profile=SettingsProfile(UUID.randomUUID(),"4K",0,0,mapOf("list_resolution" to "3840x2160"))
        val manager=ProfilesManager.getInstance();manager.add(profile);manager.setActive(profile.getUuid())
        NovaSettingsMigration.apply(context)
        assertEquals(80000,PreferenceConfiguration.readPreferences(context).bitrate)
        assertFalse(prefs.contains("seekbar_bitrate_kbps"))
    }
    @Test fun profileEditorChangesMarkCustomAndManualBitrateDoesNotStayAutomatic() {
        val profile=SettingsProfile(UUID.randomUUID(),"Custom",0,0,mapOf("list_resolution" to "1920x1080"))
        profile.selectStreamTier(NovaTier.RECOMMENDED,true)
        profile.setOptions(profile.getOptions()!! + mapOf("seekbar_bitrate_kbps" to 37000))
        assertEquals("custom",profile.getOptions()!![NovaSettingsMigration.TIER])
        assertEquals(false,profile.getOptions()!![NovaSettingsMigration.AUTO])
    }
    @Test fun classicDefaultBitrateKeepsAutoAndActiveSetupSelectionTracksEdit() {
        NovaSettingsMigration.apply(context)
        val profile=SettingsProfile(UUID.randomUUID(),"Stream",0,0,mapOf("list_fps" to "60"))
        val manager=ProfilesManager.getInstance();manager.add(profile);manager.setActive(profile.getUuid())
        NovaStreamSettings.select(context,NovaTier.MAX)
        prefs.edit().putString("list_resolution","1280x720").putString("list_fps","60").putInt("seekbar_bitrate_kbps",10000).commit()
        NovaStreamSettings.classicWrite(prefs,"seekbar_bitrate_kbps")
        assertTrue(prefs.getBoolean(NovaSettingsMigration.AUTO,false))
        assertEquals("custom",profile.getOptions()!![NovaSettingsMigration.TIER])
    }
    @Test fun partialHostImportSeedsTheCurrentlySelectedTierBeforeApplyingOverride() {
        NovaSettingsMigration.apply(context);NovaTierRuntime.installForTest(input())
        prefs.edit().putString("list_resolution","1280x720").putString("list_fps","30").putString("video_format","forcepyrowave").commit()
        assertTrue(PreferenceConfiguration.applyPolarisStreamingProfile(context,null,37000))
        val actual=PreferenceConfiguration.readPreferences(context)
        assertEquals(1920,actual.width);assertEquals(120f,actual.fps,0.0f)
        assertEquals(37000,actual.bitrate)
        assertEquals(PreferenceConfiguration.FormatOption.AUTO,actual.videoFormat)
    }
    @Test fun migrationDoesNotUndoAConcurrentUnrelatedWrite() {
        val before=mapOf<String,Any>("list_resolution" to "1920x1080")
        prefs.edit().putString("list_resolution","1920x1080").putBoolean("another_writer",true).commit()
        NovaSettingsMigration.writeDifference(prefs,NovaSettingsMigration.migrate(before),before)
        assertTrue(prefs.getBoolean("another_writer",false))
    }

    @Test fun generatedLaunchCannotPretendUnavailableMetadataIsCustom() {
        NovaSettingsMigration.apply(context)
        NovaTierRuntime.installForTest(input().copy(capabilities=NovaDeviceCapabilities(emptyList())))
        val refused=NovaStreamSettings.generatedPlan(prefs)!!
        assertFalse(refused.available)
        assertTrue(refused.bitrateKbps>0)
        assertEquals("decoder_unavailable",refused.limits.first().code)
        NovaStreamSettings.select(context,NovaTier.CUSTOM)
        assertNull(NovaStreamSettings.generatedPlan(prefs))
    }

    @Test fun pinCrashSwitchBackPreservesCustomBitrateAndItsOwnAutoFlag() {
        for (savedSetup in listOf(false,true)) {
            prefs.edit().clear().commit();ProfilesManager.instance=null
            NovaSettingsMigration.apply(context)
            val pinned=mapOf<String,Any>("list_resolution" to "1920x1080","list_fps" to "120",
                "video_format" to "auto","seekbar_bitrate_kbps" to 37000,NovaSettingsMigration.TIER to "custom",
                NovaSettingsMigration.CUSTOM_EXISTS to true,NovaSettingsMigration.AUTO to false)
            if(savedSetup) {
                val manager=ProfilesManager.getInstance()
                val profile=SettingsProfile(UUID.randomUUID(),"Pinned",0,0,pinned)
                manager.add(profile);manager.setActive(profile.getUuid())
            } else {
                NovaSettingsMigration.writeDifference(prefs,prefs.all+pinned)
                NovaStreamSettings.classicWrite(prefs,"seekbar_bitrate_kbps")
            }
            NovaCapabilityProbe.recordCrashCandidate(context,NovaFailedDecodePoint(NovaCodecChoice.HEVC,NovaSize(3840,2160),60))
            PreferenceConfiguration.resetStreamingSettings(context)
            kotlinx.coroutines.runBlocking { NovaTierRuntime.prepare(context) }
            NovaTierRuntime.installForTest(input())
            val overlay=ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            assertEquals("recommended",overlay.getString(NovaSettingsMigration.TIER,null))
            assertEquals(30000,PreferenceConfiguration.readPreferences(context).bitrate)
            NovaStreamSettings.select(context,NovaTier.CUSTOM)
            val custom=ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            assertEquals(37000,PreferenceConfiguration.readPreferences(context).bitrate)
            assertFalse(custom.getBoolean("nova_custom_bitrate_auto",true))
            assertFalse(NovaStreamSettings.custom(custom.all)!!.bitrateBasis==NovaBitrateBasis.TABLE_V1)
        }
    }
}

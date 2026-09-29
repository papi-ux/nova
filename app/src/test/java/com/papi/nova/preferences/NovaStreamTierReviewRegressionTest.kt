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
@Config(sdk=[33])
class NovaStreamTierReviewRegressionTest {
    private val context: Context=ApplicationProvider.getApplicationContext()
    private val prefs get()=PreferenceManager.getDefaultSharedPreferences(context)
    @Before fun reset() {
        prefs.edit().clear().commit()
        context.getSharedPreferences(NovaCapabilityProbe.STORE,0).edit().clear().commit()
        ProfilesManager.instance=null
        ShadowMediaCodecList.reset()
    }
    private fun input(failed: List<NovaFailedDecodePoint> = emptyList()): NovaTierInputs {
        val points=listOf(NovaDecodePoint(NovaSize(1280,720),120),NovaDecodePoint(NovaSize(1920,1080),120),
            NovaDecodePoint(NovaSize(2560,1440),120),NovaDecodePoint(NovaSize(3840,2160),60))
        return NovaTierInputs(NovaSize(1920,1080),listOf(60,120),NovaDistance.HAND,
            NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture-hevc",points),
                NovaCodecCapability(NovaCodecChoice.AVC,"fixture-avc",points)),failed))
    }
    @Test fun probeEnumeratesBeforeHelperInitialization() {
        MediaCodecHelper::class.java.getDeclaredField("initialized").apply { isAccessible=true }.setBoolean(null,false)
        val codec=mock(MediaCodecInfo::class.java)
        `when`(codec.name).thenReturn("c2.qti.hevc.decoder")
        `when`(codec.supportedTypes).thenReturn(arrayOf("video/hevc"))
        val caps=mock(MediaCodecInfo.CodecCapabilities::class.java)
        val video=mock(MediaCodecInfo.VideoCapabilities::class.java)
        `when`(codec.getCapabilitiesForType("video/hevc")).thenReturn(caps)
        `when`(caps.videoCapabilities).thenReturn(video)
        `when`(video.isSizeSupported(anyInt(),anyInt())).thenReturn(true)
        `when`(video.areSizeAndRateSupported(anyInt(),anyInt(),anyDouble())).thenReturn(true)
        ShadowMediaCodecList.addCodec(codec)
        assertEquals("c2.qti.hevc.decoder",NovaCapabilityProbe.inspect(context,NovaSize(1920,1080),120).codecs.single().decoder)
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
        val old=mapOf(NovaSettingsMigration.SCHEMA to 3,NovaSettingsMigration.TIER to "recommended")
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
        if(tiers.fourK==NovaFourK.IsMax) assertEquals(NovaSize(3840,2160),tiers.max.size)
    }
}

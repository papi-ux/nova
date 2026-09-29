package com.papi.nova.preferences

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisSessionStatus.DoctorStatus
import com.papi.nova.api.PolarisSessionStatus.DoctorStatus.EvidenceItem
import com.papi.nova.manager.*
import com.papi.nova.profiles.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.UUID
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
class NovaStreamTierRoundFiveTest {
    private fun doctor(target:Int=400000)=DoctorStatus(
        available=true,version=2,resultId="result",primaryIssue="network_jitter",
        evidenceItems=listOf(EvidenceItem("packet_loss","fail","media_transport",3.0)),
        actionId="lower_bitrate",actionCapability="auto_fix",actionKind="live_tuning",
        actionEndpoint="/api/doctor/action",actionMethod="POST",actionPayloadId="lower_bitrate",
        actionSourceResultId="result",actionContractTyped=true,actionAppSessionId="session",
        actionSessionGeneration=1,actionControllerRevision=1,actionEvidenceRevision=1,
        targetBitrateKbps=target,targetBitratePresent=true,targetBitrateTyped=true,
        verificationDelaySeconds=8,verificationMode="live_telemetry",verificationEndpoint="/api/doctor/action",
        undoSupported=true,undoEndpoint="/api/doctor/action",requiresOwner=true)
    @Test fun doctorAcceptsManualRangeAndRestoreRequiresTheSameProvenLaunchCeiling() {
        for (rate in listOf(400000,500000)) {
            assertTrue(doctor(rate).canExecuteAction)
            val restore=doctor(rate).copy(actionId="restore_quality",actionPayloadId="restore_quality",
                primaryIssue="quality_reduced_live",verificationMode="graduated_live_telemetry",
                evidenceItems=listOf(EvidenceItem("effective_quality_ceiling","watch","launch_policy",rate.toDouble()),
                    EvidenceItem("packet_loss","pass","media_transport",0.0),EvidenceItem("latency","pass","stream_stats",10.0)))
            assertTrue(restore.canExecuteAction)
            assertFalse(restore.copy(targetBitrateKbps=rate-1).canExecuteAction)
        }
        assertFalse(doctor(500001).canExecuteAction)
    }
    @Test fun steadyDoctorFailRemainsPressureUntilHostClearsItAndStaleIsNotJudged() {
        fun at(status:String,source:String="media_transport")=doctor(20000).copy(
            evidenceItems=listOf(EvidenceItem("packet_loss",status,source,1.5)))
        assertTrue(at("fail").networkPressureConfirmed)
        assertTrue(at("fail").canExecuteAction)
        for (state in listOf("watch","pass","stale","unknown")) assertFalse(at(state).networkPressureConfirmed)
        assertFalse(at("fail","control_channel").networkPressureConfirmed)
    }
    @Test fun sourceDetailUsesOnlyTheSourceNamedByTheLine() {
        fun field(v:Any,s:String)=JSONObject().put("value",v).put("source",s)
        val fields=JSONObject().put("display_width",field(1280,"device_profile_v1"))
            .put("display_height",field(720,"device_profile_v1"))
            .put("target_bitrate_kbps",field(45000,"paired_client"))
        val profile=JSONObject().put("resolved_profile",JSONObject().put("policy_version",1).put("fields",fields))
        val request=NovaStreamSourceRequest(1920,1080,60.0,30000)
        assertEquals("Host's saved copy · 45 Mbps",NovaStreamSourceLine.fromPreflight(profile,request).text)
        fields.remove("target_bitrate_kbps")
        assertEquals("Host device profile · 1280×720",NovaStreamSourceLine.fromPreflight(profile,request).text)
    }
    private fun hdmi()=NovaTierInputs(NovaSize(3840,2160),listOf(30,60),NovaDistance.ROOM,
        NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"4k60",listOf(NovaDecodePoint(NovaSize(3840,2160),60))))),
        NovaLink.ETHERNET,displayModes=listOf(NovaDisplayMode(NovaSize(3840,2160),30),NovaDisplayMode(NovaSize(1920,1080),60)))
    @Test fun hdmiThirtyHzLimitIsNotAttributedToAFourKSixtyDecoder() {
        val tiers=NovaStreamTiers.generate(hdmi())
        assertEquals(NovaSize(1920,1080),tiers.recommended.size)
        assertFalse(tiers.recommended.limits.any { it.code=="decoder_limit" })
        assertTrue(tiers.recommended.limits.any { it.code=="panel_fps" })
        assertEquals("panel_fps",(tiers.fourK as NovaFourK.Unavailable).because.code)
    }
    @Test fun profileSelectionDoesNotWaitForAnInFlightDiskWrite() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        ProfilesManager.instance=null
        val manager=ProfilesManager.getInstance();manager.load(context)
        val profile=SettingsProfile(UUID.randomUUID(),"Round five",0,0,emptyMap())
        manager.add(profile)
        val lock=ReflectionHelpers.getField<Any>(manager,"persistenceLock")
        val held=CountDownLatch(1);val release=CountDownLatch(1);val threads=Executors.newFixedThreadPool(2)
        try {
            threads.submit { synchronized(lock) { held.countDown();release.await(5,TimeUnit.SECONDS) } }
            assertTrue(held.await(2,TimeUnit.SECONDS))
            val write=threads.submit { manager.updateDeferred(profile) }
            try { write.get(1,TimeUnit.SECONDS) } catch (_:TimeoutException) { fail("Selection waited for the disk lock") }
        } finally { release.countDown();threads.shutdown();threads.awaitTermination(5,TimeUnit.SECONDS);manager.awaitDeferredWritesForTest();ProfilesManager.instance=null }
    }
}

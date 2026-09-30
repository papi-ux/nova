package com.papi.nova.manager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaStreamSourceLineTest {
    @Test fun gamePinsAndSetupOwnershipSurviveAHostCapInThePreviewSource() {
        val fields=JSONObject().put("target_bitrate_kbps",JSONObject().put("value",28000)
            .put("source","capability_validation").put("reason_code","host_bitrate_cap"))
        val preflight=JSONObject().put("resolved_profile",JSONObject().put("policy_version",1).put("fields",fields))
        val game=novaLaunchChoiceAttribution("Recommended",gamePinned=true,setupParticipates=true)
        assertEquals("This game · host cap 28 Mbps",NovaStreamSourceLine.fromPreflight(preflight,
            NovaStreamSourceRequest(3840,2160,60.0,350000,game)).text)
        assertEquals("Saved setup",novaLaunchChoiceAttribution("Recommended",false,true))
        assertEquals("Recommended",novaLaunchChoiceAttribution("Recommended",false,false))
    }
    @Test fun savedCopyAndPreflightCapNameTheLayerAndFigure() {
        fun field(value: Any, source: String, reason: String="") = JSONObject().put("value",value).put("source",source).put("reason_code",reason)
        val fields = JSONObject().put("display_width",field(1280,"paired_client"))
            .put("display_height",field(720,"paired_client"))
        val preflight = JSONObject().put("resolved_profile",JSONObject().put("policy_version",1).put("fields",fields))
        assertEquals("Host's saved copy · 1280×720",NovaStreamSourceLine.fromPreflight(preflight,NovaStreamSourceRequest(1920,1080,120.0,30000)).text)
        fields.put("target_bitrate_kbps",field(28000,"capability_validation","host_bitrate_cap"))
        val cap = NovaStreamSourceLine.fromPreflight(preflight,NovaStreamSourceRequest(1920,1080,120.0,30000))
        assertEquals(NovaStreamSource.HOST_SAVED_COPY,cap.source)
        assertEquals(28000,cap.capKbps)
        assertEquals("Host's saved copy · host cap 28 Mbps",cap.text)
    }
    @Test fun spaceAndWatchCannotPretendToBeThisDevicesRequest() {
        assertEquals(NovaStreamSource.SPACE,NovaStreamSourceLine.fromPreflight(JSONObject().put("source","worker_profile_v1")).source)
        val watch = NovaStreamSourceLine.watch(1920,1080,24)
        assertEquals(NovaStreamSource.WATCH,watch.source)
        assertTrue(watch.text.contains("24 fps"))
        assertTrue(watch.text.length <= 56)
        assertEquals("Watching",NovaStreamSourceLine.watch(0,0,0).text)
    }

    @Test fun syncedEqualValuesAreNotAHostOverrideAndCapsRetainWho() {
        fun field(v:Any,source:String="paired_client",reason:String="")=JSONObject().put("value",v).put("source",source).put("reason_code",reason)
        val fields=JSONObject().put("display_width",field(1920)).put("display_height",field(1080))
            .put("target_fps",field(120)).put("target_bitrate_kbps",field(30000))
        val preflight=JSONObject().put("resolved_profile",JSONObject().put("policy_version",1).put("fields",fields))
        val request=NovaStreamSourceRequest(1920,1080,120.0,30000)
        assertEquals("Recommended",NovaStreamSourceLine.fromPreflight(preflight,request).text)
        fields.put("target_fps",field(60,"capability_validation","host_refresh_cap"))
        val capped=NovaStreamSourceLine.fromPreflight(preflight,request)
        assertEquals(NovaStreamSource.DEVICE,capped.source)
        assertEquals(listOf("host_refresh_cap"),capped.limitCodes)
        assertEquals("Recommended · the host caps it at 60 fps",capped.text)
        fields.put("target_fps",field(60,"device_profile_v1","stability_preset_selected"))
        assertEquals(NovaStreamSource.DEVICE,NovaStreamSourceLine.fromPreflight(preflight,request).source)
        assertEquals("Recommended · Stability preset",NovaStreamSourceLine.fromPreflight(preflight,request).text)
        fields.put("target_fps",field(60,"capability_validation","client_refresh_cap"))
        assertEquals("Recommended · this screen caps it at 60 fps",NovaStreamSourceLine.fromPreflight(preflight,request).text)
    }
    @Test fun specSpaceAndWatchCopyIsExact() {
        assertEquals("Set by this Space · H.264 up to 8 Mbps",NovaStreamSourceLine.space().text)
        assertEquals("Watching · 1080p at 24 fps",NovaStreamSourceLine.watch(1920,1080,24).text)
    }
    @Test fun savedBitrateOnlyDifferenceNamesBitrateWithTheRealHostSource() {
        val fields=JSONObject()
        for((key,value) in listOf("display_width" to 1920,"display_height" to 1080,"target_fps" to 120,"target_bitrate_kbps" to 45000))
            fields.put(key,JSONObject().put("source","paired_client").put("value",value))
        val preflight=JSONObject().put("resolved_profile",JSONObject().put("policy_version",1).put("fields",fields))
        assertEquals("Host's saved copy · 45 Mbps",NovaStreamSourceLine.fromPreflight(preflight,NovaStreamSourceRequest(1920,1080,120.0,30000)).text)
    }

    @Test fun devicePolicyCannotMasqueradeAsThePairedClientsSavedCopy() {
        val fields=JSONObject().put("target_bitrate_kbps",JSONObject().put("source","device_profile_v1").put("value",45000))
        val preflight=JSONObject().put("resolved_profile",JSONObject().put("policy_version",1).put("fields",fields))
        val line=NovaStreamSourceLine.fromPreflight(preflight,NovaStreamSourceRequest(1920,1080,120.0,30000))
        assertEquals(NovaStreamSource.HOST_POLICY,line.source)
        assertEquals("Host device profile · 45 Mbps",line.text)
    }

    @Test fun missingRequestAndMultipleLimitsKeepSourceAndConciseCopy() {
        fun field(v:Any,reason:String)=JSONObject().put("value",v).put("source","capability_validation").put("reason_code",reason)
        val fields=JSONObject().put("target_bitrate_kbps",field(28000,"host_bitrate_cap"))
            .put("target_fps",field(60,"host_refresh_cap"))
        val preflight=JSONObject().put("resolved_profile",JSONObject().put("policy_version",1).put("fields",fields))
        val request=NovaStreamSourceRequest(1920,1080,120.0,30000)
        val known=NovaStreamSourceLine.fromPreflight(preflight,request)
        assertEquals(NovaStreamSource.DEVICE,known.source)
        assertEquals("Recommended · host cap 28 Mbps",known.text)
        assertEquals(listOf("host_bitrate_cap","host_refresh_cap"),known.limitCodes)
        val unknown=NovaStreamSourceLine.fromPreflight(preflight)
        assertEquals(NovaStreamSource.UNKNOWN,unknown.source)
        assertEquals("Host stream settings · host cap 28 Mbps",unknown.text)
        for(line in listOf(known,unknown,NovaStreamSourceLine.fromPreflight(preflight,request.copy(who="A".repeat(56))))) {
            assertTrue(line.text.length<=56)
            assertFalse(line.text.contains('\u2014') || line.text.contains('\u2013'))
        }
    }

}

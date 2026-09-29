package com.papi.nova.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.*
import com.papi.nova.manager.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
class NovaManualBitrateCapabilityTest {
    private fun caps(value:Any?=null,units:Boolean=true,scoped:Boolean=true):PolarisCapabilities {
        val features=JSONObject().put("bitrate_units_v1",units).put("pyrowave_advice_v1",scoped)
        if(value!=null) features.put("manual_bitrate_max_kbps",value)
        return PolarisApiClient.parseCapabilitiesResponse(JSONObject().put("features",features))
    }
    @Test fun manualMaximumIsANumberAndInvalidOrAbsentValuesUseTheLegacyLimit() {
        // This is also the named UI integration contract; it must be present on Features.
        fun maximum(features:PolarisCapabilities.Features)=features.manualBitrateMaxKbps
        assertEquals(300000,maximum(PolarisCapabilities.Features()))
        for(value in listOf(null,JSONObject.NULL,true,false,"500000",500000.5,-1,0,Long.MAX_VALUE))
            assertEquals("value=$value",300000,maximum(caps(value).features))
        for(value in listOf(200000,300000,500000,500000.0))
            assertEquals((value as Number).toInt(),maximum(caps(value).features))
    }
    private class Live(val capabilities:PolarisCapabilities) {
        val audio=1536;val fec=20
        val units=capabilities.features.bitrateUnitsV1
        val initial=if(units) NovaBitrateAdvice.encoderForRequest(10000,audio,fec) else 10000
        var current=initial;var sequence=1
        val posts=mutableListOf<Int>()
        fun status():JSONObject {
            val live=JSONObject().put("version",1).put("supported",true).put("enabled",true).put("scope","host")
                .put("state","stable").put("quality_limit_kbps",300000).put("requested_bitrate_kbps",current)
                .put("applied_bitrate_kbps",current).put("sequence",sequence++).put("host_instance","host")
                .put("configuration_revision","a".repeat(64)).put("session_generation",1).put("app_session_id","capability")
            val status=JSONObject().put("state","streaming").put("streaming_active",true).put("client_role","owner")
                .put("owned_by_client",true).put("app_session_id","capability").put("session_generation",1)
                .put("controls",JSONObject().put("host_tuning_allowed",true)).put("live_tuning",live)
                .put("encoder",JSONObject().put("codec","hevc"))
            if(units) status.put("bitrate_units",JSONObject().put("version",1).put("formula","stream_bitrate_v1")
                .put("requested_kbps",10000).put("split_kbps",10000).put("warp_factor",1)
                .put("cap_kbps",JSONObject.NULL).put("cap_source",JSONObject.NULL)
                .put("encoder_kbps",initial).put("live_encoder_kbps",current).put("audio_kbps",audio).put("fec_percentage",fec))
            return status
        }
        val observed get()=PolarisApiClient.parseSessionStatusResponse(status())
        val api=PolarisApiClient(ApplicationProvider.getApplicationContext(),"127.0.0.1",47984).also { api ->
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                val request=chain.request()
                val body=if(request.method=="GET") status() else {
                    assertTrue(request.url.encodedPath.endsWith("/session/bitrate"))
                    val buffer=Buffer();request.body!!.writeTo(buffer)
                    val sent=JSONObject(buffer.readUtf8())
                    assertEquals("capability",sent.getString("app_session_id"));assertEquals(1,sent.getInt("session_generation"))
                    current=sent.getInt("bitrate_kbps");posts+=current
                    JSONObject().put("status",true).put("bitrate_kbps",current)
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(ResponseBody.create(null,body.toString())).build()
            }.build()
            PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible=true }.set(api,client)
        }
        fun controller()=NovaLiveBitrateController(api,observed,capabilities).also { it.observe(observed,tableRecommendedKbps=450000) }
    }
    @Test fun oldHostRequestWritesStopAtThreeHundredAndStillSendEncoderUnits()=runBlocking {
        val live=Live(caps());val controller=live.controller()
        assertEquals(300000,controller.state.value.maximumKbps)
        assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(500000))
        assertEquals(listOf(NovaBitrateAdvice.encoderForRequest(300000,live.audio,live.fec)),live.posts)
        assertEquals(300000,controller.state.value.requestedKbps)
    }
    @Test fun advertisedManualLimitControlsWritesButRecommendedStaysAtThreeHundred()=runBlocking {
        for(limit in listOf(200000,500000)) {
            val live=Live(caps(limit));val controller=live.controller()
            assertEquals(limit,controller.state.value.maximumKbps)
            assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(500000))
            assertEquals(NovaBitrateAdvice.encoderForRequest(limit,live.audio,live.fec),live.posts.last())
            assertEquals(limit,controller.state.value.requestedKbps)
            assertEquals(minOf(limit,300000),controller.state.value.recommendedKbps)
            controller.useRecommended()
            assertEquals(minOf(limit,300000),controller.state.value.requestedKbps)
        }
    }
    @Test fun videoFallbackUsesTheAdvertisedOrLegacyCeilingWithoutInventingOverhead()=runBlocking {
        for(limit in listOf(null,500000)) {
            val live=Live(caps(limit,units=false));val controller=live.controller()
            assertEquals(NovaBitrateUnits.VIDEO,controller.state.value.units)
            assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(500000))
            assertEquals(listOf(limit ?: 300000),live.posts)
            assertNull(controller.state.value.recommendedKbps)
        }
    }
    @Test fun numericMaximumAloneDoesNotAuthorizeStreamWrites()=runBlocking {
        val live=Live(caps(500000,units=false,scoped=false));val controller=live.controller()
        assertFalse(controller.state.value.canChange)
        assertEquals(NovaBitrateChange.UNAVAILABLE,controller.setBitrate(500000))
        assertTrue(live.posts.isEmpty())
    }
    private fun input()=NovaTierInputs(NovaSize(1920,1080),listOf(60),NovaDistance.HAND,
        NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",listOf(NovaDecodePoint(NovaSize(1920,1080),60))))))
    @Test fun hostWithoutFeatureLimitsTheLaunchWithoutErasingTheStoredCustomPin() {
        val custom=NovaStreamPlan(1920,1080,60,NovaCodecChoice.HEVC,500000,NovaBitrateBasis.CUSTOM)
        val input=input().copy(host=NovaHostTierLimits())
        assertEquals(300000,NovaStreamTiers.resolve(input,NovaTier.RECOMMENDED,pins=NovaStreamPins(bitrateKbps=500000)).bitrateKbps)
        assertEquals(300000,NovaStreamTiers.resolve(input,NovaTier.CUSTOM,custom).bitrateKbps)
        assertEquals(500000,custom.bitrateKbps)
        assertEquals(500000,NovaStreamTiers.forDevice(input,custom).custom!!.bitrateKbps)
    }
    @Test fun hostPlanCapabilityAdapterKeepsOtherLimitsAndHandlesUnavailablePlans() {
        val base=NovaHostTierLimits(maxFps=60,bitrateCapKbps=420000)
        for((cap,expected) in listOf(caps() to 300000,caps(500000) to 420000)) {
            val host=base.withCapabilities(cap)
            assertEquals(60,host.maxFps)
            for(available in listOf(true,false)) {
                val input=input().copy(host=host,capabilities=if(available) input().capabilities else NovaDeviceCapabilities(emptyList()))
                val plan=NovaStreamTiers.resolve(input,NovaTier.RECOMMENDED,pins=NovaStreamPins(bitrateKbps=500000))
                assertEquals(expected,plan.bitrateKbps)
                assertTrue(plan.limits.any { it.code=="host_bitrate" })
            }
        }
    }
    @Test fun allFourteenHandheldRowsRemainUncappedModelFigures() {
        val fixture=JSONObject(javaClass.getResource("/pyrowave-rate-model.json")!!.readText())
        val rows=fixture.getJSONArray("far_cases");assertEquals(14,rows.length())
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            val encoder=NovaBitrateAdvice.pyrowaveEncoderKbps(row.getInt("width"),row.getInt("height"),row.getInt("fps"),
                NovaDistance.HAND,row.getString("chroma")=="444")
            assertEquals(row.toString(),(row.getDouble("mbps")*1000).toInt(),encoder)
        }
        val encoder=NovaBitrateAdvice.pyrowaveEncoderKbps(3840,2160,120,NovaDistance.HAND)
        assertEquals(293895,encoder);assertEquals(327675,NovaBitrateAdvice.requestForEncoder(encoder))
        assertEquals(300000,NovaBitrateAdvice.recommend(3840,2160,120,NovaCodecChoice.PYROWAVE).kbps)
    }
}

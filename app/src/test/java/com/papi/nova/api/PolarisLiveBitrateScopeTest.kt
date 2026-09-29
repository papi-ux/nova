package com.papi.nova.api

import androidx.test.core.app.ApplicationProvider
import okhttp3.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolarisLiveBitrateScopeTest {
    private fun status(generation: Int = 7) = JSONObject().put("state","streaming").put("streaming_active",true)
        .put("app_session_id","session-a").put("session_generation",generation).put("client_role","owner")
        .put("owned_by_client",true).put("controls",JSONObject().put("host_tuning_allowed",true))
    private fun client(handler: (Request) -> Response): PolarisApiClient {
        val api = PolarisApiClient(ApplicationProvider.getApplicationContext(),"127.0.0.1",47984)
        val client = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible=true }.set(api,client)
        return api
    }
    private fun reply(request: Request, json: String, code: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(ResponseBody.create(null,json)).build()

    @Test fun replacementBetweenControllerObservationAndApiReadNeverPosts() {
        val calls = mutableListOf<String>()
        val api = client { calls += it.method;reply(it,status(8).toString()) }
        assertFalse(api.setBitrate(40000,PolarisApiClient.parseSessionStatusResponse(status())))
        assertEquals(listOf("GET"),calls)
    }
    @Test fun writeCarriesObservedScopeAndIsNeverReplayedAfterFailure() {
        var posts=0;var body: JSONObject?=null
        val api = client { request ->
            if (request.method == "GET") reply(request,status().toString()) else {
                posts++;val buffer=Buffer();request.body!!.writeTo(buffer);body=JSONObject(buffer.readUtf8())
                reply(request,"{}",503)
            }
        }
        assertFalse(api.setBitrate(40000,PolarisApiClient.parseSessionStatusResponse(status())))
        assertEquals(1,posts)
        assertEquals("session-a",body!!.getString("app_session_id"))
        assertEquals(7,body!!.getInt("session_generation"))
        assertEquals(40000,body!!.getInt("bitrate_kbps"))
    }
    @Test fun successRequiresFreshOwnerAndEnforcesEndpointMinimum() {
        var posts=0
        val api=client { request -> if (request.method=="GET") reply(request,status().toString()) else {
            posts++;reply(request,"{\"status\":true,\"bitrate_kbps\":40000}")
        } }
        assertFalse(api.setBitrate(500,PolarisApiClient.parseSessionStatusResponse(status())))
        assertEquals(0,posts)
        assertTrue(api.setBitrate(40000,PolarisApiClient.parseSessionStatusResponse(status())))
        assertEquals(1,posts)
    }
    @Test fun serverCapIsReturnedToControllerAndFalseReceiptsNeverSucceed() {
        val api=client { request -> if (request.method=="GET") reply(request,status().toString()) else
            reply(request,"{\"status\":true,\"bitrate_kbps\":150000}") }
        var accepted=0
        assertTrue(api.setBitrate(180000,PolarisApiClient.parseSessionStatusResponse(status())) { accepted=it })
        assertEquals(150000,accepted)
        val rejected=client { request -> if (request.method=="GET") reply(request,status().toString()) else
            reply(request,"{\"status\":false,\"bitrate_kbps\":180000}") }
        assertFalse(rejected.setBitrate(180000,PolarisApiClient.parseSessionStatusResponse(status())))
    }


    @Test fun replacementAndConflictReturnTypedSessionChange() {
        val replaced=client { reply(it,status(8).toString()) }
        assertEquals(PolarisBitrateWriteResult.SessionChanged,
            replaced.setBitrateResult(40000,PolarisApiClient.parseSessionStatusResponse(status())))
        var posts=0
        val conflict=client { request -> if(request.method=="GET") reply(request,status().toString()) else {
            posts++;reply(request,"{}",409)
        } }
        assertEquals(PolarisBitrateWriteResult.SessionChanged,
            conflict.setBitrateResult(40000,PolarisApiClient.parseSessionStatusResponse(status())))
        assertEquals(1,posts)
    }

    @Test fun failedStatusReadIsNotASessionReplacement() {
        val api=client { reply(it,"{}",503) }
        assertEquals(PolarisBitrateWriteResult.Failed,
            api.setBitrateResult(40000,PolarisApiClient.parseSessionStatusResponse(status())))
    }
    @Test fun sameSessionWithoutAuthorityIsNotASessionReplacement() {
        val forbidden=status().put("controls",JSONObject().put("host_tuning_allowed",false))
        val api=client { reply(it,forbidden.toString()) }
        assertEquals(PolarisBitrateWriteResult.Failed,
            api.setBitrateResult(40000,PolarisApiClient.parseSessionStatusResponse(status())))
    }
    @Test fun fullHostFecRangeKeepsPyrowaveAdviceAndItsAssumptions() {
        val json=JSONObject().put("version",1).put("width",1920).put("height",1080).put("fps",120)
            .put("raise_goal_kbps",200000).put("cap_kbps",300000).put("raise_goal_limited_by","advice")
            .put("assumes",JSONObject().put("fec_percentage",150).put("audio_kbps",1536))
        val advice=PolarisPyrowaveAdvice.parse(json)
        assertNotNull(advice);assertEquals(150,advice!!.fecPercent)
        assertEquals(179464,com.papi.nova.preferences.NovaBitrateAdvice.encoderForRequest(181500,1536,150))
    }
    @Test fun versionedUnitsEnableDeviceRecommendationWithActualSurroundAndFec() = kotlinx.coroutines.runBlocking {
        for (codec in listOf("h264","hevc")) for ((audio,fec) in listOf(192 to 10,1536 to 20,2048 to 150)) {
            val initialEncoder=com.papi.nova.preferences.NovaBitrateAdvice.encoderForRequest(10000,audio,fec)
            var current=initialEncoder
            var sequence=1
            val posts=mutableListOf<Int>()
            fun live() = status().put("encoder",JSONObject().put("codec",codec))
                .put("live_tuning",JSONObject().put("supported",true).put("enabled",true)
                    .put("requested_bitrate_kbps",current).put("sequence",sequence++)
                    .put("host_instance","host").put("configuration_revision","a".repeat(64))
                    .put("version",1).put("scope","host").put("state","stable").put("quality_limit_kbps",300000)
                    .put("applied_bitrate_kbps",current).put("session_generation",7).put("app_session_id","session-a"))
                .put("bitrate_units",JSONObject().put("version",1).put("formula","stream_bitrate_v1")
                    .put("requested_kbps",10000).put("split_kbps",10000).put("warp_factor",1)
                    .put("cap_kbps",JSONObject.NULL).put("cap_source",JSONObject.NULL)
                    .put("encoder_kbps",initialEncoder).put("live_encoder_kbps",current)
                    .put("audio_kbps",audio).put("fec_percentage",fec))
            val api=client { request -> if(request.method=="GET") reply(request,live().toString()) else {
                val body=Buffer();request.body!!.writeTo(body)
                current=JSONObject(body.readUtf8()).getInt("bitrate_kbps");posts+=current
                reply(request,JSONObject().put("status",true).put("bitrate_kbps",current).toString())
            } }
            val caps=PolarisApiClient.parseCapabilitiesResponse(JSONObject().put("features",
                JSONObject().put("bitrate_units_v1",true)))
            val observed=PolarisApiClient.parseSessionStatusResponse(live())
            val controller=com.papi.nova.manager.NovaLiveBitrateController(api,observed,caps)
            controller.observe(observed,tableRecommendedKbps=30000)
            assertNotNull("The fixture must carry a valid Live Tuning contract",observed.liveTuning)
            assertEquals(com.papi.nova.manager.NovaBitrateUnits.REQUEST,controller.state.value.units)
            assertTrue("New units capability authorizes scoped stream writes",controller.state.value.canChange)
            assertEquals(com.papi.nova.manager.NovaBitrateChange.APPLIED,controller.useRecommended())
            assertEquals(listOf(com.papi.nova.preferences.NovaBitrateAdvice.encoderForRequest(30000,audio,fec)),posts)
            assertEquals(30000,controller.state.value.requestedKbps)
        }
    }

    @Test fun malformedOrUnsupportedUnitObjectsNeverSupplyConversionInputs() {
        val valid=JSONObject().put("version",1).put("formula","stream_bitrate_v1").put("requested_kbps",30000)
            .put("split_kbps",30000).put("warp_factor",1).put("cap_kbps",JSONObject.NULL).put("cap_source",JSONObject.NULL)
            .put("encoder_kbps",24963).put("live_encoder_kbps",24963).put("audio_kbps",1536).put("fec_percentage",10)
        assertNotNull(PolarisBitrateUnits.parse(valid))
        for((key,value) in listOf("version" to 2,"formula" to "future", "audio_kbps" to -1,
            "fec_percentage" to 256,"fec_percentage" to "10","live_encoder_kbps" to -1,"requested_kbps" to 1.5))
            assertNull("$key=$value",PolarisBitrateUnits.parse(JSONObject(valid.toString()).put(key,value)))
        for(key in listOf("version","formula","audio_kbps","fec_percentage","encoder_kbps","requested_kbps","live_encoder_kbps")) {
            val missing=JSONObject(valid.toString());missing.remove(key)
            assertNull(key,PolarisBitrateUnits.parse(missing))
        }
    }

}

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

/** Papi's nova#130 decision: manual 500 Mbps, automatic 300 Mbps, calibrated handheld advice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NovaBitratePolicyApprovalTest {
    @Test fun handheldUsesTheSharedPolarisCalibrationIncludingModelEdges() {
        // Byte-for-byte copy of Polaris tests/fixtures/pyrowave-rate-model.json from #218.
        val fixture = JSONObject(javaClass.getResource("/pyrowave-rate-model.json")!!.readText())
        assertEquals(31, fixture.getInt("far_target_db"))
        assertEquals(35, fixture.getInt("target_db"))
        for ((rows, distance, heightFactor) in listOf(
            Triple("far_cases", NovaDistance.HAND, 15), Triple("cases", NovaDistance.ROOM, 8))) {
            val cases = fixture.getJSONArray(rows)
            for (i in 0 until cases.length()) {
                val row = cases.getJSONObject(i)
                if (row.getString("chroma") != "444" || row.getInt("height_factor") != heightFactor) continue
                val encoder = (row.getDouble("mbps") * 1000).toInt() // Polaris truncates encoder kbps.
                assertEquals(row.toString(), encoder, NovaBitrateAdvice.pyrowaveEncoderKbps(
                    row.getInt("width"), row.getInt("height"), row.getInt("fps"), distance))
                val expected = NovaBitrateAdvice.requestForEncoder(encoder).coerceAtMost(300000)
                val advice = NovaBitrateAdvice.recommend(row.getInt("width"), row.getInt("height"),
                    row.getInt("fps"), NovaCodecChoice.PYROWAVE, distance)
                assertEquals(row.toString(), expected, advice.kbps)
                assertEquals(NovaBitrateBasis.PYROWAVE_MODEL, advice.basis)
            }
        }
    }

    @Test fun rp6FixtureQuotesRequestUnitsAndRoomKeepsItsHigherTarget() {
        for (distance in listOf(NovaDistance.HAND, NovaDistance.LAP)) {
            val advice = NovaBitrateAdvice.recommend(1920,1080,120,NovaCodecChoice.PYROWAVE,distance)
            assertEquals(214898, advice.kbps)
            assertTrue(advice.kbps in 200000..230000)
        }
        assertEquals(300000, NovaBitrateAdvice.recommend(1920,1080,120,NovaCodecChoice.PYROWAVE,NovaDistance.ROOM).kbps)
    }

    @Test fun oversizedHostRaiseGoalIsCappedInsteadOfDiscardedForLocalAdvice() {
        val advice = NovaBitrateAdvice.recommend(1280,720,60,NovaCodecChoice.PYROWAVE,hostRaiseGoalKbps=450000)
        assertEquals(NovaBitrateBasis.HOST_PYROWAVE, advice.basis)
        assertEquals(300000, advice.kbps)
    }

    @Test fun manualPinsReachFiveHundredButHostAndSpaceLimitsRemain() {
        val size = NovaSize(1920,1080)
        for (codec in listOf(NovaCodecChoice.AVC,NovaCodecChoice.HEVC,NovaCodecChoice.PYROWAVE)) {
            val input = NovaTierInputs(size,listOf(60,120),NovaDistance.HAND,
                NovaDeviceCapabilities(listOf(NovaCodecCapability(codec,"fixture",listOf(NovaDecodePoint(size,120))))),
                codec=codec,pyrowave=NovaPyrowaveSupport(available=true))
            for ((pin,expected) in listOf(350000 to 350000,500000 to 500000,600000 to 500000)) {
                assertEquals(codec.name,expected,NovaStreamTiers.resolve(input,NovaTier.RECOMMENDED,
                    pins=NovaStreamPins(bitrateKbps=pin)).bitrateKbps)
            }
            assertEquals(420000,NovaStreamTiers.resolve(input.copy(host=NovaHostTierLimits(bitrateCapKbps=420000)),
                NovaTier.RECOMMENDED,pins=NovaStreamPins(bitrateKbps=500000)).bitrateKbps)
            if(codec==NovaCodecChoice.AVC) assertEquals(8000,NovaStreamTiers.resolve(
                input.copy(host=NovaHostTierLimits(space=true)),NovaTier.RECOMMENDED,
                pins=NovaStreamPins(bitrateKbps=500000)).bitrateKbps)
        }
    }

    private fun adviceJson(goal:Int=450000,cap:Int=500000,limited:String="max_bitrate") = JSONObject()
        .put("version",1).put("width",1920).put("height",1080).put("fps",120)
        .put("raise_goal_kbps",goal).put("cap_kbps",cap).put("raise_goal_limited_by",limited)
        .put("assumes",JSONObject().put("audio_kbps",512).put("fec_percentage",10))

    @Test fun adviceParserRetainsManualMaximumAndCapsOnlyAutomaticGoal() {
        val advice = PolarisPyrowaveAdvice.parse(adviceJson())
        assertNotNull(advice)
        assertEquals(500000,advice!!.capKbps)
        assertEquals(450000,advice.hostMaximumKbps)
        assertEquals(300000,advice.raiseGoalKbps)
        assertNull(PolarisPyrowaveAdvice.parse(adviceJson(cap=500001)))
        assertNull(PolarisPyrowaveAdvice.parse(adviceJson(goal=500001)))
    }

    private fun owner(codec:String="pyrowave", request:Int=300000):PolarisSessionStatus {
        val encoder=NovaBitrateAdvice.encoderForRequest(request)
        return PolarisSessionStatus("streaming",streamingActive=true,appSessionId="approved",sessionGeneration=4,
            ownedByClient=true,liveTuningPresent=true,encoder=PolarisSessionStatus.EncoderStatus(codec=codec),
            liveTuning=LiveTuningStatus(true,"stable",true,"",500000,encoder,encoder,"a".repeat(64),"host",1,4,"approved"),
            bitrateUnits=PolarisBitrateUnits(request,encoder,encoder,512,10,splitKbps=request),
            pyrowaveBitrate=if(codec=="pyrowave") PolarisPyrowaveAdvice(1920,1080,120,450000,300000,"cap") else null)
    }
    private class Fake(var current:PolarisSessionStatus):NovaLiveBitrateTransport {
        val writes=mutableListOf<Int>()
        var floor:Int?=null
        override fun status()=current
        override fun setBitrate(kbps:Int,observed:PolarisSessionStatus)=true
        override fun write(encoderKbps:Int,observed:PolarisSessionStatus):PolarisBitrateWriteResult {
            writes+=encoderKbps
            val actual=maxOf(encoderKbps,floor ?: 1000)
            current=current.copy(liveTuning=current.liveTuning!!.copy(requestedBitrateKbps=actual,
                appliedBitrateKbps=actual,sequence=current.liveTuning!!.sequence+1),
                bitrateUnits=current.bitrateUnits?.copy(liveEncoderKbps=actual))
            return PolarisBitrateWriteResult.Applied(actual,observed)
        }
    }
    private fun controller(fake:Fake,units:Boolean=true)=NovaLiveBitrateController(fake,"approved",4,true,units)
        .also { it.observe(fake.current,tableRecommendedKbps=450000) }

    @Test fun manualWritesAndStepsCrossAutomaticCapForEveryCodec()=runBlocking {
        for(codec in listOf("h264","hevc","av1","pyrowave")) {
            val fake=Fake(owner(codec));val controller=controller(fake)
            assertEquals(500000,controller.state.value.maximumKbps)
            assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
            assertEquals(if(codec=="pyrowave") 330000 else 305000,controller.state.value.requestedKbps)
            assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(500000))
            assertEquals(NovaBitrateAdvice.encoderForRequest(500000),fake.writes.last())
            assertEquals(500000,controller.state.value.requestedKbps)
            assertEquals(NovaBitrateChange.AT_LIMIT,controller.step(1))
            assertEquals(NovaBitrateChange.APPLIED,controller.useRecommended())
            assertEquals(300000,controller.state.value.requestedKbps)
        }
    }

    @Test fun manualEncoderFallbackAlsoReachesFiveHundredWithoutGuessedConversion()=runBlocking {
        val fake=Fake(owner("hevc"));val controller=controller(fake,units=false)
        assertEquals(NovaBitrateUnits.VIDEO,controller.state.value.units)
        assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(500000))
        assertEquals(listOf(500000),fake.writes)
        assertNull(controller.state.value.recommendedKbps)
    }

    @Test fun actualHostMaximumAboveAutomaticCapStillLimitsManualWrites()=runBlocking {
        val fake=Fake(owner());val controller=controller(fake)
        controller.observe(fake.current,hostMaximumKbps=420000)
        assertEquals(420000,controller.state.value.maximumKbps)
        assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(500000))
        assertEquals(420000,controller.state.value.requestedKbps)
        assertEquals(300000,controller.state.value.recommendedKbps)
    }

    @Test fun hostFloorAboveAutomaticCeilingDisablesRecommendedRatherThanRaisingPastIt()=runBlocking {
        val fake=Fake(owner(request=400000));val controller=controller(fake)
        fake.floor=NovaBitrateAdvice.encoderForRequest(350000)
        assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(300000))
        assertNull(controller.state.value.recommendedKbps)
        val count=fake.writes.size
        assertEquals(NovaBitrateChange.UNAVAILABLE,controller.useRecommended())
        assertEquals(count,fake.writes.size)
    }

    @Test fun scopedEndpointAcceptsManualWriteAndReceiptThroughFiveHundred() {
        val status=JSONObject().put("state","streaming").put("streaming_active",true)
            .put("app_session_id","approved").put("session_generation",4).put("client_role","owner")
            .put("owned_by_client",true).put("controls",JSONObject().put("host_tuning_allowed",true))
        val posts=mutableListOf<Int>();var receipt:Int?=null
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val body=if(request.method=="GET") status else {
                val buffer=Buffer();request.body!!.writeTo(buffer)
                val sent=JSONObject(buffer.readUtf8());posts+=sent.getInt("bitrate_kbps")
                assertEquals("approved",sent.getString("app_session_id"));assertEquals(4,sent.getInt("session_generation"))
                JSONObject().put("status",true).put("bitrate_kbps",receipt ?: posts.last())
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(ResponseBody.create(null,body.toString())).build()
        }.build()
        val api=PolarisApiClient(ApplicationProvider.getApplicationContext(),"127.0.0.1",47984)
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible=true }.set(api,client)
        val observed=PolarisApiClient.parseSessionStatusResponse(status)
        for(rate in listOf(350000,500000)) {
            val result=api.setBitrateResult(rate,observed)
            assertTrue(result is PolarisBitrateWriteResult.Applied)
            assertEquals(rate,(result as PolarisBitrateWriteResult.Applied).encoderKbps)
        }
        assertEquals(PolarisBitrateWriteResult.Failed,api.setBitrateResult(500001,observed))
        assertEquals(listOf(350000,500000),posts)
        receipt=500001
        assertEquals(PolarisBitrateWriteResult.Failed,api.setBitrateResult(450000,observed))
    }
}

package com.papi.nova.manager

import com.papi.nova.api.*
import com.papi.nova.preferences.NovaBitrateAdvice
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class NovaFinalBitrateUnitsContractTest {
    private fun units(request:Int=20000,split:Int?=40000,warp:Int=2,cap:Int?=null,source:String?=null,
        encoder:Int=NovaBitrateAdvice.encoderForRequest(split ?: 40000),live:Int=encoder)=JSONObject()
        .put("version",1).put("formula","stream_bitrate_v1").put("requested_kbps",request)
        .put("split_kbps",split ?: JSONObject.NULL).put("warp_factor",warp)
        .put("cap_kbps",cap ?: JSONObject.NULL).put("cap_source",source ?: JSONObject.NULL)
        .put("encoder_kbps",encoder).put("live_encoder_kbps",live).put("audio_kbps",512).put("fec_percentage",10)
    private fun status(json:JSONObject?,codec:String="hevc",viewer:Boolean=false):PolarisSessionStatus {
        val encoder=json?.optInt("live_encoder_kbps") ?: 20000
        return PolarisSessionStatus("streaming",streamingActive=true,appSessionId="stream",sessionGeneration=1,
            clientRole=if(viewer) "viewer" else "owner",ownedByClient=!viewer,liveTuningPresent=true,
            encoder=PolarisSessionStatus.EncoderStatus(codec=codec),
            liveTuning=LiveTuningStatus(true,"stable",true,"",300000,encoder,encoder,"a".repeat(64),"host",1,1,"stream"),
            bitrateUnits=PolarisBitrateUnits.parse(json),
            pyrowaveBitrate=if(codec=="pyrowave") PolarisPyrowaveAdvice(1920,1080,60,100000,300000,"advice") else null)
    }
    private open class Fake(var current:PolarisSessionStatus):NovaLiveBitrateTransport {
        val writes=mutableListOf<Int>()
        override fun status()=current
        override fun setBitrate(kbps:Int,observed:PolarisSessionStatus):Boolean { writes+=kbps;return true }
    }
    @Test fun nullSplitUsesVideoUnitsAndHidesRecommendation()=runBlocking {
        val fake=Fake(status(units(split=null)))
        val controller=NovaLiveBitrateController(fake,"stream",1,true,true)
        controller.observe(fake.current,tableRecommendedKbps=80000)
        assertEquals(NovaBitrateUnits.VIDEO,controller.state.value.units)
        assertEquals(fake.current.bitrateUnits!!.liveEncoderKbps,controller.state.value.requestedKbps)
        assertNull(controller.state.value.recommendedKbps)
        assertEquals(NovaBitrateChange.UNAVAILABLE,controller.useRecommended())
        assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
        assertEquals(listOf(fake.current.bitrateUnits!!.liveEncoderKbps+5000),fake.writes)
    }
    @Test fun nullOrMissingNewContractCannotFallBackToPyrowaveAssumptions()=runBlocking {
        for(json in listOf(units(split=null),null)) {
            val fake=Fake(status(json,"pyrowave"))
            val controller=NovaLiveBitrateController(fake,"stream",1,true,true)
            controller.observe(fake.current,tableRecommendedKbps=100000)
            assertEquals(NovaBitrateUnits.UNKNOWN,controller.state.value.units)
            assertFalse(controller.state.value.canChange)
            assertNull(controller.state.value.recommendedKbps)
            assertEquals(NovaBitrateChange.UNAVAILABLE,controller.step(1));assertTrue(fake.writes.isEmpty())
        }
    }
    @Test fun negotiatedReadoutUsesExactSplitAndDoesNotApplyWarpAgain()=runBlocking {
        val fake=Fake(status(units(request=1000,split=2000)))
        val controller=NovaLiveBitrateController(fake,"stream",1,true,true)
        controller.observe(fake.current,tableRecommendedKbps=10000)
        assertEquals(2000,controller.state.value.requestedKbps)
        assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
        assertEquals(listOf(NovaBitrateAdvice.encoderForRequest(7000)),fake.writes)
    }
    @Test fun capAndOriginalRequestRemainAvailableAsNegotiationProvenance() {
        val fake=Fake(status(units(request=150000,split=40000,warp=1,cap=40000,source="stability_preset_selected")))
        val controller=NovaLiveBitrateController(fake,"stream",1,true,true)
        controller.observe(fake.current)
        val recorded=controller.state.value.negotiatedUnits
        assertNotNull(recorded)
        assertEquals(150000,recorded!!.requestedKbps);assertEquals(40000,recorded.splitKbps)
        assertEquals(1,recorded.warpFactor);assertEquals(40000,recorded.capKbps)
        assertEquals("stability_preset_selected",recorded.capSource)
        assertEquals(40000,controller.state.value.requestedKbps)
    }
    @Test fun watcherAndUnsplitZeroRequestAreValidMetadataWithoutAFormula() {
        val watcher=PolarisBitrateUnits.parse(units(split=null,encoder=20000))
        assertNotNull(watcher);assertNull(watcher!!.splitKbps)
        val empty=PolarisBitrateUnits.parse(units(request=0,split=null,warp=1,encoder=0))
        assertNotNull(empty);assertEquals(0,empty!!.encoderKbps);assertNull(empty.splitKbps)
        val fake=Fake(status(units(split=null),viewer=true))
        val controller=NovaLiveBitrateController(fake,"stream",1,true,true);controller.observe(fake.current)
        assertFalse(controller.state.value.canChange);assertNull(controller.state.value.recommendedKbps)
    }
    @Test fun finalFieldsAreRequiredAndMalformedNumbersCannotEnableTheFormula() {
        for(key in listOf("split_kbps","warp_factor","cap_kbps","cap_source")) {
            val json=units();json.remove(key)
            assertNull(key,PolarisBitrateUnits.parse(json))
        }
        for((key,value) in listOf("split_kbps" to 0,"split_kbps" to "40000","split_kbps" to 40000.5,
            "warp_factor" to 0,"warp_factor" to 2.5,"cap_kbps" to -1,"cap_source" to 42))
            assertNull("$key=$value",PolarisBitrateUnits.parse(units().put(key,value)))
        assertNull(PolarisBitrateUnits.parse(units(cap=40000,source=null)))
        assertNull(PolarisBitrateUnits.parse(units(cap=null,source="stability_preset_selected")))
    }
    @Test fun liveRateAfterATuneIsConvertedInsteadOfReusingTheNegotiatedSplit() {
        val live=NovaBitrateAdvice.encoderForRequest(55000)
        val fake=Fake(status(units(live=live)))
        val controller=NovaLiveBitrateController(fake,"stream",1,true,true);controller.observe(fake.current)
        assertEquals(55000,controller.state.value.requestedKbps)
    }
    @Test fun spacesWithoutTheFeatureAndUnitsStayReadOnly()=runBlocking {
        val fake=Fake(status(null).copy(liveTuningUnavailable=true))
        val controller=NovaLiveBitrateController(fake,"stream",1,false,false);controller.observe(fake.current)
        assertNull(controller.state.value.negotiatedUnits)
        assertFalse(controller.state.value.canChange)
        assertNull(controller.state.value.recommendedKbps)
        assertEquals(NovaBitrateChange.UNAVAILABLE,controller.step(1));assertTrue(fake.writes.isEmpty())
    }
    @Test fun formulaBecomingInapplicableDuringAWriteChangesTheAcknowledgementUnits()=runBlocking {
        val fake=object:Fake(status(units())) {
            override fun setBitrate(kbps:Int,observed:PolarisSessionStatus):Boolean {
                writes+=kbps;current=status(units(split=null,live=kbps));return true
            }
        }
        val controller=NovaLiveBitrateController(fake,"stream",1,true,true);controller.observe(fake.current)
        assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
        assertEquals(NovaBitrateUnits.VIDEO,controller.state.value.units)
        assertEquals(fake.writes.single(),controller.state.value.requestedKbps)
        assertNull(controller.state.value.recommendedKbps)
    }
}

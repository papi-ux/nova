package com.papi.nova.manager

import com.papi.nova.api.*
import com.papi.nova.preferences.NovaBitrateAdvice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NovaLiveBitrateRoundTwoTest {
    private fun owner(encoder:Int,sequence:Long=1)=PolarisSessionStatus("streaming",streamingActive=true,
        appSessionId="round-two",sessionGeneration=1,ownedByClient=true,liveTuningPresent=true,
        encoder=PolarisSessionStatus.EncoderStatus(codec="hevc"),
        liveTuning=LiveTuningStatus(true,"stable",true,"",300000,encoder,encoder,"a".repeat(64),"host",sequence,1,"round-two"))
    private open class Fake(var current:PolarisSessionStatus):NovaLiveBitrateTransport {
        val writes=mutableListOf<Int>()
        override fun status()=current
        override fun setBitrate(kbps:Int,observed:PolarisSessionStatus):Boolean { writes+=kbps;return true }
    }
    @Test fun ordinaryCodecWithoutLinkAssumptionsUsesEncoderUnitsForAnyAudioAndFec()=runBlocking {
        for((wire,audio,fec) in listOf(Triple(10000,192,10),Triple(30000,1536,20))) {
            val encoder=NovaBitrateAdvice.encoderForRequest(wire,audio,fec)
            val fake=Fake(owner(encoder));val controller=NovaLiveBitrateController(fake,"round-two",1,true)
            controller.observe(fake.current,tableRecommendedKbps=wire)
            assertEquals(encoder,controller.state.value.requestedKbps)
            assertNull("A request-unit recommendation cannot be converted without the session's overhead",controller.state.value.recommendedKbps)
            assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
            assertEquals(listOf(encoder+5000),fake.writes)
        }
    }
    @Test fun pollBetweenPreflightReadAndPostCannotUndoAcknowledgement()=runBlocking {
        val fake=object:Fake(owner(30000)) {
            override fun setBitrate(kbps:Int,observed:PolarisSessionStatus):Boolean {
                writes+=kbps;current=owner(kbps,3);return true
            }
        }
        val controller=NovaLiveBitrateController(fake,"round-two",1,true)
        controller.observe(fake.current)
        assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(40000))
        controller.observe(owner(30000,2))
        assertEquals(40000,controller.state.value.requestedKbps)
    }
    @Test fun hostAcknowledgedFloorStopsRepeatedNoOpWrites()=runBlocking {
        val fake=object:Fake(owner(10000)) {
            override fun confirmedKbps()=10000
        }
        val controller=NovaLiveBitrateController(fake,"round-two",1,true)
        controller.observe(fake.current)
        assertEquals(NovaBitrateChange.AT_LIMIT,controller.step(-1))
        assertEquals(10000,controller.state.value.minimumKbps)
        assertEquals(NovaBitrateChange.AT_LIMIT,controller.step(-1))
        assertEquals(1,fake.writes.size)
    }
}

package com.papi.nova.manager

import com.papi.nova.api.LiveTuningStatus
import com.papi.nova.api.PolarisPyrowaveAdvice
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.preferences.NovaBitrateAdvice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NovaLiveBitrateControllerTest {
    private fun status() = PolarisSessionStatus("streaming", streamingActive = true, appSessionId = "session-a",
        sessionGeneration = 7, encoder = PolarisSessionStatus.EncoderStatus(codec="pyrowave"), ownedByClient = true, liveTuningPresent = true,
        liveTuning = LiveTuningStatus(true,"stable",true,"",300000,25987,25987,"a".repeat(64),"host-a",1,7,"session-a"),
        pyrowaveBitrate = PolarisPyrowaveAdvice(1920,1080,120,201125,300000,"advice"))
    private class Fake(var current: PolarisSessionStatus?) : NovaLiveBitrateTransport {
        val writes = mutableListOf<String>(); var succeeds = true;var confirmed: Int? = null
        override fun status() = current
        override fun confirmedKbps() = confirmed
        override fun setBitrate(kbps: Int, observed: PolarisSessionStatus): Boolean { writes += "$kbps:${observed.sessionGeneration}";return succeeds }
    }
    @Test fun recommendedUsesFreshHostGoalAndDoesNotTouchSettings() = runBlocking {
        val fake = Fake(status());val controller = NovaLiveBitrateController(fake,"session-a",7,true)
        controller.observe(status(),195000)
        fake.current = status().copy(pyrowaveBitrate = status().pyrowaveBitrate!!.copy(raiseGoalKbps=210000))
        assertEquals(NovaBitrateChange.APPLIED,controller.useRecommended())
        assertEquals(listOf("${NovaBitrateAdvice.encoderForRequest(210000)}:7"),fake.writes)
        assertEquals(210000,controller.state.value.requestedKbps)
        assertEquals(195000,controller.state.value.receivedKbps)
    }
    @Test fun watchersSpacesInactiveAndReplacedSessionsCannotWrite() = runBlocking {
        val blocked = listOf(status().copy(clientRole="viewer"),status().copy(liveTuningUnavailable=true),
            status().copy(streamingActive=false),status().copy(authorityContractValid=false),status().copy(shutdownRequested=true))
        for (s in blocked) {
            val fake = Fake(s);val controller = NovaLiveBitrateController(fake,"session-a",7,true)
            assertEquals(NovaBitrateChange.UNAVAILABLE,controller.setBitrate(40000))
            assertTrue(fake.writes.isEmpty())
        }
        val fake = Fake(status().copy(sessionGeneration=8));val controller = NovaLiveBitrateController(fake,"session-a",7,true)
        assertEquals(NovaBitrateChange.SESSION_CHANGED,controller.setBitrate(40000))
        assertTrue(fake.writes.isEmpty())
    }
    @Test fun failedWritesAndUnknownTuningDoNotClaimSuccess() = runBlocking {
        val fake = Fake(status());val controller = NovaLiveBitrateController(fake,"session-a",7,true)
        controller.observe(status());fake.succeeds=false
        assertEquals(NovaBitrateChange.FAILED,controller.setBitrate(40000))
        assertEquals(30000,controller.state.value.requestedKbps)
        fake.current = status().copy(liveTuning=null)
        assertEquals(NovaBitrateChange.UNAVAILABLE,controller.setBitrate(40000))
    }
    @Test fun ordinaryCodecsStepFiveMbpsAndMissingTelemetryStaysUnavailable() = runBlocking {
        val fake = Fake(status().copy(encoder=PolarisSessionStatus.EncoderStatus(codec="hevc"),liveTuning=status().liveTuning!!.copy(enabled=false)))
        val controller = NovaLiveBitrateController(fake,"session-a",7,true)
        controller.observe(fake.current,0)
        assertNull(controller.state.value.receivedKbps)
        assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
        assertEquals(NovaBitrateUnits.VIDEO,controller.state.value.units)
        assertEquals(listOf("${25987+5000}:7"),fake.writes)
        controller.observe(null,10000)
        assertFalse(controller.state.value.canChange)
        assertNull(controller.state.value.requestedKbps)
        assertNull(controller.state.value.receivedKbps)
    }
    @Test fun pyrowaveStepsTenPercentOnHalfMbpsGridAndHonorsFreshHostCap() = runBlocking {
        val start = status().copy(liveTuning=status().liveTuning!!.copy(enabled=false,requestedBitrateKbps=NovaBitrateAdvice.encoderForRequest(201125)))
        val fake = Fake(start);val controller = NovaLiveBitrateController(fake,"session-a",7,true)
        controller.observe(start)
        assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
        assertEquals(221000,controller.state.value.requestedKbps)
        assertEquals(NovaBitrateChange.APPLIED,controller.step(-1))
        assertEquals(199000,controller.state.value.requestedKbps)
        fake.current = start.copy(pyrowaveBitrate=start.pyrowaveBitrate!!.copy(raiseGoalKbps=200000,limitedBy="max_bitrate"))
        assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
        assertEquals(200000,controller.state.value.requestedKbps)
        assertEquals(listOf("${NovaBitrateAdvice.encoderForRequest(221000)}:7","${NovaBitrateAdvice.encoderForRequest(199000)}:7","${NovaBitrateAdvice.encoderForRequest(200000)}:7"),fake.writes)
    }
    @Test fun stepsHonorPreflightHostMaximumAndGlobalBounds() = runBlocking {
        for (codec in listOf("h264","hevc","pyrowave")) {
            val start = status().copy(encoder=PolarisSessionStatus.EncoderStatus(codec=codec),
                liveTuning=status().liveTuning!!.copy(enabled=false,requestedBitrateKbps=NovaBitrateAdvice.encoderForRequest(299000)),
                bitrateUnits=com.papi.nova.api.PolarisBitrateUnits(299000,NovaBitrateAdvice.encoderForRequest(299000),NovaBitrateAdvice.encoderForRequest(299000),512,10,splitKbps=299000))
            val fake = Fake(start);val controller = NovaLiveBitrateController(fake,"session-a",7,true,true)
            controller.observe(start)
            assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
            assertEquals(300000,controller.state.value.requestedKbps)
            controller.observe(start,hostMaximumKbps=150000)
            assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
            assertEquals(150000,controller.state.value.requestedKbps)
            fake.current=start.copy(liveTuning=start.liveTuning!!.copy(requestedBitrateKbps=1000,sequence=10),bitrateUnits=start.bitrateUnits!!.copy(liveEncoderKbps=1000))
            controller.observe(fake.current)
            val writesAtFloor=fake.writes.size
            assertEquals(NovaBitrateChange.AT_LIMIT,controller.step(-1))
            assertEquals(NovaBitrateAdvice.requestForEncoder(1000),controller.state.value.requestedKbps)
            assertEquals(writesAtFloor,fake.writes.size)
        }
    }

    @Test fun hostAcknowledgementWinsWhenAnUnadvertisedCapCutsTheRequest() = runBlocking {
        val fake=Fake(status());fake.confirmed=NovaBitrateAdvice.encoderForRequest(150000)
        val controller=NovaLiveBitrateController(fake,"session-a",7,true);controller.observe(status())
        assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(180000))
        assertEquals(150000,controller.state.value.requestedKbps)
        assertEquals(150000,controller.state.value.maximumKbps)
        assertEquals(listOf("${NovaBitrateAdvice.encoderForRequest(180000)}:7"),fake.writes)
    }

}

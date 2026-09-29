package com.papi.nova.manager
import com.papi.nova.api.*
import com.papi.nova.preferences.NovaBitrateAdvice
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
class NovaLiveBitrateReviewRegressionTest {
    private fun owner()=PolarisSessionStatus("streaming",streamingActive=true,appSessionId="session",sessionGeneration=1,
        ownedByClient=true,encoder=PolarisSessionStatus.EncoderStatus(codec="hevc"),liveTuningPresent=true,
        liveTuning=LiveTuningStatus(true,"stable",true,"",300000,25987,25987,"a".repeat(64),"host",1,1,"session"))
    private open class Fake(var observed: PolarisSessionStatus):NovaLiveBitrateTransport {
        var writes=0
        override fun status()=observed
        override fun setBitrate(kbps:Int,observed:PolarisSessionStatus):Boolean { writes++;return true }
    }
    @Test fun releasedHostWithoutStreamScopedCapabilityCannotWrite()=runBlocking {
        val fake=Fake(owner());val controller=NovaLiveBitrateController(fake,"session",1)
        controller.observe(fake.observed)
        assertFalse(controller.state.value.canChange)
        assertEquals(NovaBitrateChange.UNAVAILABLE,controller.setBitrate(40000));assertEquals(0,fake.writes)
    }
    @Test fun unsupportedEncoderCannotWriteEvenWithOwnerAuthority()=runBlocking {
        val fake=Fake(owner().let { it.copy(liveTuning=it.liveTuning!!.copy(supported=false)) })
        val controller=NovaLiveBitrateController(fake,"session",1,true)
        controller.observe(fake.observed)
        assertFalse(controller.state.value.canChange)
        assertEquals(NovaBitrateChange.UNAVAILABLE,controller.setBitrate(40000));assertEquals(0,fake.writes)
    }

    @Test fun requestAndEncoderUnitsMatchPinnedPolarisFixture() = runBlocking {
        assertEquals(160988,NovaBitrateAdvice.encoderForRequest(180000,512,10))
        assertEquals(180000,NovaBitrateAdvice.requestForEncoder(160988,512,10))
        var sent=0
        val fake=object:Fake(owner()) {
            override fun setBitrate(kbps:Int,observed:PolarisSessionStatus):Boolean { sent=kbps;return true }
        }
        val controller=NovaLiveBitrateController(fake,"session",1,true)
        controller.observe(fake.observed)
        assertTrue(controller.state.value.canChange)
        assertEquals(30000,controller.state.value.requestedKbps)
        assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(180000))
        assertEquals(160988,sent);assertEquals(180000,controller.state.value.requestedKbps)
    }
    @Test fun staleObservationDuringWriteCannotUndoAckOrQueuedStep() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val sent=java.util.Collections.synchronizedList(mutableListOf<Int>())
        val fake=object:Fake(owner()) {
            override fun setBitrate(kbps:Int,observed:PolarisSessionStatus):Boolean {
                sent.add(kbps)
                if(sent.size==1) { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
                return true
            }
        }
        val controller=NovaLiveBitrateController(fake,"session",1,true)
        controller.observe(fake.observed,27000,35000)
        val first=async(Dispatchers.Default) { controller.step(1) }
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            controller.observe(fake.observed,28000,35000)
            assertTrue(controller.state.value.busy)
            val second=async(Dispatchers.Default) { controller.step(1) }
            release.countDown()
            assertEquals(NovaBitrateChange.APPLIED,first.await())
            assertEquals(NovaBitrateChange.APPLIED,second.await())
        } finally { release.countDown() }
        assertEquals(listOf(35000,40000).map { NovaBitrateAdvice.encoderForRequest(it) },sent)
        controller.observe(fake.observed,29000,35000)
        assertEquals(40000,controller.state.value.requestedKbps)
        assertEquals(29000,controller.state.value.receivedKbps)
        assertFalse(controller.state.value.busy)
    }
    @Test fun stepUsesFreshStatusAndNewHostSequencesCanStartOver() = runBlocking {
        val fake=Fake(owner());val controller=NovaLiveBitrateController(fake,"session",1,true)
        controller.observe(fake.observed)
        fake.observed=owner().let { it.copy(liveTuning=it.liveTuning!!.copy(sequence=50,requestedBitrateKbps=NovaBitrateAdvice.encoderForRequest(40000))) }
        assertEquals(NovaBitrateChange.APPLIED,controller.step(1))
        assertEquals(45000,controller.state.value.requestedKbps)
        controller.observe(fake.observed)
        fake.observed=owner().let { it.copy(liveTuning=it.liveTuning!!.copy(hostInstance="new-host",sequence=1)) }
        controller.observe(fake.observed)
        controller.observe(fake.observed.let { it.copy(liveTuning=it.liveTuning!!.copy(sequence=2,requestedBitrateKbps=NovaBitrateAdvice.encoderForRequest(50000))) })
        assertEquals(50000,controller.state.value.requestedKbps)
    }
    @Test fun pyroWithoutHostAdviceNeverUsesTableRecommendation()=runBlocking {
        val fake=Fake(owner().copy(encoder=PolarisSessionStatus.EncoderStatus(codec="pyrowave")))
        val controller=NovaLiveBitrateController(fake,"session",1,true)
        controller.observe(fake.observed,tableRecommendedKbps=200000)
        assertNull(controller.state.value.recommendedKbps)
        assertEquals(NovaBitrateChange.UNAVAILABLE,controller.useRecommended());assertEquals(0,fake.writes)
    }
    @Test fun clampedRecommendedBecomesInUseAndLimitPressDoesNotPauseTuning()=runBlocking {
        val fake=Fake(owner());val controller=NovaLiveBitrateController(fake,"session",1,true)
        controller.observe(fake.observed,tableRecommendedKbps=80000,hostMaximumKbps=50000)
        assertEquals(50000,controller.state.value.recommendedKbps)
        assertEquals(NovaBitrateChange.APPLIED,controller.useRecommended())
        assertEquals(NovaBitrateChange.AT_LIMIT,controller.step(1))
        assertEquals(NovaBitrateChange.AT_LIMIT,controller.useRecommended())
        assertEquals(1,fake.writes)
    }
    @Test fun refusalKeepsReadoutAndSessionChangeClearsIt()=runBlocking {
        val fake=Fake(owner());val controller=NovaLiveBitrateController(fake,"session",1,true)
        controller.observe(fake.observed,27000,35000)
        fake.observed=owner().let { it.copy(liveTuning=it.liveTuning!!.copy(supported=false)) }
        assertEquals(NovaBitrateChange.UNAVAILABLE,controller.step(1))
        assertFalse(controller.state.value.canChange)
        assertEquals(27000,controller.state.value.receivedKbps)
        assertEquals(35000,controller.state.value.recommendedKbps)
        fake.observed=owner().copy(sessionGeneration=2)
        assertEquals(NovaBitrateChange.SESSION_CHANGED,controller.step(1))
        assertNull(controller.state.value.requestedKbps)
    }
    @Test fun sessionReplacementAtTransportReadClearsOldAuthority()=runBlocking {
        val fake=object:Fake(owner()) {
            override fun write(encoderKbps:Int,observed:PolarisSessionStatus)=PolarisBitrateWriteResult.SessionChanged
        }
        val controller=NovaLiveBitrateController(fake,"session",1,true);controller.observe(fake.observed)
        assertEquals(NovaBitrateChange.SESSION_CHANGED,controller.step(1))
        assertFalse(controller.state.value.canChange);assertNull(controller.state.value.requestedKbps)
    }
    @Test fun acknowledgementAboveRequestAndAdviceAssumptionsUseWireUnits()=runBlocking {
        val s=owner().copy(pyrowaveBitrate=PolarisPyrowaveAdvice(1920,1080,120,200000,300000,"advice",20,256))
        val fake=object:Fake(s) {
            override fun write(encoderKbps:Int,observed:PolarisSessionStatus):PolarisBitrateWriteResult {
                assertEquals(NovaBitrateAdvice.encoderForRequest(50000,256,20),encoderKbps)
                return PolarisBitrateWriteResult.Applied(NovaBitrateAdvice.encoderForRequest(60000,256,20),observed)
            }
        }
        val controller=NovaLiveBitrateController(fake,"session",1,true)
        assertEquals(NovaBitrateChange.APPLIED,controller.setBitrate(50000))
        assertEquals(60000,controller.state.value.requestedKbps)
    }
}

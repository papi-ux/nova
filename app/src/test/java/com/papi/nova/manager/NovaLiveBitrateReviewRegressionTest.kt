package com.papi.nova.manager
import com.papi.nova.api.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class NovaLiveBitrateReviewRegressionTest {
    private fun owner()=PolarisSessionStatus("streaming",streamingActive=true,appSessionId="session",sessionGeneration=1,
        ownedByClient=true,encoder=PolarisSessionStatus.EncoderStatus(codec="hevc"),liveTuningPresent=true,
        liveTuning=LiveTuningStatus(true,"stable",true,"",300000,30000,30000,"a".repeat(64),"host",1,1,"session"))
    private class Fake(var observed: PolarisSessionStatus):NovaLiveBitrateTransport {
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
        val controller=NovaLiveBitrateController(fake,"session",1)
        controller.observe(fake.observed)
        assertFalse(controller.state.value.canChange)
        assertEquals(NovaBitrateChange.UNAVAILABLE,controller.setBitrate(40000));assertEquals(0,fake.writes)
    }
}

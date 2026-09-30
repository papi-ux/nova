package com.papi.nova.preferences

import org.junit.Assert.*
import org.junit.Test

class NovaBitrateAdviceTest {
    @Test fun tableMatchesApprovedRequestFigures() {
        val cases = listOf(
            listOf(1280,720,60,10000), listOf(1280,720,120,15000),
            listOf(1920,1080,60,20000), listOf(1920,1080,90,25000), listOf(1920,1080,120,30000),
            listOf(2560,1440,60,40000), listOf(2560,1440,120,60000),
            listOf(3840,2160,60,80000), listOf(3840,2160,120,115000),
            listOf(2560,1600,60,45000), listOf(2400,1080,120,40000), listOf(1240,1080,60,15000)
        )
        cases.forEach { c -> assertEquals(c.toString(), c[3], NovaBitrateAdvice.table(c[0], c[1], c[2])) }
    }

    @Test fun hostAdviceIsAlreadyRequestUnitsAndNeverGrossedUpTwice() {
        val advice = NovaBitrateAdvice.recommend(1920,1080,120,NovaCodecChoice.PYROWAVE, hostRaiseGoalKbps = 201125)
        assertEquals(201125, advice.kbps)
        assertEquals(NovaBitrateBasis.HOST_PYROWAVE, advice.basis)
        assertEquals("Auto · 30 Mbps", NovaBitrateAdvice.text(30000,true))
        assertEquals("37 Mbps", NovaBitrateAdvice.text(37000,false))
    }

    @Test fun grossUpInvertsHostSinglePrecisionFormulaToOneKbps() {
        // Independent arithmetic contract from Polaris stream_bitrate.h: FEC, then audio, then protocol.
        fun hostEncoder(request: Int, audio: Int, fec: Int): Int {
            var n = if (fec <= 80) (request / (100.0f / (100 - fec))).toInt() else request
            n -= minOf(audio, n / 5)
            n -= minOf(500, n / 10)
            return n
        }
        for (goal in listOf(1000, 20000, 180000, 270000)) for (fec in listOf(0,10,20,80,81)) {
            val request = NovaBitrateAdvice.requestForEncoder(goal,512,fec)
            assertTrue(hostEncoder(request,512,fec) in goal..goal+1)
            assertTrue(hostEncoder(request-1,512,fec) < goal)
        }
    }

    @Test fun pyrowaveFallbackUsesMeasuredModelAndRequestCap() {
        val advice = NovaBitrateAdvice.recommend(1920,1080,120,NovaCodecChoice.PYROWAVE)
        assertEquals(NovaBitrateBasis.PYROWAVE_MODEL, advice.basis)
        assertTrue(advice.kbps > NovaBitrateAdvice.table(1920,1080,120))
        assertTrue(advice.kbps <= 300000)
    }
}

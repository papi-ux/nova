package com.papi.nova.binding.video

import com.papi.nova.nvstream.jni.MoonBridge
import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both halves of "is this stream ten bit" have to know about this codec.
 *
 * Nothing in either the library or the client asks whether a format is ten bit by naming formats. They
 * ask a mask, on both sides of the JNI, and each side has its own copy of it. A format missing from
 * one of them is not a build error and not a wrong picture either: it is dynamicRangeMode 0 on the
 * wire, or an SDR launch request, and then a PQ BT.2020 swapchain presenting Rec. 709 frames. Dark,
 * oversaturated, and nothing in any log.
 *
 * That is not hypothetical. The library's copy was written as 0xC0AA00 for 0xCAA00, which put both of
 * this codec's ten bit formats a nibble out of the mask and two formats that do not exist into it.
 */
class PyroWaveTenBitNegotiationTest {
    @Test
    fun theClientSideMaskCoversBothTenBitFormats() {
        assertNotEquals(
            "a PyroWave 4:2:0 HDR stream does not read as ten bit, so the launch request asks for SDR",
            0,
            MoonBridge.VIDEO_FORMAT_PYROWAVE_10BIT and MoonBridge.VIDEO_FORMAT_MASK_10BIT,
        )
        assertNotEquals(
            "a PyroWave 4:4:4 HDR stream does not read as ten bit, so the launch request asks for SDR",
            0,
            MoonBridge.VIDEO_FORMAT_PYROWAVE_444_10BIT and MoonBridge.VIDEO_FORMAT_MASK_10BIT,
        )
    }

    @Test
    fun theEightBitFormatsStayOutOfIt() {
        assertNotEquals(0, MoonBridge.VIDEO_FORMAT_PYROWAVE)
        assertTrue(
            "an eight bit PyroWave stream reads as ten bit, which would request HDR for it",
            MoonBridge.VIDEO_FORMAT_PYROWAVE and MoonBridge.VIDEO_FORMAT_MASK_10BIT == 0,
        )
        assertTrue(
            MoonBridge.VIDEO_FORMAT_PYROWAVE_444 and MoonBridge.VIDEO_FORMAT_MASK_10BIT == 0,
        )
    }

    /**
     * The library's copy, read as text because the mask lives in a C header and this is a JVM test.
     * Naming the formats is what makes it right; a literal is what made it wrong.
     */
    @Test
    fun theLibrarySideMaskNamesBothTenBitFormatsRatherThanCountingThem() {
        val header = File("src/main/jni/moonlight-core/moonlight-common-c/src/Limelight.h")
        assertTrue("${header.absolutePath} is missing; is the submodule initialised?", header.exists())
        val text = header.readText()
        val mask = text.substringAfter("#define VIDEO_FORMAT_MASK_10BIT").substringBefore("\n//")
        assertTrue(
            "the library's ten bit mask does not name VIDEO_FORMAT_PYROWAVE_10BIT, so an HDR stream " +
                "announces dynamicRangeMode 0 and the host serves Rec. 709",
            mask.contains("VIDEO_FORMAT_PYROWAVE_10BIT"),
        )
        assertTrue(
            mask.contains("VIDEO_FORMAT_PYROWAVE_444_10BIT"),
        )
    }
}

package com.papi.nova.binding.video

import com.papi.nova.nvstream.jni.MoonBridge
import java.io.File
import org.junit.Assert.assertFalse
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
     * The library's copy, read out of the patch that writes it.
     *
     * Not out of the header. The mask lives in moonlight-common-c, this repository pins that submodule
     * at its upstream commit, and the protocol changes are carried as patches beside it. So a checkout
     * that has never had the patches applied is the normal case, and it is the only case CI ever sees.
     * A guard that read the header would pass on a developer's patched tree and fail in CI for a
     * reason that has nothing to do with the mask.
     *
     * The patch is what this repository actually owns, so the patch is what gets guarded.
     */
    @Test
    fun theLibrarySideMaskNamesBothTenBitFormatsRatherThanCountingThem() {
        val patches = File("src/main/jni/moonlight-core/patches")
        assertTrue("${patches.absolutePath} is missing", patches.isDirectory)
        // Three patches write this mask in turn, each widening it, so the one that decides what the
        // header ends up saying is the last one applied. Patches apply in name order, so that is the
        // last by name. Guarding the set would fail on the intermediate values, which are correct for
        // the patch they belong to and wrong for the header.
        val defining =
            patches
                .listFiles { file -> file.name.endsWith(".patch") }
                .orEmpty()
                .filter { it.readText().contains("+#define VIDEO_FORMAT_MASK_10BIT") }
                .sortedBy { it.name }
        assertTrue("no patch defines the ten bit mask at all", defining.isNotEmpty())

        // The mask is a multi line macro, so take the added lines from the #define to the last
        // continuation. Anything past that belongs to the next hunk.
        val added =
            defining
                .last()
                .readLines()
                .dropWhile { !it.startsWith("+#define VIDEO_FORMAT_MASK_10BIT") }
                .takeWhile { it.startsWith("+") }
                .joinToString("\n")

        assertTrue(
            "the library's ten bit mask does not name VIDEO_FORMAT_PYROWAVE_10BIT, so an HDR stream " +
                "announces dynamicRangeMode 0 and the host serves Rec. 709",
            added.contains("VIDEO_FORMAT_PYROWAVE_10BIT"),
        )
        assertTrue(
            added.contains("VIDEO_FORMAT_PYROWAVE_444_10BIT"),
        )
        assertFalse(
            "a literal is what made this mask wrong the first time; name the formats instead",
            added.contains("0xC0AA00"),
        )
    }

    /**
     * And when the patches have been applied, the header has to agree with them.
     *
     * This is the end to end half of the guard above. It can only run on a tree where someone has
     * applied the patches, which is a developer's tree and never CI's, so an unpatched header is not
     * a failure here. What would be a failure is a patched header that disagrees with its own patch.
     */
    @Test
    fun aPatchedHeaderAgreesWithThePatch() {
        val header = File("src/main/jni/moonlight-core/moonlight-common-c/src/Limelight.h")
        if (!header.exists()) return
        val text = header.readText()
        if (!text.contains("VIDEO_FORMAT_PYROWAVE")) return

        val mask = text.substringAfter("#define VIDEO_FORMAT_MASK_10BIT").substringBefore("\n//")
        assertTrue(
            "this header carries the PyroWave formats but its ten bit mask does not name them, so the " +
                "patches are applied only in part",
            mask.contains("VIDEO_FORMAT_PYROWAVE_10BIT") &&
                mask.contains("VIDEO_FORMAT_PYROWAVE_444_10BIT"),
        )
    }
}

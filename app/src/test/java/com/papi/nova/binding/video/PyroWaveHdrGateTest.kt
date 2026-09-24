package com.papi.nova.binding.video

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether Nova asks for HDR at all.
 *
 * It used to ask the renderer whether it had an HEVC Main10 or an AV1 Main10 decoder, which for this
 * codec is honestly no twice over: there is no hardware decoder profile in it to have. So a device
 * with an HDR panel, an HDR host and a renderer built to present PQ BT.2020 was told "Decoder does not
 * support HDR10 profile" and given an SDR stream, and no amount of work on the host could be reached
 * from the client.
 *
 * The wiring is three files apart and there is nothing to instantiate in a JVM test, so it is pinned
 * as text, the way this repository pins the rest of Game.kt's launch decisions.
 */
class PyroWaveHdrGateTest {
    @Test
    fun theGateAsksWhetherTheRendererCanTakeHdrAtAll() {
        val game = File("src/main/java/com/papi/nova/Game.kt").readText()
        assertTrue(
            "the HDR gate is back to asking about two hardware decoder profiles, which this codec " +
                "does not have, so its HDR path cannot be reached",
            game.contains("if (willStreamHdr && !decoderRenderer!!.isHdr10Supported)"),
        )
    }

    @Test
    fun aRendererWhoseCodecCarriesHdrItselfCanSaySo() {
        val renderer = File("src/main/java/com/papi/nova/binding/video/PyroWaveDecoderRenderer.kt").readText()
        assertTrue(
            "this renderer no longer claims HDR10, so the gate above turns every HDR stream on this " +
                "codec into an SDR one",
            renderer.contains("override val isHdr10Supported: Boolean = true"),
        )
    }

    @Test
    fun theHostIsAskedWhetherItServesHdrAndNotWhichCodecItServesItWith() {
        val connection = File("src/main/java/com/papi/nova/nvstream/NvConnection.kt").readText()
        assertTrue(
            "the host HDR capability check is back to two literal HEVC and AV1 bits, so a host that " +
                "serves HDR only on this codec is told its GPU cannot stream HDR",
            connection.contains("(context.serverCodecModeSupport and HOST_SERVES_HDR) == 0"),
        )
        assertTrue(
            "the PyroWave HDR10 capability bit is not one of the bits that mean the host serves HDR",
            connection.contains("private const val HOST_SERVES_HDR = 0x200 or 0x20000 or 0x02000000"),
        )
    }

    @Test
    fun theDefaultIsStillTheTwoHardwareProfiles() {
        val base = File("src/main/java/com/papi/nova/binding/video/NovaVideoRenderer.kt").readText()
        val body = base.substringAfter("open val isHdr10Supported: Boolean")
        assertTrue(
            "a MediaCodec renderer's HDR capability is not the two profiles any more, which would " +
                "offer HDR10 to a device that cannot decode it",
            body.startsWith("\n        get() = isHevcMain10Hdr10Supported || isAv1Main10Supported"),
        )
    }
}

package com.papi.nova.diagnostics

import com.papi.nova.diagnostics.NovaMomentReport.Readings
import com.papi.nova.diagnostics.NovaMomentReport.Sample
import com.papi.nova.diagnostics.NovaMomentReport.StreamRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaMomentReportTest {
    private val now = 1_000_000L

    private fun sample(
        at: Long = now - 500L,
        codec: String = "c2.qti.hevc.decoder",
        renderedFps: Double = 59.8,
        lossPct: Double = 1.25,
        rttMs: Int = 18,
        decodeMs: Double = 3.4,
    ) = Sample(
        codec = codec, width = 1920, height = 1080, renderedFps = renderedFps, receivedFps = 60.1,
        decodeTimeMs = decodeMs, rttMs = rttMs, rttVarianceMs = 3, packetLossPct = lossPct,
        hostProcessingLatencyMs = 4.5, framesExpected = 12_000, framesLost = 150, monotonicTimestampMs = at,
    )

    private fun capture(
        sample: Sample? = sample(),
        watching: Boolean = false,
        space: Boolean = false,
        fps: Float = 120f,
        version: String = "1.4.14-beta.2",
    ) = NovaMomentReport.capture(
        markedAtUtc = "2026-10-08T11:30:00Z", nowElapsedMs = now, novaVersion = version, buildType = "preRelease",
        androidRelease = "15", androidSdk = 35, device = "Retroid Pocket 5", watching = watching, space = space,
        requestedWidth = 2560, requestedHeight = 1440, requestedFps = fps, requestedBitrateKbps = 40_000,
        requestedCodec = "FORCE_AV1", sample = sample,
    )

    @Test fun requestedSettingsAndObservedDecoderReadingsStaySeparate() {
        val snapshot = capture()
        assertEquals(Readings.CURRENT, snapshot.readings)
        assertEquals(500L, snapshot.sampleAgeMs)
        // Asked for 2560x1440 at 120 FPS in AV1; the decoder actually ran HEVC at 1920x1080, ~60.
        assertEquals(2560, snapshot.requested.width)
        assertEquals(120.0, snapshot.requested.fps!!, 0.0)
        assertEquals("av1", snapshot.requested.codecPreference)
        val observed = snapshot.observed!!
        assertEquals("hevc", observed.codec)
        assertEquals("c2.qti.hevc.decoder", observed.decoder)
        assertEquals(1920, observed.width)
        assertEquals(59.8, observed.renderedFps!!, 0.0)
        val json = NovaMomentReport.toJson(snapshot)
        assertEquals(120.0, json.getJSONObject("requested_settings").getDouble("fps"), 0.0)
        assertEquals(59.8, json.getJSONObject("client_decoder").getDouble("rendered_fps"), 0.0)
    }

    @Test fun decoderFrameLossIsLabelledAsMediaLossNeverControlLoss() {
        val json = NovaMomentReport.toJson(capture())
        val decoder = json.getJSONObject("client_decoder")
        assertEquals(1.25, decoder.getDouble("frame_loss_pct"), 0.0)
        assertEquals("decoder_video_frames_in_sample_window", decoder.getString("frame_loss_scope"))
        val text = json.toString()
        assertFalse(text.contains("packet_loss"))
        assertFalse(json.getJSONObject("control_channel").has("loss_pct"))
        assertEquals("control_stream_round_trip_estimate", json.getJSONObject("control_channel").getString("scope"))
        assertTrue(json.getJSONArray("not_included").toString().contains("control_channel_loss"))
    }

    @Test fun staleFutureAndMissingSamplesAreNeverPromotedToCurrent() {
        val stale = capture(sample(at = now - NovaMomentReport.SAMPLE_FRESH_MS - 1))
        assertEquals(Readings.STALE, stale.readings)
        assertEquals(NovaMomentReport.SAMPLE_FRESH_MS + 1, stale.sampleAgeMs)
        assertNull(stale.observed)
        val edge = capture(sample(at = now - NovaMomentReport.SAMPLE_FRESH_MS))
        assertEquals(Readings.CURRENT, edge.readings)
        val future = capture(sample(at = now + 10))
        assertEquals(Readings.STALE, future.readings)
        assertNull(future.observed)
        val missing = capture(sample = null)
        assertEquals(Readings.UNAVAILABLE, missing.readings)
        assertNull(missing.sampleAgeMs)
        val unset = capture(sample(at = 0L))
        assertEquals(Readings.UNAVAILABLE, unset.readings)
        val decoder = NovaMomentReport.toJson(stale).getJSONObject("client_decoder")
        assertEquals("stale", decoder.getString("readings"))
        assertTrue(decoder.isNull("rendered_fps"))
        assertTrue(decoder.isNull("frame_loss_pct"))
        assertTrue(NovaMomentReport.toJson(stale).getJSONObject("control_channel").isNull("rtt_ms"))
    }

    @Test fun nonFiniteNegativeAndOutOfRangeReadingsBecomeNull() {
        val bad = capture(sample(renderedFps = Double.NaN, lossPct = Double.POSITIVE_INFINITY, rttMs = 0, decodeMs = -1.0), fps = Float.NaN)
        val observed = bad.observed!!
        assertNull(observed.renderedFps)
        assertNull(observed.decoderFrameLossPct)
        assertNull(observed.decodeTimeMs)
        // A zero RTT is MoonBridge's "no estimate yet", not a measured 0 ms.
        assertNull(observed.controlRttMs)
        assertNull(observed.controlRttVarianceMs)
        assertNull(bad.requested.fps)
        assertNull(capture(sample(lossPct = 140.0)).observed!!.decoderFrameLossPct)
        // JSON carries explicit nulls, never NaN.
        val json = NovaMomentReport.toJson(bad).toString()
        assertFalse(json.contains("NaN"))
        assertFalse(json.contains("Infinity"))
    }

    @Test fun theSchemaIsClosed() {
        val json = NovaMomentReport.toJson(capture())
        assertEquals(setOf("kind", "marked_at_utc", "client", "stream", "requested_settings", "client_decoder", "control_channel", "not_included"), json.keys().asSequence().toSet())
        assertEquals(setOf("app", "version", "build_type", "android_release", "android_sdk", "device"), json.getJSONObject("client").keys().asSequence().toSet())
        assertEquals(setOf("active_on_this_device", "role"), json.getJSONObject("stream").keys().asSequence().toSet())
        assertEquals(setOf("width", "height", "fps", "bitrate_kbps", "codec_preference"), json.getJSONObject("requested_settings").keys().asSequence().toSet())
        assertEquals(setOf("readings", "sample_age_ms", "codec", "decoder", "width", "height", "rendered_fps", "received_fps",
            "decode_time_ms", "host_processing_latency_ms", "frame_loss_pct", "frame_loss_scope", "frames_expected", "frames_lost"),
            json.getJSONObject("client_decoder").keys().asSequence().toSet())
        assertEquals(setOf("rtt_ms", "rtt_variance_ms", "scope"), json.getJSONObject("control_channel").keys().asSequence().toSet())
        assertEquals(NovaMomentReport.KIND, json.getString("kind"))
        // No field anywhere names an identity, command scope, endpoint or commit.
        fun keys(value: Any?): List<String> = when (value) {
            is JSONObject -> value.keys().asSequence().flatMap { sequenceOf(it) + keys(value.get(it)).asSequence() }.toList()
            is org.json.JSONArray -> (0 until value.length()).flatMap { keys(value.get(it)) }
            else -> emptyList()
        }
        val exact = setOf("host", "host_name", "hostname", "ip", "pc", "server", "game", "app_id")
        val parts = listOf("session", "unique", "uuid", "cert", "pairing", "address", "commit", "token", "owner")
        for (key in keys(json)) {
            val name = key.lowercase()
            assertFalse("$key is an identity field", name in exact || parts.any { it in name })
        }
        // What is not included is said, as labels only.
        assertEquals(NovaMomentReport.NOT_INCLUDED, (0 until json.getJSONArray("not_included").length()).map { json.getJSONArray("not_included").getString(it) })
    }

    @Test fun zeroFrameWindowsAndMissingRttAreUnknownNotHealthy() {
        // The renderer writes 0% loss when its window had no frames, and 0 RTT before an estimate.
        val idle = capture(Sample("c2.qti.hevc.decoder", 1920, 1080, 0.0, 0.0, 0.0, 0, 0, 0.0, null, 0, 0, now - 100))
        assertEquals(Readings.CURRENT, idle.readings)
        assertNull(idle.observed!!.decoderFrameLossPct)
        assertNull(idle.observed!!.controlRttMs)
        // No cumulative frames either way: still unknown even with a frame rate.
        val noFrames = capture(Sample("c2.qti.hevc.decoder", 1920, 1080, 60.0, 60.0, 2.0, 12, 1, 0.0, null, 0, 0, now - 100))
        assertNull(noFrames.observed!!.decoderFrameLossPct)
        // A real window with frames reports its loss, including a true zero.
        assertEquals(0.0, capture(sample(lossPct = 0.0)).observed!!.decoderFrameLossPct!!, 0.0)
    }

    @Test fun onlyRealDecoderComponentNamesFillTheDecoderField() {
        for (name in listOf("c2.qti.hevc.decoder", "OMX.qcom.video.decoder.avc", "OMX.google.h264.decoder", "c2.android.av1.decoder"))
            assertEquals(name, capture(sample(codec = name)).observed!!.decoder)
        // Host names, tokens and dotted words that merely look safe are not decoders.
        for (masquerade in listOf("gaming-pc.tail0000.ts.net", "c2.example.ts.net", "OMX.secret-token", "c2.evil",
                "host.decoder.example.com", "6f1c9e2a-session-token-value"))
            assertNull(masquerade, capture(sample(codec = masquerade)).observed!!.decoder)
    }

    @Test fun freeTextInputsCannotCarryIdentityIntoTheSchema() {
        // A decoder "name" or version that is really free text is dropped, not passed through.
        val hostile = capture(sample(codec = "gaming-pc 192.168.1.20 app_session_id=s3cr3t"), version = "1.4 (from 10.0.0.5)")
        assertNull(hostile.observed!!.decoder)
        assertEquals("unknown", hostile.observed!!.codec)
        assertEquals("unknown", hostile.novaVersion)
        val text = NovaMomentReport.toJson(hostile).toString()
        assertFalse(text.contains("192.168"))
        assertFalse(text.contains("s3cr3t"))
        assertFalse(text.contains("10.0.0.5"))
        assertEquals("pyrowave", capture(sample(codec = "PyroWave")).observed!!.codec)
        assertNull(capture(sample(codec = "PyroWave")).observed!!.decoder)
    }

    @Test fun watchingAndSpacesAreLabelledHonestly() {
        assertEquals(StreamRole.PLAYING, capture().role)
        assertEquals("watching", NovaMomentReport.toJson(capture(watching = true)).getJSONObject("stream").getString("role"))
        assertEquals(StreamRole.SPACE, capture(watching = true, space = true).role)
    }

    @Test fun onlyAnActiveLocalStreamCanBeReported() {
        assertTrue(NovaMomentReport.canCapture(streamActive = true, connected = true, finishing = false, destroyed = false))
        assertFalse(NovaMomentReport.canCapture(streamActive = false, connected = true, finishing = false, destroyed = false))
        assertFalse(NovaMomentReport.canCapture(streamActive = true, connected = false, finishing = false, destroyed = false))
        assertFalse(NovaMomentReport.canCapture(streamActive = true, connected = true, finishing = true, destroyed = false))
        assertFalse(NovaMomentReport.canCapture(streamActive = true, connected = true, finishing = false, destroyed = true))
    }

    @Test fun theRenderedReportSaysWhatItIsAndCarriesTheFrozenJson() {
        val snapshot = capture()
        val tail = NovaMomentLog.Tail("line one\nline two\n", 18, 2, truncated = true, bytesRead = 40)
        val text = NovaMomentReport.render(snapshot, tail)
        assertTrue(text.contains("Nothing was sent automatically"))
        assertTrue(text.contains("not a time window"))
        assertTrue(text.contains("No host snapshot is included"))
        assertTrue(text.contains("Troubleshooting"))
        assertTrue(text.contains("no source commit is claimed"))
        assertTrue(text.contains("18 bytes (2 lines)"))
        val json = JSONObject(text.substringAfter("```json\n").substringBefore("\n```"))
        assertEquals(NovaMomentReport.toJson(snapshot).toString(), json.toString())
        assertTrue(NovaMomentReport.render(snapshot, NovaMomentLog.Tail.EMPTY).contains("No local log was available."))
    }
}

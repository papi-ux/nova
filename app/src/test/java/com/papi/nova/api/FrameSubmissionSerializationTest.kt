package com.papi.nova.api

import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.nvstream.jni.NativeFrameSubmissionSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class FrameSubmissionSerializationTest {
    private val snapshot = NativeFrameSubmissionSnapshot("1234567890abcdef1234567890abcdef",
        Long.MAX_VALUE, 9007199254740993L, 0xffffffffL, 12, Long.MAX_VALUE, 1001, 1000)
    private val sample = PerfOverlaySample(60.0, 59.0, 58.0, 1920, 1080, "HEVC", 2, 1, 3.0, 0.0,
        monotonicTimestampMs = 999, framesExpected = 777, framesReceived = 700, sessionGeneration = 99,
        frameSubmission = snapshot)
    private fun body(capable: Boolean = false, observation: NativeFrameSubmissionSnapshot? = snapshot) =
        LiveMediaTelemetryBody.build(sample, "session", 55, 60.0, 120.0, 20000, "private", false,
            observation?.let { com.papi.nova.binding.video.FrameSubmissionReport(it, "abcdef0123456789abcdef0123456789") }, capable)

    @Test fun onlyLiteralPositiveCapabilityEnablesTheExtension() {
        assertFalse(LiveMediaTelemetryBody.frameSubmissionSupported(null))
        assertFalse(LiveMediaTelemetryBody.frameSubmissionSupported(JSONObject()))
        for (value in listOf(false, "true", 1, 1.0, JSONObject.NULL, JSONObject(), listOf(true))) {
            assertFalse(LiveMediaTelemetryBody.frameSubmissionSupported(JSONObject().put("live_media_frame_submission_v1", value)))
        }
        assertTrue(LiveMediaTelemetryBody.frameSubmissionSupported(JSONObject().put("live_media_frame_submission_v1", true)))
    }

    @Test fun unsupportedOrUnavailableObservationsKeepTheLegacyBodyExactly() {
        assertEquals(body(false, null).toString(), body(false).toString())
        assertEquals(body(false, null).toString(), body(true, null).toString())
        assertFalse(body(false).getJSONObject("sample").has("frame_submission"))
    }

    @Test fun exactIntegerWireTypesAndNativePopulationStayIndependentOfJavaCounters() {
        val wire = JSONObject(body(true).toString())
        val legacy = wire.getJSONObject("sample")
        val native = legacy.getJSONObject("frame_submission")
        assertEquals(777, legacy.getInt("frames_expected"))
        assertEquals(99, legacy.getInt("decoder_generation"))
        assertEquals(999, legacy.getInt("monotonic_timestamp_ms"))
        assertEquals(10, native.length())
        assertEquals("abcdef0123456789abcdef0123456789", native.getString("launch_nonce"))
        assertEquals(1, native.getInt("version"))
        assertEquals(snapshot.connectionNonce, native.getString("connection_nonce"))
        for ((key, value) in mapOf("native_epoch" to Long.MAX_VALUE, "snapshot_sequence" to 9007199254740993L,
                "last_frame_index" to 0xffffffffL, "decode_units_submitted_total" to 12L,
                "payload_bytes_total" to Long.MAX_VALUE, "monotonic_timestamp_ms" to 1001L,
                "last_submission_timestamp_ms" to 1000L)) {
            assertTrue(native.get(key) is Int || native.get(key) is Long)
            assertEquals(value, native.getLong(key))
        }
        legacy.remove("frame_submission")
        assertEquals(body(false).toString(), wire.toString())
    }

    @Test fun invalidTypedSnapshotsAreRejectedBeforeSerialization() {
        for (nonce in listOf("0".repeat(32), "A".repeat(32), "abc", "g".repeat(32)))
            assertThrows(IllegalArgumentException::class.java) { snapshot.copy(connectionNonce = nonce) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(nativeEpoch = 0) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(snapshotSequence = 0) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(lastFrameIndex = 0) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(lastFrameIndex = 0x100000000L) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(decodeUnitsSubmittedTotal = 0) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(payloadBytesTotal = -1) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(decodeUnitsSubmittedTotal = 100, lastFrameIndex = 99) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(payloadBytesTotal = 1) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.copy(lastSubmissionTimestampMs = 1002) }
    }
}

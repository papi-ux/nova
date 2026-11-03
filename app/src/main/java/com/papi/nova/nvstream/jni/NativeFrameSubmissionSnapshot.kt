package com.papi.nova.nvstream.jni

import org.json.JSONObject

/** One mutex-coherent complete-DU observation. No Java decoder counters or host clock. */
data class NativeFrameSubmissionSnapshot(
    val connectionNonce: String,
    val nativeEpoch: Long,
    val snapshotSequence: Long,
    val lastFrameIndex: Long,
    val decodeUnitsSubmittedTotal: Long,
    val payloadBytesTotal: Long,
    val monotonicTimestampMs: Long,
    val lastSubmissionTimestampMs: Long,
) {
    init {
        require(connectionNonce.matches(Regex("[0-9a-f]{32}")) && connectionNonce.any { it != '0' })
        require(nativeEpoch > 0 && snapshotSequence > 0 && lastFrameIndex in 1..0xffffffffL)
        require(decodeUnitsSubmittedTotal > 0 && payloadBytesTotal > 0)
        require(decodeUnitsSubmittedTotal <= lastFrameIndex && payloadBytesTotal >= decodeUnitsSubmittedTotal)
        require(monotonicTimestampMs > 0 && lastSubmissionTimestampMs in 1..monotonicTimestampMs)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("connection_nonce", connectionNonce)
        put("native_epoch", nativeEpoch)
        put("snapshot_sequence", snapshotSequence)
        put("last_frame_index", lastFrameIndex)
        put("decode_units_submitted_total", decodeUnitsSubmittedTotal)
        put("payload_bytes_total", payloadBytesTotal)
        put("monotonic_timestamp_ms", monotonicTimestampMs)
        put("last_submission_timestamp_ms", lastSubmissionTimestampMs)
    }
}

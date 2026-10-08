package com.papi.nova.diagnostics

import org.json.JSONObject

/**
 * "Report this moment": what this device saw of the stream at the instant the button was pressed.
 *
 * Captured on the main thread as an immutable [Snapshot] before any file is read or written, so a
 * later poll, sample or menu refresh can never change what the report says about that instant.
 * The schema is closed: every field is listed here, every reading is validated and nullable, and
 * nothing from an API object, a host, a pairing or a command scope is ever carried. In particular
 * there is no app_session_id, host name or address, certificate, pairing field, app UUID or path.
 *
 * Everything here is what the client observed or asked for. Nothing claims what the host did: the
 * host's own half is its Troubleshooting export, which this report points to rather than imitates.
 */
object NovaMomentReport {
    const val KIND = "nova-moment-report-v1"

    /** A decoder sample older than this no longer describes the moment. */
    const val SAMPLE_FRESH_MS = 3_000L

    /** How the stream was being used on this device when the moment was marked. */
    enum class StreamRole(val wire: String) { PLAYING("playing"), WATCHING("watching"), SPACE("space") }

    /** Whether the client decoder readings describe the moment. */
    enum class Readings(val wire: String) {
        /** A decoder sample at most [SAMPLE_FRESH_MS] old. */
        CURRENT("current"),
        /** The last sample is too old, or from the future; its values are not reported as current. */
        STALE("stale"),
        /** No decoder sample has arrived in this stream yet. */
        UNAVAILABLE("unavailable"),
    }

    /** What the settings asked for. Not what the decoder delivered. */
    data class Requested(
        val width: Int?,
        val height: Int?,
        val fps: Double?,
        val bitrateKbps: Int?,
        /** The codec preference ("auto", "h264", "hevc", "av1", "pyrowave"), not the decoder in use. */
        val codecPreference: String,
    )

    /** What the client decoder measured in the sample window that ends at [Snapshot.sampleAgeMs]. */
    data class Observed(
        val codec: String,
        /** The platform decoder component, when it reads as one; never free text. */
        val decoder: String?,
        val width: Int?,
        val height: Int?,
        val renderedFps: Double?,
        val receivedFps: Double?,
        val decodeTimeMs: Double?,
        /** The stream's control-channel round trip, not media latency or loss. */
        val controlRttMs: Double?,
        val controlRttVarianceMs: Double?,
        val hostProcessingLatencyMs: Double?,
        /** Video frames missing in the decoder's sample window: media loss, not control-channel loss. */
        val decoderFrameLossPct: Double?,
        val framesExpected: Long?,
        val framesLost: Long?,
    )

    data class Snapshot(
        val markedAtUtc: String,
        val novaVersion: String,
        val buildType: String,
        val androidRelease: String,
        val androidSdk: Int,
        val device: String,
        val role: StreamRole,
        val requested: Requested,
        val readings: Readings,
        /** Age of the newest decoder sample at the moment; null when there was none. */
        val sampleAgeMs: Long?,
        /** Only for [Readings.CURRENT]; stale or missing samples are never promoted to current. */
        val observed: Observed?,
    )

    /** A decoder sample as the stream holds it; values are checked, not trusted. */
    data class Sample(
        val codec: String,
        val width: Int,
        val height: Int,
        val renderedFps: Double,
        val receivedFps: Double,
        val decodeTimeMs: Double,
        val rttMs: Int,
        val rttVarianceMs: Int,
        val packetLossPct: Double,
        val hostProcessingLatencyMs: Double?,
        val framesExpected: Long,
        val framesLost: Long,
        val monotonicTimestampMs: Long,
    )

    /** Only a stream that is actually up on this device can be reported. */
    @JvmStatic
    fun canCapture(streamActive: Boolean, connected: Boolean, finishing: Boolean, destroyed: Boolean): Boolean =
        streamActive && connected && !finishing && !destroyed

    @JvmStatic
    fun capture(
        markedAtUtc: String,
        nowElapsedMs: Long,
        novaVersion: String,
        buildType: String,
        androidRelease: String,
        androidSdk: Int,
        device: String,
        watching: Boolean,
        space: Boolean,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Float,
        requestedBitrateKbps: Int,
        requestedCodec: String,
        sample: Sample?,
    ): Snapshot {
        val age = sample?.let { nowElapsedMs - it.monotonicTimestampMs }
        val readings = when {
            sample == null || sample.monotonicTimestampMs <= 0L -> Readings.UNAVAILABLE
            age == null || age < 0L || age > SAMPLE_FRESH_MS -> Readings.STALE
            else -> Readings.CURRENT
        }
        return Snapshot(
            markedAtUtc = token(markedAtUtc, UTC_PATTERN) ?: "unknown",
            novaVersion = token(novaVersion, VERSION_PATTERN) ?: "unknown",
            buildType = token(buildType, WORD_PATTERN) ?: "unknown",
            androidRelease = token(androidRelease, VERSION_PATTERN) ?: "unknown",
            androidSdk = androidSdk.takeIf { it in 1..1000 } ?: 0,
            device = deviceLabel(device),
            role = when {
                space -> StreamRole.SPACE
                watching -> StreamRole.WATCHING
                else -> StreamRole.PLAYING
            },
            requested = Requested(
                width = requestedWidth.takeIf { it in 1..16384 },
                height = requestedHeight.takeIf { it in 1..16384 },
                fps = finite(requestedFps.toDouble(), 1.0, 1000.0),
                bitrateKbps = requestedBitrateKbps.takeIf { it in 1..10_000_000 },
                codecPreference = codecPreference(requestedCodec),
            ),
            readings = readings,
            sampleAgeMs = if (readings == Readings.UNAVAILABLE) null else age,
            observed = if (readings != Readings.CURRENT || sample == null) null else Observed(
                codec = codecFamily(sample.codec),
                decoder = decoderName(sample.codec),
                width = sample.width.takeIf { it in 1..16384 },
                height = sample.height.takeIf { it in 1..16384 },
                renderedFps = finite(sample.renderedFps, 0.0, 1000.0),
                receivedFps = finite(sample.receivedFps, 0.0, 1000.0),
                decodeTimeMs = finite(sample.decodeTimeMs, 0.0, 10_000.0),
                // MoonBridge reports 0 before it has an estimate; that is "unknown", not 0 ms.
                controlRttMs = sample.rttMs.takeIf { it in 1..60_000 }?.toDouble(),
                controlRttVarianceMs = sample.rttVarianceMs.takeIf { sample.rttMs in 1..60_000 && it in 0..60_000 }?.toDouble(),
                hostProcessingLatencyMs = sample.hostProcessingLatencyMs?.let { finite(it, 0.0, 10_000.0) },
                // The renderer reports 0 when its window had no frames to count: unknown, not healthy.
                decoderFrameLossPct = finite(sample.packetLossPct, 0.0, 100.0)?.takeIf {
                    sample.framesExpected > 0 &&
                        (finite(sample.receivedFps, 0.0, 1000.0)?.let { it > 0.0 } == true ||
                            finite(sample.renderedFps, 0.0, 1000.0)?.let { it > 0.0 } == true)
                },
                framesExpected = sample.framesExpected.takeIf { it >= 0 },
                framesLost = sample.framesLost.takeIf { it >= 0 && (sample.framesExpected < 0 || it <= sample.framesExpected) },
            ),
        )
    }

    /** The closed JSON object: exactly these keys, in this shape, whatever the inputs held. */
    @JvmStatic
    fun toJson(snapshot: Snapshot): JSONObject {
        val requested = snapshot.requested
        val observed = snapshot.observed
        return JSONObject()
            .put("kind", KIND)
            .put("marked_at_utc", snapshot.markedAtUtc)
            .put("client", JSONObject()
                .put("app", "Nova Android")
                .put("version", snapshot.novaVersion)
                .put("build_type", snapshot.buildType)
                .put("android_release", snapshot.androidRelease)
                .put("android_sdk", snapshot.androidSdk)
                .put("device", snapshot.device))
            .put("stream", JSONObject()
                .put("active_on_this_device", true)
                .put("role", snapshot.role.wire))
            .put("requested_settings", JSONObject()
                .put("width", requested.width ?: JSONObject.NULL)
                .put("height", requested.height ?: JSONObject.NULL)
                .put("fps", requested.fps ?: JSONObject.NULL)
                .put("bitrate_kbps", requested.bitrateKbps ?: JSONObject.NULL)
                .put("codec_preference", requested.codecPreference))
            .put("client_decoder", JSONObject()
                .put("readings", snapshot.readings.wire)
                .put("sample_age_ms", snapshot.sampleAgeMs ?: JSONObject.NULL)
                .put("codec", observed?.codec ?: JSONObject.NULL)
                .put("decoder", observed?.decoder ?: JSONObject.NULL)
                .put("width", observed?.width ?: JSONObject.NULL)
                .put("height", observed?.height ?: JSONObject.NULL)
                .put("rendered_fps", observed?.renderedFps ?: JSONObject.NULL)
                .put("received_fps", observed?.receivedFps ?: JSONObject.NULL)
                .put("decode_time_ms", observed?.decodeTimeMs ?: JSONObject.NULL)
                .put("host_processing_latency_ms", observed?.hostProcessingLatencyMs ?: JSONObject.NULL)
                .put("frame_loss_pct", observed?.decoderFrameLossPct ?: JSONObject.NULL)
                .put("frame_loss_scope", "decoder_video_frames_in_sample_window")
                .put("counter_scope", "decoder_session_cumulative")
                .put("frames_expected", observed?.framesExpected ?: JSONObject.NULL)
                .put("frames_lost", observed?.framesLost ?: JSONObject.NULL))
            .put("control_channel", JSONObject()
                .put("rtt_ms", observed?.controlRttMs ?: JSONObject.NULL)
                .put("rtt_variance_ms", observed?.controlRttVarianceMs ?: JSONObject.NULL)
                .put("scope", "control_stream_round_trip_estimate"))
            .put("not_included", org.json.JSONArray(NOT_INCLUDED))
    }

    /** Facts the report deliberately does not claim, said in the report itself. */
    val NOT_INCLUDED = listOf(
        "host_snapshot",
        "control_channel_loss",
        "installed_source_commit",
        "session_identity",
        "host_name_or_address",
    )

    /** The shareable text: a note on what this is, the frozen JSON, and the bounded log tail. */
    @JvmStatic
    fun render(snapshot: Snapshot, log: NovaMomentLog.Tail): String = buildString {
        appendLine("# Nova moment report")
        appendLine()
        appendLine("> Captured on this device when Report This Moment was pressed, at ${snapshot.markedAtUtc} (the moment, not when this file was shared).")
        appendLine("> Nothing was sent automatically. You chose where to share this file.")
        appendLine()
        appendLine("## What this describes")
        appendLine("- The client decoder readings are ${describe(snapshot)}.")
        appendLine("- Requested settings are what Nova asked for; client decoder readings are what it measured.")
        appendLine("- Frame loss counts missing video frames in the decoder's sample window. It is not control-channel or packet loss.")
        appendLine("- Frames expected and lost are cumulative decoder-session counters, separate from that sample-window percentage.")
        appendLine("- Control-channel round trip is the stream connection's estimate. It is not video latency.")
        appendLine("- No host snapshot is included. For the host side, export the Polaris support bundle from the host's Troubleshooting screen and attach it too.")
        appendLine("- The version is the Nova build that is running; no source commit is claimed.")
        appendLine()
        appendLine("## Moment")
        appendLine("```json")
        appendLine(toJson(snapshot).toString(2))
        appendLine("```")
        appendLine()
        appendLine("## Recent Nova log")
        appendLine(
            "The most recent ${log.bytes} bytes (${log.lines} lines) of Nova's log when the moment was marked, " +
                "redacted. This is a byte tail, not a time window around the moment" +
                (if (log.truncated) "; older lines were left out." else ".") +
                (if (log.changedSinceMoment) " A log file changed after the moment, so its lines were left out." else "")
        )
        if (log.text.isEmpty()) {
            appendLine()
            appendLine("No local log was available.")
        } else {
            appendLine()
            appendLine("```")
            appendLine(log.text.trimEnd('\n'))
            appendLine("```")
        }
    }

    private fun describe(snapshot: Snapshot): String = when (snapshot.readings) {
        Readings.CURRENT -> "from a sample ${snapshot.sampleAgeMs} ms old"
        Readings.STALE -> "unavailable: the newest sample was too old to describe the moment"
        Readings.UNAVAILABLE -> "unavailable: no decoder sample had arrived yet"
    }

    private val UTC_PATTERN = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")
    private val VERSION_PATTERN = Regex("""[0-9A-Za-z][0-9A-Za-z.+_\-]{0,63}""")
    private val WORD_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_]{0,31}""")
    /**
     * Android decoder component names only: c2.* or OMX.*, dotted words, naming a decoder, such as
     * c2.qti.hevc.decoder or OMX.qcom.video.decoder.avc. A host name or token in the codec label
     * does not take this shape and is dropped rather than reported as a decoder.
     */
    private val DECODER_PATTERN = Regex("""(?:c2|OMX)(?:\.[A-Za-z0-9_]{1,24}){2,7}""")

    private fun decoderName(label: String?): String? =
        token(label, DECODER_PATTERN)?.takeIf { it.length <= 96 && it.contains("decoder", ignoreCase = true) }

    private fun token(value: String?, pattern: Regex): String? = value?.trim()?.takeIf { pattern.matches(it) }

    private fun deviceLabel(value: String?): String {
        val cleaned = value.orEmpty().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' || it == '.' }
            .trim().take(64)
        return cleaned.ifEmpty { "unknown" }
    }

    private fun finite(value: Double, min: Double, max: Double): Double? =
        value.takeIf { it.isFinite() && it >= min && it <= max }

    /** The codec family a decoder name or label stands for, from a closed list. */
    @JvmStatic
    fun codecFamily(label: String?): String {
        val text = label.orEmpty().lowercase()
        return when {
            text == "pyrowave" -> "pyrowave"
            "av1" in text || "av01" in text -> "av1"
            "hevc" in text || "h265" in text -> "hevc"
            "avc" in text || "h264" in text -> "h264"
            else -> "unknown"
        }
    }

    private fun codecPreference(value: String?): String = when (value) {
        "AUTO" -> "auto"
        "FORCE_H264" -> "h264"
        "FORCE_HEVC" -> "hevc"
        "FORCE_AV1" -> "av1"
        "FORCE_PYROWAVE" -> "pyrowave"
        else -> "unknown"
    }
}

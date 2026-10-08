package com.papi.nova.diagnostics

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The After-stream recap as a report file the user chose to save: one ended client connection,
 * from this device's samples only, in a closed and bounded schema.
 *
 * It carries no host, app or session identity (the requested host used for matching a Library
 * stays in memory), no process generation, path, address, token, log or decoder free text, no
 * host-side health, and no overall verdict. A model outside the schema's bounds isn't written at
 * all ([render] returns null), so nothing invalid reaches a file.
 */
object NovaStreamRecapReport {
    const val KIND = "nova-stream-recap-report-v1"

    /** The whole rendered file, envelope included, in UTF-8 bytes. */
    const val MAX_BYTES = 24 * 1024

    private const val MAX_COUNT = 100_000_000
    private const val MAX_DURATION_MS = 31L * 24 * 60 * 60 * 1000
    private const val MAX_FRAMES = 1_000_000_000_000L

    private val CODECS = setOf("h264", "hevc", "av1", "pyrowave", "unknown")
    private val CODEC_PREFERENCES = setOf("auto", "h264", "hevc", "av1", "pyrowave", "unknown")
    private val VERSION = Regex("""[0-9A-Za-z][0-9A-Za-z.+_\-]{0,63}""")
    private val WORD = Regex("""[A-Za-z][A-Za-z0-9_]{0,31}""")

    /** Facts the report deliberately does not carry, said in the report itself. */
    val NOT_INCLUDED = listOf(
        "host_identity_or_address",
        "app_or_session_identity",
        "host_diagnostics",
        "measured_throughput",
        "game_or_scene",
        "network_path",
        "picture_audio_or_control_quality",
        "logs",
    )

    private class Invalid : Exception()

    /**
     * The report's bytes for [recap], or null when the recap falls outside the schema or the
     * result would exceed [maxBytes]. [novaVersion] and [buildType] identify the client software
     * only through closed token shapes; anything else becomes "unknown".
     */
    @JvmStatic
    fun render(recap: NovaStreamRecap.Recap, novaVersion: String, buildType: String, maxBytes: Int = MAX_BYTES): ByteArray? {
        val json = try {
            toJson(recap, novaVersion, buildType)
        } catch (_: Invalid) {
            return null
        } catch (_: JSONException) {
            return null
        }
        val bytes = markdown(json).toByteArray(StandardCharsets.UTF_8)
        // MAX_BYTES is a hard cap: a caller may ask for less, never more.
        return bytes.takeIf { it.size <= minOf(maxBytes, MAX_BYTES) }
    }

    /** The closed object: exactly these keys at every level. */
    @JvmStatic
    internal fun toJson(recap: NovaStreamRecap.Recap, novaVersion: String, buildType: String): JSONObject {
        if (!NovaWorkingBaseline.isCalendarUtc(recap.endedAtUtc)) throw Invalid()
        if (recap.errorCode != null && recap.end != NovaStreamRecap.End.ERROR) throw Invalid()
        count(recap.acceptedSamples, allowZero = true)
        count(recap.rejectedSamples, allowZero = true)
        consistent(recap)
        val codecs = recap.codecs
        if (codecs.size > CODECS.size || codecs.toSet().size != codecs.size || codecs.any { it !in CODECS }) throw Invalid()
        val launch = recap.launch
        val requested = launch.requested
        return JSONObject()
            .put("kind", KIND)
            .put("client", JSONObject()
                .put("app", "Nova Android")
                .put("version", novaVersion.takeIf { VERSION.matches(it) } ?: "unknown")
                .put("build_type", buildType.takeIf { WORD.matches(it) } ?: "unknown"))
            .put("ended_at_utc", recap.endedAtUtc)
            .put("end", JSONObject()
                .put("reason", recap.end.name.lowercase())
                .put("error_code", recap.errorCode ?: JSONObject.NULL))
            .put("launch", JSONObject()
                .put("role", launch.role.wire)
                .put("reconnect_requested", launch.reconnectRequested)
                .put("requested_settings", JSONObject()
                    .put("width", nullable(requested.width?.let { size(it) }))
                    .put("height", nullable(requested.height?.let { size(it) }))
                    .put("fps", nullable(requested.fps?.let { number(it, 1.0, 1000.0) }))
                    .put("bitrate_kbps", nullable(requested.bitrateKbps?.also { if (it !in 1..10_000_000) throw Invalid() }))
                    .put("codec_preference", requested.codecPreference.takeIf { it in CODEC_PREFERENCES } ?: throw Invalid())
                    .put("scope", "requested_at_launch_not_measured")))
            .put("connection", JSONObject()
                .put("scope", "this_client_connection_not_host_session")
                .put("connected_for_ms", nullable(recap.connectedForMs?.let { duration(it) }))
                .put("accepted_samples", recap.acceptedSamples)
                .put("rejected_samples", recap.rejectedSamples)
                .put("samples_covered_ms", nullable(recap.samplesCoveredMs?.let { duration(it) })))
            .put("readings", JSONObject()
                .put("aggregation", "unweighted_over_accepted_samples")
                .put("rendered_fps", stat(recap.renderedFps, 0.0, 1000.0, recap.acceptedSamples))
                .put("received_fps", stat(recap.receivedFps, 0.0, 1000.0, recap.acceptedSamples))
                .put("decode_time_ms", stat(recap.decodeTimeMs, 0.0, 10_000.0, recap.acceptedSamples))
                .put("host_processing_latency_ms", stat(recap.hostProcessingLatencyMs, 0.0, 10_000.0, recap.acceptedSamples))
                .put("control_rtt_ms", stat(recap.controlRttMs, 1.0, 60_000.0, recap.acceptedSamples))
                .put("control_rtt_scope", "control_channel_not_video_latency"))
            .put("video_frames", JSONObject()
                .put("between_samples", loss(recap.lossBetweenSamples, intervals = true))
                .put("between_samples_scope", "decoder_counter_deltas_between_accepted_client_samples")
                .put("whole_connection", loss(recap.connectionLoss, intervals = false))
                .put("whole_connection_available", recap.connectionLoss != null)
                .put("whole_connection_scope", "cumulative_decoder_counters_unbroken_first_generation"))
            .put("mixed", JSONObject()
                .put("codecs", JSONArray(codecs))
                .put("dimensions_changed", recap.dimensionsChanged))
            .put("last_sample", last(recap.last))
            .put("not_included", JSONArray(NOT_INCLUDED))
    }

    private fun markdown(json: JSONObject): String = buildString {
        appendLine("# Nova stream recap report")
        appendLine()
        appendLine("> Saved on this device because you chose Save Report. Nothing was sent automatically; you choose where to share it.")
        appendLine()
        appendLine("## What this describes")
        appendLine("- One ended client connection, from samples this device received. Not the host app's whole session.")
        appendLine("- Readings are unweighted averages over accepted client samples, each with its own count, lowest and highest value. Unknown stays null.")
        appendLine("- Requested settings are what Nova asked for at launch. The requested bitrate is not measured throughput.")
        appendLine("- Video frames missing are counted from decoder counters between client samples, and over the whole connection only when those counters were unbroken.")
        appendLine("- Processing latency is reported by the host through frame metadata received by this client. No separate host diagnostics were requested.")
        appendLine("- Control-channel round trip is not video latency. No host diagnostics, game, scene or network path are included, and there is no health grade.")
        appendLine()
        appendLine("## Recap")
        appendLine("```json")
        appendLine(json.toString(2))
        appendLine("```")
    }

    private fun stat(stat: NovaStreamRecap.Stat?, min: Double, max: Double, samples: Int): Any {
        if (stat == null) return JSONObject.NULL
        count(stat.count, allowZero = false)
        if (stat.count > samples) throw Invalid()
        val low = number(stat.min, min, max)
        val high = number(stat.max, min, max)
        val mean = number(stat.mean, min, max)
        val slack = 1e-9 * maxOf(1.0, Math.abs(high))
        if (low > high || mean < low - slack || mean > high + slack) throw Invalid()
        return JSONObject().put("count", stat.count).put("mean", mean).put("min", low).put("max", high)
    }

    private fun loss(loss: NovaStreamRecap.Loss?, intervals: Boolean): Any {
        if (loss == null) return JSONObject.NULL
        if (loss.framesExpected !in 1..MAX_FRAMES || loss.framesLost !in 0..loss.framesExpected) throw Invalid()
        if (intervals && loss.intervals !in 1..MAX_COUNT) throw Invalid()
        if (!intervals && loss.intervals != 0) throw Invalid()
        val out = JSONObject()
        if (intervals) out.put("intervals", loss.intervals)
        return out.put("frames_expected", loss.framesExpected)
            .put("frames_lost", loss.framesLost)
            .put("pct", number(loss.pct, 0.0, 100.0))
    }

    private fun last(last: NovaStreamRecap.LastSample?): Any {
        if (last == null) return JSONObject.NULL
        if (last.codec !in CODECS) throw Invalid()
        return JSONObject()
            .put("age_at_end_ms", nullable(last.ageAtEndMs?.let { duration(it) }))
            .put("codec", last.codec)
            .put("width", nullable(last.width?.let { size(it) }))
            .put("height", nullable(last.height?.let { size(it) }))
            .put("rendered_fps", nullable(last.renderedFps?.let { number(it, 0.0, 1000.0) }))
            .put("received_fps", nullable(last.receivedFps?.let { number(it, 0.0, 1000.0) }))
            .put("decode_time_ms", nullable(last.decodeTimeMs?.let { number(it, 0.0, 10_000.0) }))
            .put("control_rtt_ms", nullable(last.controlRttMs?.let { number(it, 1.0, 60_000.0) }))
    }

    /** The recap's parts must agree with its own sample count before any of it is written. */
    private fun consistent(recap: NovaStreamRecap.Recap) {
        val accepted = recap.acceptedSamples
        val stats = listOf(recap.renderedFps, recap.receivedFps, recap.decodeTimeMs, recap.hostProcessingLatencyMs, recap.controlRttMs)
        if (accepted == 0) {
            if (stats.any { it != null } || recap.lossBetweenSamples != null || recap.connectionLoss != null ||
                recap.codecs.isNotEmpty() || recap.dimensionsChanged || recap.last != null || recap.samplesCoveredMs != null) throw Invalid()
            return
        }
        if (recap.last == null || recap.codecs.isEmpty()) throw Invalid()
        if ((accepted == 1) != (recap.samplesCoveredMs == null)) throw Invalid()
        recap.lossBetweenSamples?.let { if (it.intervals > accepted - 1) throw Invalid() }
    }

    private fun nullable(value: Any?): Any = value ?: JSONObject.NULL

    private fun number(value: Double, min: Double, max: Double): Double =
        value.takeIf { it.isFinite() && it >= min && it <= max } ?: throw Invalid()

    private fun size(value: Int): Int = value.takeIf { it in 1..16_384 } ?: throw Invalid()

    private fun duration(value: Long): Long = value.takeIf { it in 0..MAX_DURATION_MS } ?: throw Invalid()

    private fun count(value: Int, allowZero: Boolean) {
        if (value < (if (allowZero) 0 else 1) || value > MAX_COUNT) throw Invalid()
    }

    /** The closed key sets, for tests and for anyone reading the schema. */
    internal val TOP_KEYS = setOf("kind", "client", "ended_at_utc", "end", "launch", "connection", "readings",
        "video_frames", "mixed", "last_sample", "not_included")
}

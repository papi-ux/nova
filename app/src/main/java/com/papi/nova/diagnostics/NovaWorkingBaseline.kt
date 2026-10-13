package com.papi.nova.diagnostics

import com.papi.nova.binding.video.PerfOverlaySample

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONException
import org.json.JSONObject

/**
 * Working Baseline: the client readings of a stream the user said works well for them, kept on
 * this device to set beside a later stream's readings.
 *
 * A baseline exists only because the user said so, on a confirmation, for a stream running here.
 * Nothing infers one: not a connection, green tests, frame counters or a loss reading of zero.
 * It grants nothing either. It is never Doctor authority and never changes tuning, bitrate,
 * profiles or launch settings.
 *
 * The schema is closed and bounded on both write and read. It holds only the readings of one
 * fresh client sample ([NovaMomentReport.Readings.CURRENT]), what the settings requested, and
 * this client's own version and device. It never holds a log, a host or session identity, a
 * token, a path, or the decoder's cumulative session counters, which mean nothing across sessions.
 *
 * Baselines are grouped by the PC and app entry the stream was launched from ([RequestedEntry]),
 * through a keyed hash ([scope]). That grouping is local bookkeeping. It doesn't show that the
 * game, the scene, the host build or the network path are the same.
 */
object NovaWorkingBaseline {
    const val KIND = "nova-working-baseline-v1"
    const val BASIS = "user_marked_working"
    const val FRAME_LOSS_SCOPE = "decoder_video_frames_in_sample_window"
    const val CONTROL_SCOPE = "control_stream_round_trip_estimate"

    /** The largest stored entry, in UTF-8 bytes, accepted on write and on read. */
    const val MAX_ENTRY_BYTES = 2_048

    /** At most this many PC and app entries keep a baseline on this device. */
    const val MAX_SCOPES = 12

    /** A requested identity longer than this isn't one this client sends, so it isn't grouped. */
    const val MAX_IDENTITY_CHARS = 256

    private const val SCOPE_VERSION = "nova-working-baseline-scope-v1"
    private const val HMAC = "HmacSHA256"

    /**
     * The PC and app entry this stream was launched from, as the launch requested them. Held in
     * memory only: [scope] stores a keyed hash of it, never the values. Either one missing or
     * malformed means there is no entry, and so no baseline; there is no global fallback.
     */
    class RequestedEntry private constructor(internal val pc: String, internal val app: String) {
        override fun toString() = "RequestedEntry(redacted)"

        companion object {
            @JvmStatic
            fun of(pc: String?, app: String?): RequestedEntry? {
                val p = identity(pc) ?: return null
                val a = identity(app) ?: return null
                return RequestedEntry(p, a)
            }

            /** Any non-blank printable value within bounds; the format is the host's, not checked here. */
            private fun identity(value: String?): String? = value?.trim()?.takeIf { text ->
                text.isNotEmpty() && text.length <= MAX_IDENTITY_CHARS && text.none { it.isISOControl() }
            }
        }
    }

    data class Client(val version: String, val buildType: String, val androidSdk: Int, val device: String)

    /** One client sample's readings, each with the scope it was measured in. Null means unknown. */
    data class Readings(
        /** How old the sample was when it was taken: its freshness, not its measurement window. */
        val sampleAgeMs: Long,
        val codec: String,
        val decoder: String?,
        val width: Int?,
        val height: Int?,
        val renderedFps: Double?,
        val receivedFps: Double?,
        val decodeTimeMs: Double?,
        val hostProcessingLatencyMs: Double?,
        /** Video frames missing in the decoder's sample window. Not packet or control-channel loss. */
        val frameLossPct: Double?,
        /** The control channel's round-trip estimate. Not video latency. */
        val controlRttMs: Double?,
        val controlRttVarianceMs: Double?,
    )

    data class Baseline(
        val scope: String,
        val markedAtUtc: String,
        val client: Client,
        val role: NovaMomentReport.StreamRole,
        val requested: NovaMomentReport.Requested,
        val readings: Readings,
    )

    /**
     * The keyed hash naming one PC and app entry's baseline, or null when [key] isn't a usable
     * local key. The key sits beside the baselines in the same app-private file: this keeps the
     * values out of storage, but someone holding a copy of the whole file can still test it
     * against identities they already know.
     */
    @JvmStatic
    fun scope(key: ByteArray?, entry: RequestedEntry): String? {
        if (key == null || key.size != KEY_BYTES) return null
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }
        listOf(SCOPE_VERSION, entry.pc, entry.app).forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            mac.update(bytes)
        }
        return hex(mac.doFinal())
    }

    const val KEY_BYTES = 32

    /**
     * A baseline from the snapshot frozen at the user's Save press, or null unless that snapshot
     * holds a fresh client sample. Everything is taken from the snapshot's own validated values;
     * its cumulative frame counters are left behind.
     */
    @JvmStatic
    fun fromSnapshot(scope: String, snapshot: NovaMomentReport.Snapshot): Baseline? {
        val observed = snapshot.observed ?: return null
        val age = snapshot.sampleAgeMs ?: return null
        if (snapshot.readings != NovaMomentReport.Readings.CURRENT || !SCOPE.matches(scope)) return null
        val baseline = Baseline(
            scope = scope,
            markedAtUtc = snapshot.markedAtUtc,
            client = Client(snapshot.novaVersion, snapshot.buildType, snapshot.androidSdk, snapshot.device),
            role = snapshot.role,
            requested = snapshot.requested,
            readings = Readings(
                sampleAgeMs = age,
                codec = observed.codec,
                decoder = observed.decoder,
                width = observed.width,
                height = observed.height,
                renderedFps = observed.renderedFps,
                receivedFps = observed.receivedFps,
                decodeTimeMs = observed.decodeTimeMs,
                hostProcessingLatencyMs = observed.hostProcessingLatencyMs,
                frameLossPct = observed.decoderFrameLossPct,
                controlRttMs = observed.controlRttMs,
                controlRttVarianceMs = observed.controlRttVarianceMs,
            ),
        )
        // What is written must also read back: one set of bounds for both directions.
        return baseline.takeIf { encode(it) != null }
    }

    /** The stored text, or null if [baseline] falls outside the schema or its size bound. */
    @JvmStatic
    fun encode(baseline: Baseline): String? {
        val text = try {
            toJson(baseline).toString()
        } catch (_: JSONException) {
            return null
        }
        if (!fitsEntry(text)) return null
        return text.takeIf { parse(it, baseline.scope) == baseline }
    }

    private fun toJson(b: Baseline): JSONObject = JSONObject()
        .put("kind", KIND)
        .put("scope", b.scope)
        .put("marked_at_utc", b.markedAtUtc)
        .put("basis", BASIS)
        .put("client", JSONObject()
            .put("version", b.client.version)
            .put("build_type", b.client.buildType)
            .put("android_sdk", b.client.androidSdk)
            .put("device", b.client.device))
        .put("role", b.role.wire)
        .put("requested_settings", JSONObject()
            .put("width", b.requested.width ?: JSONObject.NULL)
            .put("height", b.requested.height ?: JSONObject.NULL)
            .put("fps", b.requested.fps ?: JSONObject.NULL)
            .put("bitrate_kbps", b.requested.bitrateKbps ?: JSONObject.NULL)
            .put("codec_preference", b.requested.codecPreference))
        .put("client_decoder", JSONObject()
            .put("sample_age_ms", b.readings.sampleAgeMs)
            .put("codec", b.readings.codec)
            .put("decoder", b.readings.decoder ?: JSONObject.NULL)
            .put("width", b.readings.width ?: JSONObject.NULL)
            .put("height", b.readings.height ?: JSONObject.NULL)
            .put("rendered_fps", b.readings.renderedFps ?: JSONObject.NULL)
            .put("received_fps", b.readings.receivedFps ?: JSONObject.NULL)
            .put("decode_time_ms", b.readings.decodeTimeMs
                ?.let { PerfOverlaySample.measuredDecodeLatencyMs(b.readings.codec, it) } ?: JSONObject.NULL)
            .put("host_processing_latency_ms", b.readings.hostProcessingLatencyMs ?: JSONObject.NULL)
            .put("frame_loss_pct", b.readings.frameLossPct ?: JSONObject.NULL)
            .put("frame_loss_scope", FRAME_LOSS_SCOPE))
        .put("control_channel", JSONObject()
            .put("rtt_ms", b.readings.controlRttMs ?: JSONObject.NULL)
            .put("rtt_variance_ms", b.readings.controlRttVarianceMs ?: JSONObject.NULL)
            .put("scope", CONTROL_SCOPE))

    private val TOP_KEYS = setOf("kind", "scope", "marked_at_utc", "basis", "client", "role", "requested_settings",
        "client_decoder", "control_channel")
    private val CLIENT_KEYS = setOf("version", "build_type", "android_sdk", "device")
    private val REQUESTED_KEYS = setOf("width", "height", "fps", "bitrate_kbps", "codec_preference")
    private val DECODER_KEYS = setOf("sample_age_ms", "codec", "decoder", "width", "height", "rendered_fps", "received_fps",
        "decode_time_ms", "host_processing_latency_ms", "frame_loss_pct", "frame_loss_scope")
    private val CONTROL_KEYS = setOf("rtt_ms", "rtt_variance_ms", "scope")

    private val CODECS = setOf("h264", "hevc", "av1", "pyrowave", "unknown")
    private val CODEC_PREFERENCES = setOf("auto", "h264", "hevc", "av1", "pyrowave", "unknown")

    private val SCOPE = Regex("""[0-9a-f]{64}""")
    private val UTC = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")
    private val VERSION = Regex("""[0-9A-Za-z][0-9A-Za-z.+_\-]{0,63}""")
    private val WORD = Regex("""[A-Za-z][A-Za-z0-9_]{0,31}""")
    private val DEVICE = Regex("""[\p{L}\p{N} ._\-]{1,64}""")
    private val DECODER = Regex("""(?:c2|OMX)(?:\.[A-Za-z0-9_]{1,24}){2,7}""")

    /**
     * The baseline [text] holds for [expectedScope], or null for anything else: too large, not
     * JSON, a key missing or extra at any level, a value of the wrong type, out of range or not
     * finite, an unknown word, or another scope. Nothing is repaired or guessed.
     */
    @JvmStatic
    fun parse(text: String?, expectedScope: String): Baseline? {
        if (text == null || !fitsEntry(text)) return null
        return try {
            read(JSONObject(text), expectedScope)
        } catch (_: JSONException) {
            null
        } catch (_: Invalid) {
            null
        }
    }

    private class Invalid : Exception()

    private fun read(json: JSONObject, expectedScope: String): Baseline {
        json.closed(TOP_KEYS)
        if (json.word("kind") != KIND || json.word("basis") != BASIS) throw Invalid()
        val scope = json.text("scope", SCOPE)
        if (scope != expectedScope) throw Invalid()
        val client = json.child("client", CLIENT_KEYS)
        val requested = json.child("requested_settings", REQUESTED_KEYS)
        val decoder = json.child("client_decoder", DECODER_KEYS)
        val control = json.child("control_channel", CONTROL_KEYS)
        if (decoder.word("frame_loss_scope") != FRAME_LOSS_SCOPE || control.word("scope") != CONTROL_SCOPE) throw Invalid()
        val role = NovaMomentReport.StreamRole.entries.firstOrNull { it.wire == json.word("role") } ?: throw Invalid()
        val codec = decoder.word("codec").takeIf { it in CODECS } ?: throw Invalid()
        return Baseline(
            scope = scope,
            markedAtUtc = json.text("marked_at_utc", UTC).takeIf(::isCalendarUtc) ?: throw Invalid(),
            client = Client(
                version = client.text("version", VERSION),
                buildType = client.text("build_type", WORD),
                androidSdk = client.integer("android_sdk", 1, 1000)?.toInt() ?: throw Invalid(),
                device = client.text("device", DEVICE),
            ),
            role = role,
            requested = NovaMomentReport.Requested(
                width = requested.integer("width", 1, 16_384)?.toInt(),
                height = requested.integer("height", 1, 16_384)?.toInt(),
                fps = requested.number("fps", 1.0, 1000.0),
                bitrateKbps = requested.integer("bitrate_kbps", 1, 10_000_000)?.toInt(),
                codecPreference = requested.word("codec_preference").takeIf { it in CODEC_PREFERENCES } ?: throw Invalid(),
            ),
            readings = Readings(
                sampleAgeMs = decoder.integer("sample_age_ms", 0, NovaMomentReport.SAMPLE_FRESH_MS) ?: throw Invalid(),
                codec = codec,
                decoder = decoder.optionalText("decoder", DECODER)?.also {
                    if (it.length > 96 || !it.contains("decoder", ignoreCase = true)) throw Invalid()
                },
                width = decoder.integer("width", 1, 16_384)?.toInt(),
                height = decoder.integer("height", 1, 16_384)?.toInt(),
                renderedFps = decoder.number("rendered_fps", 0.0, 1000.0),
                receivedFps = decoder.number("received_fps", 0.0, 1000.0),
                decodeTimeMs = decoder.number("decode_time_ms", 0.0, 10_000.0)
                    ?.let { PerfOverlaySample.measuredDecodeLatencyMs(codec, it) },
                hostProcessingLatencyMs = decoder.number("host_processing_latency_ms", 0.0, 10_000.0),
                frameLossPct = decoder.number("frame_loss_pct", 0.0, 100.0),
                controlRttMs = control.number("rtt_ms", 1.0, 60_000.0),
                controlRttVarianceMs = control.number("rtt_variance_ms", 0.0, 60_000.0),
            ),
        )
    }

    private fun JSONObject.closed(keys: Set<String>) {
        if (length() != keys.size || keys.any { !has(it) }) throw Invalid()
    }

    private fun JSONObject.child(name: String, keys: Set<String>): JSONObject =
        (opt(name) as? JSONObject ?: throw Invalid()).also { it.closed(keys) }

    private fun JSONObject.word(name: String): String = opt(name) as? String ?: throw Invalid()

    private fun JSONObject.text(name: String, pattern: Regex): String =
        word(name).takeIf { pattern.matches(it) } ?: throw Invalid()

    private fun JSONObject.optionalText(name: String, pattern: Regex): String? =
        if (isNull(name)) null else text(name, pattern)

    /** A finite number in [min]..[max], or null for an explicit null; anything else is invalid. */
    private fun JSONObject.number(name: String, min: Double, max: Double): Double? {
        if (isNull(name)) return null
        val value = (opt(name) as? Number ?: throw Invalid()).toDouble()
        if (!value.isFinite() || value < min || value > max) throw Invalid()
        return value
    }

    private fun JSONObject.integer(name: String, min: Long, max: Long): Long? {
        val value = number(name, min.toDouble(), max.toDouble()) ?: return null
        if (value != Math.floor(value)) throw Invalid()
        return value.toLong()
    }

    /** Whether one piece of known client context is the same, different, or unknown on a side. */
    enum class Match { SAME, DIFFERENT, UNKNOWN }

    enum class ContextItem {
        ROLE, REQUESTED_RESOLUTION, REQUESTED_FPS, REQUESTED_BITRATE, REQUESTED_CODEC,
        DECODER_CODEC, DECODER_COMPONENT, DECODED_RESOLUTION, NOVA_VERSION, DEVICE,
    }

    data class ContextLine(val item: ContextItem, val baseline: String?, val now: String?, val match: Match)

    enum class ReadingItem {
        RENDERED_FPS, RECEIVED_FPS, DECODE_TIME_MS, HOST_PROCESSING_LATENCY_MS, FRAME_LOSS_PCT,
        CONTROL_RTT_MS, CONTROL_RTT_VARIANCE_MS,
    }

    /** One reading, baseline beside now. Never subtracted into a score or a verdict. */
    data class ReadingLine(val item: ReadingItem, val baseline: Double?, val now: Double?)

    data class Comparison(
        val baselineMarkedAtUtc: String,
        val baselineSampleAgeMs: Long,
        val nowMarkedAtUtc: String,
        /** Whether the current snapshot had a fresh sample; when false, every current reading is unknown. */
        val nowCurrent: Boolean,
        val nowSampleAgeMs: Long?,
        val context: List<ContextLine>,
        /**
         * Only about the client context listed above: true when every item is known on both sides
         * and the same, false when any known item differs, null otherwise. It says nothing about
         * the game scene, the host or the network path, and it isn't a verdict on the readings.
         */
        val knownClientContextMatches: Boolean?,
        val readings: List<ReadingLine>,
    )

    /**
     * [baseline] beside [now], a snapshot frozen at the Compare press. Reads nothing else and
     * changes nothing. Readings that aren't current stay unknown rather than compared.
     */
    @JvmStatic
    fun compare(baseline: Baseline, now: NovaMomentReport.Snapshot): Comparison {
        val current = now.readings == NovaMomentReport.Readings.CURRENT && now.observed != null
        val observed = if (current) now.observed else null
        fun line(item: ContextItem, before: String?, after: String?) = ContextLine(item, before, after, when {
            before == null || after == null -> Match.UNKNOWN
            before == after -> Match.SAME
            else -> Match.DIFFERENT
        })
        fun known(value: String) = value.takeUnless { it == "unknown" }
        fun size(width: Int?, height: Int?) = if (width != null && height != null) "${width}x$height" else null
        val b = baseline.readings
        val context = listOf(
            line(ContextItem.ROLE, baseline.role.wire, now.role.wire),
            line(ContextItem.REQUESTED_RESOLUTION, size(baseline.requested.width, baseline.requested.height),
                size(now.requested.width, now.requested.height)),
            line(ContextItem.REQUESTED_FPS, baseline.requested.fps?.toString(), now.requested.fps?.toString()),
            line(ContextItem.REQUESTED_BITRATE, baseline.requested.bitrateKbps?.toString(), now.requested.bitrateKbps?.toString()),
            line(ContextItem.REQUESTED_CODEC, known(baseline.requested.codecPreference), known(now.requested.codecPreference)),
            line(ContextItem.DECODER_CODEC, known(b.codec), observed?.codec?.let(::known)),
            line(ContextItem.DECODER_COMPONENT, b.decoder, observed?.decoder),
            line(ContextItem.DECODED_RESOLUTION, size(b.width, b.height), size(observed?.width, observed?.height)),
            line(ContextItem.NOVA_VERSION, known(baseline.client.version), known(now.novaVersion)),
            line(ContextItem.DEVICE, known(baseline.client.device), known(now.device)),
        )
        val matches = when {
            context.any { it.match == Match.DIFFERENT } -> false
            context.all { it.match == Match.SAME } -> true
            else -> null
        }
        return Comparison(
            baselineMarkedAtUtc = baseline.markedAtUtc,
            baselineSampleAgeMs = b.sampleAgeMs,
            nowMarkedAtUtc = now.markedAtUtc,
            nowCurrent = current,
            nowSampleAgeMs = if (current) now.sampleAgeMs else null,
            context = context,
            knownClientContextMatches = matches,
            readings = listOf(
                ReadingLine(ReadingItem.RENDERED_FPS, b.renderedFps, observed?.renderedFps),
                ReadingLine(ReadingItem.RECEIVED_FPS, b.receivedFps, observed?.receivedFps),
                ReadingLine(ReadingItem.DECODE_TIME_MS, b.decodeTimeMs, observed?.decodeTimeMs),
                ReadingLine(ReadingItem.HOST_PROCESSING_LATENCY_MS, b.hostProcessingLatencyMs, observed?.hostProcessingLatencyMs),
                ReadingLine(ReadingItem.FRAME_LOSS_PCT, b.frameLossPct, observed?.decoderFrameLossPct),
                ReadingLine(ReadingItem.CONTROL_RTT_MS, b.controlRttMs, observed?.controlRttMs),
                ReadingLine(ReadingItem.CONTROL_RTT_VARIANCE_MS, b.controlRttVarianceMs, observed?.controlRttVarianceMs),
            ),
        )
    }

    private val HEX = "0123456789abcdef".toCharArray()

    internal fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { value ->
            val byte = value.toInt() and 0xff
            append(HEX[byte ushr 4])
            append(HEX[byte and 0x0f])
        }
    }

    /** A stored key's bytes, or null unless it is exactly [KEY_BYTES] bytes of lowercase hex. */
    internal fun unhex(text: String?): ByteArray? {
        if (text == null || text.length != KEY_BYTES * 2 || !SCOPE.matches(text)) return null
        return ByteArray(KEY_BYTES) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    internal fun isScope(text: String): Boolean = SCOPE.matches(text)

    /**
     * Whether [value] fits one stored entry: at most [MAX_ENTRY_BYTES] UTF-8 bytes. The character
     * count is checked first, since no string longer than that in characters can fit.
     */
    @JvmStatic
    fun fitsEntry(value: String): Boolean =
        value.length <= MAX_ENTRY_BYTES && value.toByteArray(StandardCharsets.UTF_8).size <= MAX_ENTRY_BYTES

    /**
     * A real calendar instant in the stored shape: month, day (with leap years), hour, minute and
     * second all in range. A corrupted time such as 2026-19-45T99:00:00Z is refused rather than
     * sorted as the newest baseline. No clock is consulted: past and future dates stay as written.
     */
    internal fun isCalendarUtc(text: String): Boolean {
        if (!UTC.matches(text)) return false
        val year = text.substring(0, 4).toInt()
        val month = text.substring(5, 7).toInt()
        val day = text.substring(8, 10).toInt()
        val hour = text.substring(11, 13).toInt()
        val minute = text.substring(14, 16).toInt()
        val second = text.substring(17, 19).toInt()
        if (year < 1 || month !in 1..12 || hour > 23 || minute > 59 || second > 59) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day in 1..days
    }
}

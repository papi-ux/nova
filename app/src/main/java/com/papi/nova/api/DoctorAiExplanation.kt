package com.papi.nova.api

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Comparison metadata only. None of these fields grants action or setting authority. */
data class DoctorAiDescriptor(
    val enabled: Boolean, val permitted: Boolean, val available: Boolean,
    val blockedReason: String, val sourceResultId: String,
    val findingFingerprint: String, val controllerRevision: Long,
)

data class DoctorAiScope(
    val appSessionId: String, val sessionGeneration: Long, val sourceResultId: String,
    val findingFingerprint: String, val controllerRevision: Long,
)

data class DoctorAiRequest(val requestId: String, val scope: DoctorAiScope)

data class DoctorAiText(
    val likelyCause: String, val evidence: List<String>, val tryFirst: List<String>, val advancedDetail: String,
)

data class DoctorAiResponse(val text: DoctorAiText? = null, val expiresInMs: Long = 0)

/** A successful HTTP status read is fresh for this optional request for five seconds. */
internal data class DoctorAiReading(
    val supported: Boolean = false, val descriptor: DoctorAiDescriptor? = null,
    val scope: DoctorAiScope? = null, val observedAtMs: Long = 0,
) {
    fun fresh(now: Long) = observedAtMs > 0 && now >= observedAtMs && now - observedAtMs < 5_000
}

/** Synchronous observation avoids losing an observed A -> B -> A to StateFlow conflation. */
internal class DoctorAiReadings {
    @Volatile var current = DoctorAiReading()
        private set
    private val listeners = CopyOnWriteArraySet<(DoctorAiReading) -> Unit>()
    fun observe(reading: DoctorAiReading) {
        current = reading
        listeners.forEach { it(reading) }
    }
    fun listen(listener: (DoctorAiReading) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(current)
        return { listeners.remove(listener) }
    }
}

internal object DoctorAiWire {
    const val MAX_BODY_BYTES = 8192
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val fingerprint = Regex("[0-9a-f]{64}")
    private val snapshot = Regex("[0-9a-f]{32}")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val descriptorKeys = setOf("schema", "enabled", "permitted", "available", "blocked_reason", "source_result_id", "finding_fingerprint", "controller_revision")
    private val responseKeys = setOf("schema", "status", "fallback", "changed", "code", "request_id", "app_session_id", "session_generation", "source_result_id", "snapshot_age_ms", "expires_in_ms", "retry_after_ms", "authority", "may_define_settings", "source", "explanation", "snapshot_id", "finding_fingerprint", "controller_revision")
    private val explanationKeys = setOf("likely_cause", "evidence", "try_first", "advanced_detail", "confidence", "destructive_action_allowed")
    private val blockedReasons = setOf("", "host_ai_disabled", "provider_not_ready", "permission_required", "active_owner_required", "unsupported_scope", "request_busy", "rate_limited", "evidence_unavailable", "server_stopping")
    fun integer(value: Any?): Long? = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }
        ?.takeIf { it in 0..MAX_SAFE_INTEGER }
    private fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    private fun string(value: Any?, max: Int, lines: Boolean = false): String? = (value as? String)?.takeIf { text ->
        text.codePointCount(0, text.length) <= max && text.none { (it < ' ' && !(lines && (it == '\n' || it == '\t'))) || it == '\u007f' } &&
            runCatching { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text)).remaining() <= max * 2 }.getOrDefault(false)
    }
    fun descriptor(json: JSONObject?): DoctorAiDescriptor? = runCatching {
        require(json != null && keys(json) == descriptorKeys && integer(json.opt("schema")) == 1L)
        val enabled = json.opt("enabled") as? Boolean ?: return null
        val permitted = json.opt("permitted") as? Boolean ?: return null
        val available = json.opt("available") as? Boolean ?: return null
        val reason = (json.opt("blocked_reason") as? String)?.takeIf { it in blockedReasons } ?: return null
        val source = string(json.opt("source_result_id"), 256) ?: return null
        val fp = json.opt("finding_fingerprint") as? String ?: return null
        val revision = integer(json.opt("controller_revision")) ?: return null
        require(fp.isEmpty() || fingerprint.matches(fp))
        require(!available || (enabled && permitted && reason.isEmpty() && fingerprint.matches(fp) && revision > 0))
        DoctorAiDescriptor(enabled, permitted, available, reason, source, fp, revision)
    }.getOrNull()

    fun body(request: DoctorAiRequest): String {
        val scope = request.scope
        require(uuid.matches(request.requestId) && string(scope.appSessionId, 256)?.isNotBlank() == true)
        require(scope.sessionGeneration in 1..MAX_SAFE_INTEGER && scope.controllerRevision in 1..MAX_SAFE_INTEGER)
        require(fingerprint.matches(scope.findingFingerprint) && string(scope.sourceResultId, 256) != null)
        return JSONObject().put("schema", 1).put("request_id", request.requestId)
            .put("app_session_id", scope.appSessionId).put("session_generation", scope.sessionGeneration)
            .put("source_result_id", scope.sourceResultId).put("finding_fingerprint", scope.findingFingerprint)
            .put("controller_revision", scope.controllerRevision).toString().also { require(it.toByteArray(Charsets.UTF_8).size <= 1024) }
    }

    /** Refusals and all malformed/unbound responses have one local deterministic fallback. */
    fun response(code: Int, body: String, request: DoctorAiRequest): DoctorAiResponse = runCatching {
        require(code == 200 && body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES)
        val j = StrictDoctorAiJson(body).read()
        require(keys(j) == responseKeys && integer(j.opt("schema")) == 1L)
        require(j.opt("status") == true && j.opt("fallback") == false && j.opt("changed") == false)
        require(j.opt("authority") == "explanation_only" && j.opt("may_define_settings") == false && j.opt("code") == "explained")
        val s = request.scope
        require(j.opt("request_id") == request.requestId && j.opt("app_session_id") == s.appSessionId)
        require(integer(j.opt("session_generation")) == s.sessionGeneration && j.opt("source_result_id") == s.sourceResultId)
        require(j.opt("finding_fingerprint") == s.findingFingerprint && integer(j.opt("controller_revision")) == s.controllerRevision)
        require((j.opt("snapshot_id") as? String)?.let(snapshot::matches) == true)
        val age = integer(j.opt("snapshot_age_ms")) ?: return DoctorAiResponse()
        val expires = integer(j.opt("expires_in_ms")) ?: return DoctorAiResponse()
        require(age < 60_000 && expires in 1..(60_000 - age) && j.isNull("retry_after_ms"))
        val source = j.getJSONObject("source")
        require(keys(source) == setOf("kind", "mode", "informational") && source.opt("kind") == "ai-explanation" && source.opt("mode") == "host-configured" && source.opt("informational") == true)
        val e = j.getJSONObject("explanation")
        require(keys(e) == explanationKeys && e.opt("destructive_action_allowed") == false && e.opt("confidence") in setOf("low", "medium", "high"))
        fun text(key: String, max: Int) = string(e.opt(key), max, lines = true) ?: error("Invalid explanation")
        fun lines(key: String, count: Int): List<String> {
            val a = e.opt(key) as? JSONArray ?: error("Invalid explanation")
            require(a.length() <= count)
            return (0 until a.length()).map { string(a.opt(it), 384, lines = true) ?: error("Invalid explanation") }
        }
        DoctorAiResponse(DoctorAiText(text("likely_cause", 512), lines("evidence", 6), lines("try_first", 3), text("advanced_detail", 1536)), expires)
    }.getOrDefault(DoctorAiResponse())
}

/** Standard JSON only, bounded nesting and duplicate-key refusal before JSONObject can lose keys. */
private class StrictDoctorAiJson(private val text: String) {
    private var at = 0
    fun read(): JSONObject { val result = value(0) as? JSONObject ?: error("Object required"); space(); require(at == text.length); return result }
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(c: Char) { space(); require(at < text.length && text[at++] == c) }
    private fun quoted(): String {
        space(); val start = at; take('"')
        while (at < text.length) {
            val c = text[at++]
            require(c >= ' ')
            if (c == '"') return JSONTokener(text.substring(start, at)).nextValue() as String
            if (c == '\\') {
                require(at < text.length)
                when (text[at++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> { require(at + 4 <= text.length && text.substring(at, at + 4).all { it in "0123456789abcdefABCDEF" }); at += 4 }
                    else -> error("Invalid escape")
                }
            }
        }
        error("Unterminated string")
    }
    private fun value(depth: Int): Any {
        require(depth <= 8); space(); require(at < text.length)
        return when (text[at]) {
            '"' -> quoted()
            '{' -> {
                at++; val result = JSONObject(); space()
                if (at < text.length && text[at] == '}') { at++; result } else {
                    while (true) {
                        val key = quoted(); require(!result.has(key) && result.length() < 64); take(':'); result.put(key, value(depth + 1)); space()
                        if (at < text.length && text[at] == '}') { at++; break }; take(',')
                    }; result
                }
            }
            '[' -> {
                at++; val result = JSONArray(); space()
                if (at < text.length && text[at] == ']') { at++; result } else {
                    while (true) {
                        require(result.length() < 64); result.put(value(depth + 1)); space()
                        if (at < text.length && text[at] == ']') { at++; break }; take(',')
                    }; result
                }
            }
            else -> {
                val start = at
                while (at < text.length && text[at] !in " \t\r\n,]}") at++
                val raw = text.substring(start, at)
                require(raw in setOf("true", "false", "null") || Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(raw))
                JSONTokener(raw).nextValue()
            }
        }
    }
}

/** Uses the supplied paired TLS client; never redirects, retries or recovers through another route. */
internal object DoctorAiHttp {
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).callTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).build()
    fun request(baseUrl: String, request: DoctorAiRequest): Request = Request.Builder()
        .url("$baseUrl/doctor/explanation").header("Connection", "close")
        .post(DoctorAiWire.body(request).toRequestBody("application/json".toMediaType())).build()
    suspend fun execute(call: Call, request: DoctorAiRequest, current: () -> Boolean): DoctorAiResponse {
        if (!current()) { call.cancel(); return DoctorAiResponse() }
        return try {
            executeCancellableHttpCall(call) { response ->
                if (!current() || response.code != 200) return@executeCancellableHttpCall DoctorAiResponse()
                val body = response.body ?: return@executeCancellableHttpCall DoctorAiResponse()
                if (body.contentLength() > DoctorAiWire.MAX_BODY_BYTES) return@executeCancellableHttpCall DoctorAiResponse()
                val bytes = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = input.read(buffer, 0, minOf(buffer.size, DoctorAiWire.MAX_BODY_BYTES + 1 - bytes.size()))
                        if (count < 0) break
                        bytes.write(buffer, 0, count)
                        if (bytes.size() > DoctorAiWire.MAX_BODY_BYTES) return@executeCancellableHttpCall DoctorAiResponse()
                    }
                }
                if (!current()) return@executeCancellableHttpCall DoctorAiResponse()
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                DoctorAiWire.response(response.code, text, request)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { DoctorAiResponse() }
    }
}

package com.papi.nova.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The recent end of Nova's own log, for a moment report: bounded, and scrubbed harder than a
 * general support report.
 *
 * Bounded in bytes on both sides: at most [MAX_BYTES] are ever read from disk, whatever the files
 * hold, and the redacted text handed back is at most [MAX_BYTES] of UTF-8. It is a byte and line
 * tail, the newest lines when the moment was marked, and never a time window around it.
 *
 * [DiagnosticsRedaction] removes credentials by name but not command authority or network
 * identity: app_session_id reads as three ordinary words to it, and addresses and host names pass
 * untouched. Here those go too, by name (session and unique ids, rikey, certificates, pairing,
 * UUIDs, hosts), by shape (IP and MAC addresses, URL hosts, private DNS names, UUIDs, long hex,
 * PEM), and by the exact values this stream knows it must not share.
 */
object NovaMomentLog {
    const val MAX_BYTES = 64 * 1024

    data class Tail(
        /** Redacted text; its UTF-8 encoding is [bytes] long and never over the bound. */
        val text: String,
        val bytes: Int,
        val lines: Int,
        /** Older log lines existed and were left out. */
        val truncated: Boolean,
        /** Raw bytes read from disk, never over the bound. */
        val bytesRead: Int,
    ) {
        companion object {
            val EMPTY = Tail("", 0, 0, false, 0)
        }
    }

    private val GENERATION = Regex("""^${Regex.escape(NovaDiagnostics.LOG_BASE_NAME)}\.(\d+)$""")

    /**
     * A log file and where it ended when the moment was marked. Reading stops there, so lines
     * written after the press (including by the report itself) are not part of the moment.
     */
    data class Part(val file: File, val end: Long)

    /** The rotating log files as they stand now, newest first (generation 0 is being written). */
    @JvmStatic
    fun parts(directory: File): List<Part> =
        directory.listFiles()?.mapNotNull { file ->
            GENERATION.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to file }
        }?.filter { it.second.isFile }?.sortedBy { it.first }?.map { Part(it.second, it.second.length()) }.orEmpty()

    /**
     * Read, scrub and bound the newest log lines up to where the moment froze them.
     *
     * @param parts Newest first, with the length each had at the moment.
     * @param known Exact values to replace, with their replacement labels, such as the host's
     *              address or this pairing's unique id. Longer values are replaced first.
     */
    @JvmStatic
    @JvmOverloads
    fun read(parts: List<Part>, known: Map<String, String> = emptyMap(), maxBytes: Int = MAX_BYTES): Tail {
        require(maxBytes > 0)
        var need = maxBytes.toLong()
        var cutSomething = false
        val chunks = ArrayList<ByteArray>()
        for ((index, part) in parts.withIndex()) {
            if (need <= 0L) {
                cutSomething = cutSomething || parts.drop(index).any { it.end > 0 }
                break
            }
            val chunk = try {
                RandomAccessFile(part.file, "r").use { raf ->
                    // A file that shrank since the moment (rotated) is read to its current end.
                    val length = minOf(part.end, raf.length()).coerceAtLeast(0L)
                    val take = minOf(length, need)
                    if (take < length) cutSomething = true
                    val buffer = ByteArray(take.toInt())
                    raf.seek(length - take)
                    raf.readFully(buffer)
                    buffer
                }
            } catch (error: Exception) {
                continue
            }
            chunks.add(0, chunk)
            need -= chunk.size
        }
        val raw = ByteArray(chunks.sumOf { it.size }).also { out ->
            var at = 0
            for (chunk in chunks) {
                chunk.copyInto(out, at)
                at += chunk.size
            }
        }
        if (raw.isEmpty()) return Tail.EMPTY

        var text = decode(raw)
        // A cut through the oldest file leaves half a line (and maybe half a character): drop it.
        if (cutSomething) text = text.substringAfter('\n', "")
        val (bounded, trimmed) = boundUtf8(scrub(text, known), maxBytes)
        val size = bounded.toByteArray(StandardCharsets.UTF_8).size
        val lines = if (bounded.isEmpty()) 0 else bounded.trimEnd('\n').count { it == '\n' } + 1
        return Tail(bounded, size, lines, cutSomething || trimmed, raw.size)
    }

    private fun decode(bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /**
     * Keep the end of [text] within [maxBytes] of UTF-8: whole lines first, and if one line alone
     * is too long, its end from a character boundary. Returns the text and whether it was cut.
     */
    @JvmStatic
    fun boundUtf8(text: String, maxBytes: Int): Pair<String, Boolean> {
        if (text.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return text to false
        var start = 0
        var size = text.toByteArray(StandardCharsets.UTF_8).size
        while (size > maxBytes) {
            val newline = text.indexOf('\n', start)
            if (newline < 0 || newline + 1 >= text.length) break
            size -= text.substring(start, newline + 1).toByteArray(StandardCharsets.UTF_8).size
            start = newline + 1
        }
        var tail = text.substring(start)
        if (tail.toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
            // One line longer than the bound: keep its end, starting on a whole code point.
            var cut = tail.length
            var bytes = 0
            while (cut > 0) {
                val codePoint = tail.codePointBefore(cut)
                val width = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
                if (bytes + width > maxBytes) break
                bytes += width
                cut -= Character.charCount(codePoint)
            }
            tail = tail.substring(cut)
        }
        return tail to true
    }

    /** Names whose values identify this device's pairing, the session, or a network endpoint. */
    private val IDENTITY_NAME_PARTS = listOf(
        "sessionid", "sessiontoken", "uniqueid", "rikey", "cert", "pairing", "challenge", "salt",
        "uuid", "commandkey", "ownerid", "deviceid", "serverid",
    )
    /** Endings that name an endpoint; "host" alone would also catch metrics like hostProcessingLatency. */
    private val IDENTITY_NAME_ENDINGS = listOf("address", "addr", "hostname", "host", "ssid")
    private val IDENTITY_NAME_EXACT = setOf("ip", "mac", "pin", "pcname", "computername", "servername")

    /** Whether a `name=value` or `"name": value` label carries an identity or command authority. */
    @JvmStatic
    fun isIdentityName(label: String): Boolean {
        val normalized = label.lowercase().filter { it.isLetterOrDigit() }
        if (normalized.isEmpty()) return false
        return normalized in IDENTITY_NAME_EXACT ||
            IDENTITY_NAME_PARTS.any { it in normalized } ||
            IDENTITY_NAME_ENDINGS.any { normalized.endsWith(it) }
    }

    private val PEM = Regex("""-----BEGIN [A-Z ]+-----[\s\S]*?(-----END [A-Z ]+-----|$)""")
    private val URL_HOST = Regex("""\b((?:https?|wss?|rtsp)://)(?:[^\s/@]+@)?(\[[^\]\s]+\]|[^\s/:?#]+)""", RegexOption.IGNORE_CASE)
    private val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")
    private val IPV6_CANDIDATE = Regex("""(?<![0-9A-Fa-f:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[A-Za-z0-9]+)?(?![0-9A-Fa-f:])""")
    private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
    private val PRIVATE_DNS = Regex(
        """\b[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\.(?:ts\.net|local|lan|home\.arpa|internal|localdomain|home)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LONG_HEX = Regex("""\b[0-9A-Fa-f]{32,}\b""")

    /**
     * Scrub one block of text; safe to run twice. Placeholders use angle brackets: a bracketed
     * `[ip]:47984` would read as a name and a value on a second pass and lose the port.
     */
    @JvmStatic
    @JvmOverloads
    fun scrub(text: String, known: Map<String, String> = emptyMap()): String {
        if (text.isEmpty()) return text
        var out = text
        for ((value, label) in known.entries.filter { it.key.trim().length >= 3 }.sortedByDescending { it.key.length }) {
            out = out.replace(value.trim(), label, ignoreCase = true)
        }
        out = DiagnosticsRedaction.redact(out, ::isIdentityName)
        out = out.replace(PEM, "<certificate>")
        out = out.replace(URL_HOST) { "${it.groupValues[1]}<host>" }
        out = out.replace(UUID, "<uuid>")
        out = out.replace(MAC, "<mac>")
        // IPv4 first, so an IPv4-mapped IPv6 address cannot leave its last octets behind.
        out = out.replace(IPV4, "<ip>")
        out = out.replace(IPV6_CANDIDATE) { match ->
            val value = match.value
            val hasHex = value.substringBefore('%').any { it.isLetterOrDigit() }
            // Clock times (10:42:07) have no "::" and too few groups; Class::method has no hex.
            if ((value.contains("::") && hasHex) || value.count { it == ':' } == 7) "<ip>" else value
        }
        out = out.replace(PRIVATE_DNS, "<host>")
        out = out.replace(LONG_HEX, "<hex>")
        return out
    }
}

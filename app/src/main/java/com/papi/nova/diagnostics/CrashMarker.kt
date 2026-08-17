package com.papi.nova.diagnostics

/**
 * What Nova knew about itself at the moment it died.
 */
data class CrashRecord(
    val occurredAt: String,
    val version: String,
    val device: String,
    val androidRelease: String,
    val thread: String,
    val stackTrace: String,
)

/**
 * The on-disk crash marker, written as the process is going down and read back
 * on the next launch.
 *
 * Nova had no persistent record of a crash at all. The decoder tombstone
 * counted MediaCodec failures and nothing else, so an ordinary uncaught
 * exception left the user with a system "app has stopped" dialog and left the
 * host with nothing to correlate against.
 *
 * Format and parse are pure and symmetric so the round trip can be tested
 * without staging a real crash on a device.
 */
object CrashMarker {
    const val MAGIC = "nova-crash-v1"
    const val FILE_NAME = "last_crash.txt"

    /** Bound on what is retained, so a recursive stack cannot fill the device. */
    const val MAX_STACK_TRACE_CHARS = 32 * 1024

    private const val OCCURRED_AT = "occurred_at:"
    private const val VERSION = "version:"
    private const val DEVICE = "device:"
    private const val ANDROID = "android:"
    private const val THREAD = "thread:"
    private const val STACK_TRACE = "stacktrace:"

    @JvmStatic
    fun format(record: CrashRecord): String {
        // Everything is redacted on the way in rather than on the way out. The
        // marker sits on disk until someone reads it, and an exception message
        // is a very ordinary place for a token to end up.
        val stack = DiagnosticsRedaction.redact(record.stackTrace).let {
            if (it.length > MAX_STACK_TRACE_CHARS) it.take(MAX_STACK_TRACE_CHARS) + "\n[truncated]" else it
        }
        return buildString {
            appendLine(MAGIC)
            appendLine("$OCCURRED_AT ${single(record.occurredAt)}")
            appendLine("$VERSION ${single(record.version)}")
            appendLine("$DEVICE ${single(record.device)}")
            appendLine("$ANDROID ${single(record.androidRelease)}")
            appendLine("$THREAD ${single(record.thread)}")
            appendLine(STACK_TRACE)
            append(stack)
        }
    }

    @JvmStatic
    fun parse(text: String?): CrashRecord? {
        if (text.isNullOrBlank()) return null
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != MAGIC) return null

        val stackStart = lines.indexOfFirst { it.trim() == STACK_TRACE }
        if (stackStart < 0) return null

        val header = lines.subList(1, stackStart)
        val stack = lines.subList(stackStart + 1, lines.size).joinToString("\n")

        return CrashRecord(
            occurredAt = field(header, OCCURRED_AT),
            version = field(header, VERSION),
            device = field(header, DEVICE),
            androidRelease = field(header, ANDROID),
            thread = field(header, THREAD),
            stackTrace = stack,
        )
    }

    /** Header values are one line each, so a newline in a value would corrupt the record. */
    private fun single(value: String?): String =
        (value ?: "").replace('\n', ' ').replace('\r', ' ').trim()

    private fun field(header: List<String>, label: String): String =
        header.firstOrNull { it.trimStart().startsWith(label) }
            ?.trimStart()
            ?.removePrefix(label)
            ?.trim()
            ?: ""
}

package com.papi.nova.diagnostics

/**
 * The report a Nova user hands to someone who can fix the problem.
 *
 * Pure, so its shape and its redaction can be tested without a device. Every
 * value that reaches it is redacted on the way in rather than trusting the
 * caller, because this text leaves the device by routes Nova does not control.
 */
object NovaSupportReport {
    const val KIND = "nova-support-report-v1"

    @JvmStatic
    @JvmOverloads
    fun build(
        generatedAt: String,
        version: String,
        device: String,
        androidRelease: String,
        crash: CrashRecord? = null,
        logTail: String = "",
        userNotes: String = "",
        host: String = "",
    ): String = buildString {
        appendLine("# Nova support report")
        appendLine()
        appendLine("> Generated on the device. Nothing was sent automatically.")
        appendLine()
        appendLine("- Kind: $KIND")
        appendLine("- Generated: ${clean(generatedAt)}")
        appendLine("- Nova version: ${clean(version)}")
        appendLine("- Device: ${clean(device)}")
        appendLine("- Android: ${clean(androidRelease)}")
        if (host.isNotBlank()) appendLine("- Paired host: ${clean(host)}")

        appendLine()
        appendLine("## What went wrong")
        appendLine(if (userNotes.isBlank()) "Not provided." else DiagnosticsRedaction.redact(userNotes))

        if (crash != null) {
            appendLine()
            appendLine("## Last crash")
            appendLine("- Occurred: ${clean(crash.occurredAt)}")
            appendLine("- Thread: ${clean(crash.thread)}")
            appendLine("- Version that crashed: ${clean(crash.version)}")
            appendLine()
            appendLine("```")
            appendLine(DiagnosticsRedaction.redact(crash.stackTrace).trimEnd())
            appendLine("```")
        }

        appendLine()
        appendLine("## Recent Nova log")
        if (logTail.isBlank()) {
            appendLine("No local log was available.")
        } else {
            appendLine("```")
            appendLine(DiagnosticsRedaction.redact(logTail).trimEnd())
            appendLine("```")
        }

        appendLine()
        appendLine("## Host side")
        appendLine(
            "Export the Polaris support bundle from the host's Troubleshooting screen and attach it " +
                "too. The host half is what says why a stream looked wrong; this half only says what " +
                "the client saw."
        )
    }

    private fun clean(value: String?): String =
        DiagnosticsRedaction.redact(value).replace('\n', ' ').trim().ifEmpty { "unknown" }
}

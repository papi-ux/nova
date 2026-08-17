package com.papi.nova.diagnostics

import org.json.JSONObject

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


    /**
     * The JSON body Polaris expects at /polaris/v1/support/client-report.
     *
     * Built here rather than at the call site so the field names live next to
     * the text report that carries the same facts, and so redaction happens once
     * on the way in. Field names match the host's parser; changing one without
     * changing the other is silent on both sides, which is why the host treats a
     * missing field as absent rather than fatal.
     *
     * Deliberately does not include an identity claim. The host attributes the
     * report to the client certificate it was posted with, so anything this said
     * about who it is would be ignored at best.
     */
    @JvmStatic
    @JvmOverloads
    fun hostPayload(
        version: String,
        device: String,
        androidRelease: String,
        occurredAt: String,
        crash: CrashRecord? = null,
        logTail: String = "",
        userNotes: String = "",
    ): String = JSONObject().apply {
        put("nova_version", DiagnosticsRedaction.redact(version))
        put("device", DiagnosticsRedaction.redact(device))
        put("android_release", DiagnosticsRedaction.redact(androidRelease))
        put("occurred_at", DiagnosticsRedaction.redact(occurredAt))
        put("notes", DiagnosticsRedaction.redact(userNotes))
        put("crash", if (crash == null) "" else DiagnosticsRedaction.redact(crash.stackTrace))
        put("log_tail", DiagnosticsRedaction.redact(logTail))
    }.toString()

    private fun clean(value: String?): String =
        DiagnosticsRedaction.redact(value).replace('\n', ' ').trim().ifEmpty { "unknown" }
}

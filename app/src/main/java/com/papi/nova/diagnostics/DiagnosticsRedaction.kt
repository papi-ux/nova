package com.papi.nova.diagnostics

/**
 * Client-side redaction for anything Nova is about to hand to a human or a host.
 *
 * This deliberately mirrors the rules in the Polaris Web UI's diagnostics-export.js
 * rather than trusting the host to clean up after the client. A report can leave
 * this device by a route that never touches Polaris, such as the Android share
 * sheet, so the guarantee has to hold here on its own.
 *
 * The sensitive word may be one segment of a longer identifier, because
 * `auth_token=` and `api-key=` are the common shapes in a log line. The segment
 * still has to be delimited, so ordinary words that merely contain one of these
 * (keyboard, monkey) survive: over-redaction quietly destroys the diagnostics
 * the report exists to carry.
 */
object DiagnosticsRedaction {
    const val REDACTED = "[redacted]"

    private val URL_CREDENTIAL = Regex(
        """(https?://)([^\s/@:]+):([^\s/@]+)@""",
        RegexOption.IGNORE_CASE,
    )
    private val AUTH_HEADER = Regex(
        """\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+""",
        RegexOption.IGNORE_CASE,
    )
    private val COOKIE_HEADER = Regex(
        """\b(cookie|set-cookie)(\s*[:=]\s*)[^\n;]+""",
        RegexOption.IGNORE_CASE,
    )
    private val SENSITIVE_ASSIGNMENT = Regex(
        """\b((?:[\w.\-]*[._\-])?(?:password|token|secret|key|cookie|auth|credential)(?:[._\-][\w.\-]*)?)(\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,;)}\]]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Field names whose entire value is replaced, matching the host's rules. */
    private val SENSITIVE_FIELD = Regex(
        """(password|token|secret|key|cookie|auth|credential)""",
        RegexOption.IGNORE_CASE,
    )

    @JvmStatic
    fun redact(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return value
            .replace(URL_CREDENTIAL) { "${it.groupValues[1]}$REDACTED:$REDACTED@" }
            .replace(AUTH_HEADER) { "${it.groupValues[1]} $REDACTED" }
            .replace(COOKIE_HEADER) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
            .replace(SENSITIVE_ASSIGNMENT) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
    }

    @JvmStatic
    fun isSensitiveFieldName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return SENSITIVE_FIELD.containsMatchIn(name)
    }
}

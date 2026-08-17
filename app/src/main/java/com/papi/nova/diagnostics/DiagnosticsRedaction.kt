package com.papi.nova.diagnostics

/**
 * Client-side redaction for anything Nova is about to hand to a human or a host.
 *
 * This mirrors the rules in the Polaris Web UI's diagnostics-export.js rather
 * than trusting the host to clean up after the client, because a report can
 * leave this device by a route that never touches Polaris, such as the Android
 * share sheet. Two implementations in two languages can drift, so the tests here
 * deliberately use the same cases as the host's.
 *
 * Three shapes of credential name all have to be caught, and the first version
 * of this file caught only the first:
 *
 *  - separated:      auth_token=, api-key=, session.secret=
 *  - camelCase:      apiKey=, authToken=, accessToken=
 *  - run together:   apikey=, authtoken=, clientsecret=
 *
 * And ordinary words that merely contain one of these have to survive, or
 * redaction quietly destroys the diagnostics a report exists to carry.
 */
object DiagnosticsRedaction {
    const val REDACTED = "[redacted]"

    /** Words that mean "secret" wherever they appear as a whole segment. */
    private val SENSITIVE_SEGMENT_WORDS = listOf(
        "password", "passwd", "token", "secret", "cookie", "auth", "authorization", "credential",
    )

    /**
     * "key" is judged rather than matched. On its own it is a map key or a label
     * far more often than a credential, and it counts only when something
     * qualifies it and that qualifier comes first: apiKey is a credential,
     * keyName names one.
     */
    private val QUALIFIED_ONLY_WORDS = listOf("key")

    /**
     * Qualifiers that make a run-together segment a credential name. `apikey` has
     * no separator and no camelCase hump, and English gives no structural way to
     * tell it from `monkey`: both are a prefix followed by "key". The list is
     * explicit because the distinction is semantic. Polaris made the same call
     * for the same string in its artwork request sanitiser.
     */
    private val CREDENTIAL_QUALIFIERS = listOf(
        "api", "app", "access", "auth", "bearer", "client", "db", "master", "oauth",
        "private", "public", "refresh", "secret", "session", "user",
    )

    private val URL_CREDENTIAL = Regex("""(https?://)([^\s/@:]+):([^\s/@]+)@""", RegexOption.IGNORE_CASE)
    private val AUTH_HEADER = Regex("""\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)
    private val COOKIE_HEADER = Regex("""\b(cookie|set-cookie)(\s*[:=]\s*)[^\n;]+""", RegexOption.IGNORE_CASE)
    private val AUTH_SCHEME_VALUE = Regex("""^(Bearer|Basic)$""", RegexOption.IGNORE_CASE)

    /**
     * A name immediately followed by a separator. The value is deliberately not
     * part of this pattern: consuming it made an innocent pair swallow a
     * sensitive one, and truncating it at a `]` left a stray bracket behind that
     * accumulated on every pass.
     *
     * Which names are sensitive is decided by [isSensitiveIdentifier] and not by
     * a second list encoded in a regex: two independent definitions is what let
     * apiKey= leak on the host side while a field named apiKey was redacted.
     *
     * A closing delimiter may sit between the name and its separator, which is
     * what every quoted JSON key looks like. Requiring the two to be adjacent
     * meant {"api_key": "..."} was never recognised, and Nova parses API
     * responses, so it meets real JSON more than the host does.
     */
    private val NAME_BEFORE_SEPARATOR = Regex("""\b([A-Za-z][\w.\-]*)["')\]]?\s*[:=]\s*""")

    /**
     * A value that is neither a quoted string nor a structure.
     *
     * Structures are measured by balancing rather than by pattern, because a
     * regex cannot see where a nested object ends, and stopping early is worse
     * than not matching at all: it replaces the opening fragment and leaves the
     * secret behind, while reading as though the whole subtree was handled.
     */
    private val BARE_VALUE_AT_START = Regex("""^[^\s,;)}\]]+""")

    private val STRUCTURE_CLOSERS = mapOf('{' to '}', '[' to ']')

    /** Length of a quoted string starting at [start], or 0 if it never closes. */
    private fun quotedExtent(source: String, start: Int): Int {
        val quote = source[start]
        var index = start + 1
        while (index < source.length) {
            if (source[index] == '\\') {
                index += 2
                continue
            }
            if (source[index] == quote) return index - start + 1
            index += 1
        }
        return 0
    }

    /**
     * Length of a balanced object or array starting at [start], or 0 if unbalanced.
     *
     * Quoted spans are skipped so a brace inside a string cannot unbalance it.
     */
    private fun structuredExtent(source: String, start: Int): Int {
        val stack = ArrayDeque<Char>()
        // Nullable rather than a sentinel character: a sentinel has to be a value
        // that cannot appear in the text, and getting that wrong is silent.
        var quote: Char? = null
        var index = start

        while (index < source.length) {
            val character = source[index]
            if (quote != null) {
                if (character == '\\') {
                    index += 2
                    continue
                }
                if (character == quote) quote = null
                index += 1
                continue
            }
            when {
                character == '"' || character == '\'' -> quote = character
                STRUCTURE_CLOSERS.containsKey(character) -> stack.addLast(STRUCTURE_CLOSERS.getValue(character))
                stack.isNotEmpty() && character == stack.last() -> {
                    stack.removeLast()
                    if (stack.isEmpty()) return index - start + 1
                }
            }
            index += 1
        }
        return 0
    }

    /**
     * The value that follows a separator, as text.
     *
     * A value that opens a quote or a structure and never closes it is taken to
     * run to the end of the line. The usual cause is a truncated log line, where
     * everything after the opener is still part of the value, and stopping at the
     * first bare token would leave most of the secret in place. Over-redacting a
     * malformed line is the safe direction.
     */
    private fun valueAt(source: String, start: Int): String {
        if (start >= source.length) return ""
        val first = source[start]
        val opensSomething = first == '"' || first == '\'' || STRUCTURE_CLOSERS.containsKey(first)

        if (opensSomething) {
            val extent = if (first == '"' || first == '\'') {
                quotedExtent(source, start)
            } else {
                structuredExtent(source, start)
            }
            if (extent > 0) return source.substring(start, start + extent)

            val lineEnd = source.indexOf('\n', start)
            return source.substring(start, if (lineEnd == -1) source.length else lineEnd)
        }

        return BARE_VALUE_AT_START.find(source.substring(start))?.value ?: ""
    }

    private fun identifierSegments(name: String?): List<String> =
        (name ?: "")
            .replace(Regex("""([a-z0-9])([A-Z])"""), "$1 $2")
            .split(Regex("""[^A-Za-z0-9]+"""))
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }

    private fun segmentMatches(segment: String, words: List<String>): Boolean =
        words.any { segment == it || segment == "${it}s" }

    private fun isConcatenatedCredential(segment: String): Boolean =
        (SENSITIVE_SEGMENT_WORDS + QUALIFIED_ONLY_WORDS).any { word ->
            listOf(word, "${word}s").any { ending ->
                segment.length > ending.length &&
                    segment.endsWith(ending) &&
                    CREDENTIAL_QUALIFIERS.contains(segment.dropLast(ending.length))
            }
        }

    private fun segmentIsSensitive(segment: String): Boolean =
        segmentMatches(segment, SENSITIVE_SEGMENT_WORDS) || isConcatenatedCredential(segment)

    private fun isSensitiveIdentifier(name: String?, bareQualifiedCounts: Boolean): Boolean {
        val segments = identifierSegments(name)
        if (segments.isEmpty()) return false
        if (segments.any(::segmentIsSensitive)) return true
        if (bareQualifiedCounts && segments.size == 1) {
            return segmentMatches(segments[0], QUALIFIED_ONLY_WORDS)
        }
        return segments.drop(1).any { segmentMatches(it, QUALIFIED_ONLY_WORDS) }
    }

    /**
     * Redact every `name = value` pair whose name reads as a credential.
     *
     * Walks names without consuming their values, so an innocent pair cannot
     * swallow a sensitive one that follows it: `Error: auth_token=abc` is the
     * ordinary shape of a log line, and the credential has to still be visible
     * to the scan after the label in front of it has been skipped.
     *
     * Leaving values unconsumed also makes this idempotent. Nova redacts before
     * it posts a report and the host redacts again on export, so two passes over
     * the same bytes is the normal path rather than an edge case, and a user
     * should not be able to tell how many times it ran by counting brackets.
     *
     * Free text is also the one place a bare `key=` counts as a credential. In a
     * structured payload the surrounding object says what a `key` field is; in a
     * log line nothing does, and losing one diagnostic line costs far less than
     * exporting a secret.
     */
    private fun redactAssignments(text: String): String {
        val output = StringBuilder()
        var cursor = 0
        var searchFrom = 0

        while (true) {
            val match = NAME_BEFORE_SEPARATOR.find(text, searchFrom) ?: break
            val label = match.groupValues[1]
            val valueStart = match.range.last + 1
            val value = valueAt(text, valueStart)

            // Skipping without consuming is what lets the scan find a credential
            // that sits inside an innocent pair's value.
            if (value.isEmpty() ||
                value == REDACTED ||
                AUTH_SCHEME_VALUE.matches(value) ||
                !isSensitiveIdentifier(label, true)
            ) {
                searchFrom = valueStart
                continue
            }

            output.append(text, cursor, valueStart).append(REDACTED)
            cursor = valueStart + value.length
            searchFrom = cursor
        }

        return output.append(text, cursor, text.length).toString()
    }

    @JvmStatic
    fun redact(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return redactAssignments(
            value
                .replace(URL_CREDENTIAL) { "${it.groupValues[1]}$REDACTED:$REDACTED@" }
                .replace(AUTH_HEADER) { "${it.groupValues[1]} $REDACTED" }
                .replace(COOKIE_HEADER) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
        )
    }

    /**
     * Whether an object field should have its value replaced.
     *
     * A bare `key` is a label here, matching the host's rule for the same reason.
     */
    @JvmStatic
    fun isSensitiveFieldName(name: String?): Boolean = isSensitiveIdentifier(name, false)
}

package com.papi.nova.api

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Polaris PR 625 declares PolarisSessionVrr.parse and parseHostPacing as the Nova Android readers
 * of session_vrr and session_vrr_host_pacing, and its contract check requires a reader to read
 * exactly what it declares. This applies the same scope and accessor rules here (the function body
 * up to the next sibling declaration, comments removed, receiver `json`), so a read added or lost
 * fails in Nova before it fails in Polaris.
 */
class PolarisSessionVrrContractReadsTest {
    private val accessors =
        "optString|optBoolean|optInt|optLong|optDouble|optJSONObject|optJSONArray|opt|getString|getBoolean|" +
            "getInt|getLong|getJSONObject|getJSONArray|has|isNull"

    private val source: String
        get() = File("src/main/java/com/papi/nova/api/PolarisSessionVrr.kt").readText()

    private fun reads(function: String): Set<String> {
        val start = Regex("""^([ \t]*)(?:internal |private |public )?fun ${Regex.escape(function)}\(""", RegexOption.MULTILINE)
            .find(source) ?: throw AssertionError("reader not found: $function")
        val rest = source.substring(start.range.last + 1)
        val indent = Regex.escape(start.groupValues[1])
        val next = Regex("""^$indent(?:internal |private |public )?fun \w+\(""", RegexOption.MULTILINE).find(rest)
        val body = (if (next == null) rest else rest.substring(0, next.range.first))
            .replace(Regex("""//[^\n]*|/\*[\s\S]*?\*/"""), "")
        return Regex("""\bjson\s*\??\.\s*(?:$accessors)\s*\(\s*"([a-zA-Z_0-9]+)"""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun parseReadsExactlyTheDeclaredSessionVrrFields() {
        assertEquals(
            setOf(
                "capture_pacing", "effective", "fallback_reason", "host_pacing", "host_permission",
                "pacing_mode", "requested", "requested_from", "schema", "session_generation",
            ),
            reads("parse"),
        )
    }

    @Test
    fun parseHostPacingReadsExactlyFreshAndState() {
        assertEquals(setOf("fresh", "state"), reads("parseHostPacing"))
    }
}

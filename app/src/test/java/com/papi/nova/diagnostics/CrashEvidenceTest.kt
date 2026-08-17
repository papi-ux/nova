package com.papi.nova.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsRedactionTest {

    @Test
    fun redactsCredentialsWhoseNameCarriesAPrefix() {
        // The host had this exact gap: anchoring on a word boundary matched
        // token= and missed auth_token=, and prefixed names are the common case.
        val redacted = DiagnosticsRedaction.redact(
            "auth_token=hunter2 api-key=abc123 session.secret=xyz789 password=\"pw\""
        )

        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("xyz789"))
        assertFalse(redacted.contains("pw"))
        assertTrue(redacted.contains("auth_token=${DiagnosticsRedaction.REDACTED}"))
    }

    @Test
    fun leavesOrdinaryWordsThatMerelyContainAKeywordAlone() {
        // Over-redaction destroys the diagnostics the report exists to carry.
        val redacted = DiagnosticsRedaction.redact("keyboard=us monkey=banana bitrate=45000 fps=120")

        assertEquals("keyboard=us monkey=banana bitrate=45000 fps=120", redacted)
    }

    @Test
    fun redactsAuthHeadersCookiesAndUrlCredentials() {
        assertFalse(DiagnosticsRedaction.redact("Authorization: Bearer ey.secret").contains("ey.secret"))
        assertFalse(DiagnosticsRedaction.redact("Cookie: sessionid=abc123").contains("abc123"))
        assertFalse(DiagnosticsRedaction.redact("https://user:pw@host/path").contains("pw@"))
    }

    @Test
    fun toleratesNullAndEmptyInput() {
        assertEquals("", DiagnosticsRedaction.redact(null))
        assertEquals("", DiagnosticsRedaction.redact(""))
    }

    @Test
    fun recognisesSensitiveFieldNames() {
        assertTrue(DiagnosticsRedaction.isSensitiveFieldName("api_token"))
        assertTrue(DiagnosticsRedaction.isSensitiveFieldName("Password"))
        assertFalse(DiagnosticsRedaction.isSensitiveFieldName("bitrate"))
        assertFalse(DiagnosticsRedaction.isSensitiveFieldName(null))
    }
}

class CrashMarkerTest {

    private fun record(stack: String = "java.lang.IllegalStateException: boom\n\tat com.papi.nova.Game.run(Game.kt:1)") =
        CrashRecord(
            occurredAt = "2026-08-17T10:00:00Z",
            version = "1.1.3",
            device = "Retroid Pocket 6",
            androidRelease = "13",
            thread = "main",
            stackTrace = stack,
        )

    @Test
    fun roundTripsARecord() {
        val parsed = CrashMarker.parse(CrashMarker.format(record()))

        assertNotNull(parsed)
        assertEquals("2026-08-17T10:00:00Z", parsed!!.occurredAt)
        assertEquals("1.1.3", parsed.version)
        assertEquals("Retroid Pocket 6", parsed.device)
        assertEquals("13", parsed.androidRelease)
        assertEquals("main", parsed.thread)
        assertTrue(parsed.stackTrace.contains("IllegalStateException"))
        assertTrue(parsed.stackTrace.contains("at com.papi.nova.Game.run"))
    }

    @Test
    fun redactsSecretsCarriedInAnExceptionMessage() {
        // An exception message is a very ordinary place for a token to end up,
        // and the marker sits on disk until someone reads it.
        val formatted = CrashMarker.format(
            record("java.io.IOException: POST failed auth_token=hunter2\n\tat Http.send(Http.kt:9)")
        )

        assertFalse(formatted.contains("hunter2"))
        assertTrue(formatted.contains(DiagnosticsRedaction.REDACTED))
    }

    @Test
    fun boundsAStackTraceThatWillNotStop() {
        val runaway = "at com.papi.nova.Loop.spin(Loop.kt:1)\n".repeat(20000)

        val formatted = CrashMarker.format(record(runaway))

        assertTrue(formatted.length < CrashMarker.MAX_STACK_TRACE_CHARS + 1024)
        assertTrue(formatted.contains("[truncated]"))
    }

    @Test
    fun keepsAMultiLineValueFromCorruptingTheHeader() {
        val parsed = CrashMarker.parse(
            CrashMarker.format(record().copy(device = "Weird\nDevice"))
        )

        assertNotNull(parsed)
        assertEquals("Weird Device", parsed!!.device)
        assertTrue(parsed.stackTrace.contains("IllegalStateException"))
    }

    @Test
    fun rejectsAnythingThatIsNotOurMarker() {
        assertNull(CrashMarker.parse(null))
        assertNull(CrashMarker.parse(""))
        assertNull(CrashMarker.parse("some other tool's crash file\nstacktrace:\nboom"))
        assertNull(CrashMarker.parse("${CrashMarker.MAGIC}\nversion: 1.0"))
    }
}

class NovaCrashHandlerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun context() = CrashContext(version = "1.1.3", device = "RP6", androidRelease = "13")

    @Test
    fun writesAMarkerAndStillDelegatesToThePlatform() {
        val marker = File(folder.newFolder(), "last_crash.txt")
        var delegated = false
        val handler = NovaCrashHandler(
            markerFile = marker,
            describe = { context() },
            previous = { _, _ -> delegated = true },
            clock = { "2026-08-17T10:00:00Z" },
        )

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        // Both halves matter: losing the marker costs a report, but swallowing
        // the crash costs the user their crash dialog and the platform its report.
        assertTrue(marker.isFile)
        assertTrue(delegated)
        val parsed = CrashMarker.parse(marker.readText())
        assertNotNull(parsed)
        assertTrue(parsed!!.stackTrace.contains("boom"))
        assertEquals("1.1.3", parsed.version)
    }

    @Test
    fun stillDelegatesWhenTheMarkerCannotBeWritten() {
        // A failure while recording a crash must never replace the crash.
        val unwritable = File(folder.newFile("occupied"), "nested/last_crash.txt")
        var delegated = false
        val handler = NovaCrashHandler(
            markerFile = unwritable,
            describe = { throw IllegalStateException("device lookup exploded") },
            previous = { _, _ -> delegated = true },
            clock = { "2026-08-17T10:00:00Z" },
        )

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertTrue(delegated)
    }

    @Test
    fun survivesHavingNoPreviousHandler() {
        val marker = File(folder.newFolder(), "last_crash.txt")
        val handler = NovaCrashHandler(
            markerFile = marker,
            describe = { context() },
            previous = null,
            clock = { "2026-08-17T10:00:00Z" },
        )

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertTrue(marker.isFile)
    }
}

class DiagnosticsRedactionParityTest {

    // The same cases the host's suite uses, so the two implementations are held
    // to one standard rather than each to its own.

    @Test
    fun redactsSeparatedCamelCaseAndRunTogetherNames() {
        val redacted = DiagnosticsRedaction.redact(
            "auth_token=aaa1 api-key=bbb2 apiKey=ccc3 authToken=ddd4 apikey=eee5 clientsecret=fff6 credentials=ggg7"
        )

        for (leaked in listOf("aaa1", "bbb2", "ccc3", "ddd4", "eee5", "fff6", "ggg7")) {
            assertFalse(leaked, redacted.contains(leaked))
        }
    }

    @Test
    fun leavesDiagnosticsAlone() {
        val survives = "keyboard=us monkey=banana turnkey=yes capture_path=dmabuf packet_loss=2.5 bitrate=45000"

        assertEquals(survives, DiagnosticsRedaction.redact(survives))
    }

    @Test
    fun keepsTheAuthSchemeWhileRedactingItsCredential() {
        val redacted = DiagnosticsRedaction.redact("Authorization: Bearer ey.secret.value")

        assertTrue(redacted.contains("Bearer ${DiagnosticsRedaction.REDACTED}"))
        assertFalse(redacted.contains("ey.secret.value"))
    }

    @Test
    fun treatsBareKeyAsALabelInFieldsAndACredentialInText() {
        assertFalse(DiagnosticsRedaction.isSensitiveFieldName("key"))
        assertFalse(DiagnosticsRedaction.redact("key=barevalue").contains("barevalue"))
    }

    @Test
    fun redactsACredentialThatFollowsAnInnocentLabel() {
        // The ordinary shape of a log line is "Something: detail". Matching the
        // outer pair and stopping consumed the credential as someone else's value.
        val redacted = DiagnosticsRedaction.redact(
            "java.io.IOException: auth_token=hunter2\nError at Foo.bar: apikey=abc123\na: b: c: token=deep"
        )

        for (leaked in listOf("hunter2", "abc123", "deep")) {
            assertFalse(leaked, redacted.contains(leaked))
        }
    }

    @Test
    fun redactsACredentialBehindAQuotedJsonKey() {
        // Nova parses API responses, so it meets real JSON more than the host
        // does. JSON always quotes its keys, and requiring the name to reach its
        // separator directly missed every one of them.
        val redacted = DiagnosticsRedaction.redact(
            "{\"api_key\": \"abc123\"}\n{'apiKey': 'x9'}\n{\"auth_token\":\"t1\"}\n(client_secret): cs9"
        )

        for (leaked in listOf("abc123", "x9", "t1", "cs9")) {
            assertFalse(leaked, redacted.contains(leaked))
        }
    }

    @Test
    fun redactsASensitiveNameWhoseValueIsAStructure() {
        // Stopping early is worse than not matching: it leaves the secret behind
        // while "auth": [redacted] reads as though the subtree was handled.
        assertEquals(
            "{\"auth\": ${DiagnosticsRedaction.REDACTED}}",
            DiagnosticsRedaction.redact("{\"auth\": {\"api_key\": \"abc123\"}}")
        )
        assertFalse(DiagnosticsRedaction.redact("{\"cfg\": {\"auth\": {\"api_key\": \"x9\"}}}").contains("x9"))
        assertEquals(
            "{\"tokens\": ${DiagnosticsRedaction.REDACTED}}",
            DiagnosticsRedaction.redact("{\"tokens\": [\"t1\", \"t2\"]}")
        )
    }

    @Test
    fun stillReachesACredentialNestedUnderAnInnocentName() {
        assertEquals(
            "{\"cfg\": {\"api_key\": ${DiagnosticsRedaction.REDACTED}}}",
            DiagnosticsRedaction.redact("{\"cfg\": {\"api_key\": \"x\"}}")
        )
    }

    @Test
    fun isNotConfusedByABraceInsideAQuotedString() {
        assertEquals(
            "{\"auth\": ${DiagnosticsRedaction.REDACTED}}",
            DiagnosticsRedaction.redact("{\"auth\": {\"note\": \"a } brace\", \"api_key\": \"k1\"}}")
        )
    }

    @Test
    fun redactsToEndOfLineWhenAStructureNeverCloses() {
        // Degenerate input must not become a reason to leave a secret alone.
        assertFalse(DiagnosticsRedaction.redact("api_key={\"a\": \"b\"").contains("\"b\""))
        assertEquals("token=${DiagnosticsRedaction.REDACTED}", DiagnosticsRedaction.redact("token=["))
    }

    @Test
    fun leavesInnocentQuotedKeysAlone() {
        val survives = "{\"level\": \"info\", \"capture_path\": \"dmabuf\", \"keyName\": \"readable\"}"

        assertEquals(survives, DiagnosticsRedaction.redact(survives))
    }

    @Test
    fun isIdempotentAcrossRepeatedPasses() {
        // Nova redacts before it posts and the host redacts again on export, so
        // two passes over the same bytes is the normal path for a real report.
        var text = "Warning: auth_token=hunter2 apiKey=abc123"
        val passes = mutableListOf<String>()
        repeat(5) {
            text = DiagnosticsRedaction.redact(text)
            passes.add(text)
        }

        assertEquals(1, passes.toSet().size)
        assertFalse(passes[0].contains("hunter2"))
        assertFalse(passes[0].contains("]]"))
    }

    @Test
    fun capturesABracketedValueWhole() {
        assertEquals("token=${DiagnosticsRedaction.REDACTED}", DiagnosticsRedaction.redact("token=[abc]"))
        assertEquals("note=[abc]", DiagnosticsRedaction.redact("note=[abc]"))
    }

    @Test
    fun leavesAnInnocentPairIntact() {
        val survives = "Info: capture_path=dmabuf\nlevel: info"

        assertEquals(survives, DiagnosticsRedaction.redact(survives))
    }

    @Test
    fun doesNotTreatKeyAsACredentialWhenItLeadsTheName() {
        assertFalse(DiagnosticsRedaction.isSensitiveFieldName("keyName"))
        assertTrue(DiagnosticsRedaction.isSensitiveFieldName("apiKey"))
        assertTrue(DiagnosticsRedaction.isSensitiveFieldName("publicKey"))
    }
}

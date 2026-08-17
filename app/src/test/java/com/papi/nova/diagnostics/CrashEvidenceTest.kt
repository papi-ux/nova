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

package com.papi.nova.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaSupportReportTest {

    private val crash = CrashRecord(
        occurredAt = "2026-08-17T10:00:00Z",
        version = "1.1.3",
        device = "Retroid Pocket 6",
        androidRelease = "13",
        thread = "main",
        stackTrace = "java.lang.IllegalStateException: boom\n\tat com.papi.nova.Game.run(Game.kt:1)",
    )

    private fun report(
        crash: CrashRecord? = null,
        logTail: String = "",
        userNotes: String = "",
    ) = NovaSupportReport.build(
        generatedAt = "2026-08-17T11:00:00Z",
        version = "1.1.3",
        device = "Retroid Pocket 6",
        androidRelease = "13",
        crash = crash,
        logTail = logTail,
        userNotes = userNotes,
    )

    @Test
    fun statesPlainlyThatNothingWasSent() {
        // The user has to be able to see that this is a handoff and not telemetry.
        assertTrue(report().contains("Nothing was sent automatically"))
    }

    @Test
    fun carriesTheCrashAndItsStackTrace() {
        val text = report(crash = crash)

        assertTrue(text.contains("## Last crash"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("at com.papi.nova.Game.run"))
    }

    @Test
    fun leavesTheCrashSectionOutWhenThereWasNoCrash() {
        assertFalse(report().contains("## Last crash"))
    }

    @Test
    fun redactsSecretsFromEveryInput() {
        val text = report(
            crash = crash.copy(stackTrace = "java.io.IOException: auth_token=hunter2"),
            logTail = "POST /api api_key=abc123",
            userNotes = "my password=letmein stopped working",
        )

        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("letmein"))
        assertTrue(text.contains(DiagnosticsRedaction.REDACTED))
    }

    @Test
    fun saysSoWhenThereIsNoLocalLog() {
        assertTrue(report().contains("No local log was available."))
    }

    @Test
    fun tellsTheUserTheHostHalfIsAlsoNeeded() {
        // A client-only report is exactly the shape that produces a follow-up
        // question instead of a fix.
        assertTrue(report().contains("Polaris support bundle"))
    }
}

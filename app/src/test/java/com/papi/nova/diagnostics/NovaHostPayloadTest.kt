package com.papi.nova.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NovaHostPayloadTest {

    private val crash = CrashRecord(
        occurredAt = "2026-08-17T10:00:00Z",
        version = "1.1.3",
        device = "Retroid Pocket 6",
        androidRelease = "13",
        thread = "main",
        stackTrace = "java.lang.IllegalStateException: boom",
    )

    private fun payload(
        crash: CrashRecord? = null,
        logTail: String = "",
        userNotes: String = "",
    ) = JSONObject(
        NovaSupportReport.hostPayload(
            version = "1.1.3",
            device = "Retroid Pocket 6",
            androidRelease = "13",
            occurredAt = "2026-08-17T11:00:00Z",
            crash = crash,
            logTail = logTail,
            userNotes = userNotes,
        )
    )

    @Test
    fun usesTheFieldNamesTheHostParses() {
        // These names are the contract with client_support_report.cpp. A rename on
        // either side is silent, so this test is the record of what they are.
        val body = payload(crash = crash, logTail = "line", userNotes = "froze")

        for (field in listOf("nova_version", "device", "android_release", "occurred_at", "notes", "crash", "log_tail")) {
            assertTrue(field, body.has(field))
        }
        assertEquals("1.1.3", body.getString("nova_version"))
        assertEquals("Retroid Pocket 6", body.getString("device"))
    }

    @Test
    fun claimsNoIdentity() {
        // The host attributes a report to the client certificate it arrived on, so
        // an identity claim here would be ignored at best and misleading at worst.
        val body = payload(crash = crash)

        assertFalse(body.has("client_id"))
    }

    @Test
    fun redactsEveryFieldOnTheWayIn() {
        val body = payload(
            crash = crash.copy(stackTrace = "java.io.IOException: auth_token=hunter2"),
            logTail = "POST apikey=abc123",
            userNotes = "my password=letmein broke",
        )

        val whole = body.toString()
        assertFalse(whole.contains("hunter2"))
        assertFalse(whole.contains("abc123"))
        assertFalse(whole.contains("letmein"))
    }

    @Test
    fun sendsAnEmptyCrashFieldRatherThanOmittingIt() {
        // The host treats a missing field as absent, but an explicit empty string
        // distinguishes "no crash" from "this client does not send crashes".
        val body = payload(crash = null)

        assertTrue(body.has("crash"))
        assertEquals("", body.getString("crash"))
    }
}

package com.papi.nova.diagnostics

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovaMomentLogTest {
    @get:Rule val folder = TemporaryFolder()

    private fun utf8(text: String) = text.toByteArray(StandardCharsets.UTF_8).size

    private fun log(name: String, text: String): File = File(folder.root, name).apply { writeText(text, StandardCharsets.UTF_8) }

    @Test fun commandTokensAreScrubbedInEveryShapeALogLineGivesThem() {
        val lines = listOf(
            "Nova: status app_session_id=6f1c9e2a-session-token-value generation=4",
            """Nova: body {"app_session_id":"abc123def","session_generation":4}""",
            "Nova: PolarisSessionStatus(appSessionId=abc123def, sessionGeneration=4)",
            "Nova: header X-App-Session-Id: abc123def",
            "Nova: GET /launch?appid=7&rikey=00112233445566778899aabbccddeeff&rikeyid=12&uniqueid=0123456789ABCDEF",
            "Nova: owner_unique_id=0123456789ABCDEF clientcert=deadbeef pairingsecret=feedface salt=cafe",
            "Nova: session_token=tok-123 commandKey=ck-456",
        )
        val scrubbed = NovaMomentLog.scrub(lines.joinToString("\n"))
        for (secret in listOf("6f1c9e2a-session-token-value", "abc123def", "00112233445566778899aabbccddeeff", "0123456789ABCDEF",
                "deadbeef", "feedface", "tok-123", "ck-456", "=cafe"))
            assertFalse(secret, scrubbed.contains(secret))
        // The diagnostic around them survives.
        assertTrue(scrubbed.contains("generation=4"))
        assertTrue(scrubbed.contains("session_generation"))
        assertTrue(scrubbed.contains("appid=7") || scrubbed.contains("appid"))
    }

    @Test fun networkIdentitiesAreScrubbedButOrdinaryDiagnosticsSurvive() {
        val text = listOf(
            "10:42:07.123 Nova: connecting to 192.168.1.20:47984 via https://gaming-pc.tail0000.ts.net:47990/api",
            "Nova: tailscale fd7a:115c:a1e0:ab12:4843:cd96:6258:b240 and fe80::1%wlan0 and ::ffff:10.0.0.5",
            "Nova: wifi mac 3c:22:fb:12:34:56 host=living-room.local address=router.lan",
            "Nova: app 0D9C8B7A-6F5E-4D3C-8B2A-19F8E7D6C5B4 cert fingerprint ${"ab".repeat(32)}",
            "-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIU\n-----END CERTIFICATE-----",
            "Nova: com.papi.nova.Game hostProcessingLatency=4.5 decoder c2.qti.hevc.decoder rendered 59.8 FPS",
        ).joinToString("\n")
        val scrubbed = NovaMomentLog.scrub(text)
        for (identity in listOf("192.168.1.20", "gaming-pc", "tail0000", "fd7a:115c", "fe80::1", "10.0.0.5", "3c:22:fb",
                "living-room", "router.lan", "0D9C8B7A", "ab".repeat(32), "MIIBszCCAVmgAwIBAgIU"))
            assertFalse(identity, scrubbed.contains(identity))
        // Clock times, class names, metrics and decoder names are not identities.
        for (kept in listOf("10:42:07.123", "com.papi.nova.Game", "hostProcessingLatency=4.5", "c2.qti.hevc.decoder", "59.8 FPS"))
            assertTrue(kept, scrubbed.contains(kept))
        // Idempotent: a second pass changes nothing.
        assertEquals(scrubbed, NovaMomentLog.scrub(scrubbed))
    }

    @Test fun knownValuesAreReplacedWhateverTheirShape() {
        val known = mapOf("Living Room Rig" to "<host-name>", "a1b2c3d4" to "<client-id>", "x" to "<never>")
        val scrubbed = NovaMomentLog.scrub("Nova: streaming from living room rig as A1B2C3D4 (x)", known)
        assertEquals("Nova: streaming from <host-name> as <client-id> (x)", scrubbed)
        // Placeholders do not read as names on a later pass: the port survives.
        assertEquals("Nova: to <host-name>:47984", NovaMomentLog.scrub(NovaMomentLog.scrub("Nova: to living room rig:47984", known), known))
    }

    @Test fun theTailIsBoundedInUtf8BytesAndStartsOnAWholeLine() {
        // Multibyte characters straddle the byte bound.
        val line = "Nova: frame é€😀 ok\n"
        val body = line.repeat(10_000)
        val current = log("nova.log.0", body)
        val tail = NovaMomentLog.read(listOf(NovaMomentLog.Part(current, current.length())), maxBytes = 1000)
        assertTrue(tail.bytes <= 1000)
        assertEquals(tail.bytes, utf8(tail.text))
        assertTrue(tail.bytesRead <= 1000)
        assertTrue(tail.truncated)
        assertTrue(tail.text.startsWith("Nova: frame"))
        assertFalse(tail.text.contains('�'))
        assertEquals(tail.text.trimEnd('\n').count { it == '\n' } + 1, tail.lines)
    }

    @Test fun aHugeLogIsNeverReadWhole() {
        val big = log("nova.log.0", "Nova: line\n".repeat(600_000)) // ~6.6 MB
        val tail = NovaMomentLog.read(listOf(NovaMomentLog.Part(big, big.length())))
        assertTrue(big.length() > 6_000_000)
        assertEquals(NovaMomentLog.MAX_BYTES, tail.bytesRead)
        assertTrue(tail.bytes <= NovaMomentLog.MAX_BYTES)
        assertTrue(tail.truncated)
    }

    @Test fun aSingleOverlongLineKeepsItsEndOnACodePoint() {
        val (bounded, cut) = NovaMomentLog.boundUtf8("😀".repeat(1000), 10)
        assertTrue(cut)
        assertEquals(8, utf8(bounded)) // two 4-byte emoji fit in 10 bytes, a third would not
        assertEquals("😀😀", bounded)
        assertEquals("short" to false, NovaMomentLog.boundUtf8("short", 10))
    }

    @Test fun newestFileFirstAndOnlyUpToWhereTheMomentFroze() {
        val older = log("nova.log.1", "old one\nold two\n")
        val current = log("nova.log.0", "new one\nnew two\n")
        File(folder.root, "nova.log.0.lck").writeText("lock")
        val parts = NovaMomentLog.parts(folder.root)
        assertEquals(listOf("nova.log.0", "nova.log.1"), parts.map { it.file.name })
        // Lines written after the press are not part of the moment.
        current.appendText("after the press\n")
        val tail = NovaMomentLog.read(parts)
        assertEquals("old one\nold two\nnew one\nnew two\n", tail.text)
        assertFalse(tail.truncated)
        assertTrue(older.exists())
    }

    @Test fun anEmptyOrMissingLogIsEmpty() {
        assertEquals(NovaMomentLog.Tail.EMPTY, NovaMomentLog.read(emptyList()))
        assertEquals(NovaMomentLog.Tail.EMPTY, NovaMomentLog.read(listOf(NovaMomentLog.Part(File(folder.root, "nova.log.0"), 100))))
    }

    @Test fun generalReportsKeepTheirExistingRedaction() {
        // The moment report's extra names do not leak into DiagnosticsRedaction's own rules.
        assertEquals("app_session_id=abc", DiagnosticsRedaction.redact("app_session_id=abc"))
        assertEquals("app_session_id=[redacted]", DiagnosticsRedaction.redact("app_session_id=abc", NovaMomentLog::isIdentityName))
        assertEquals("token=[redacted]", DiagnosticsRedaction.redact("token=abc"))
    }
}

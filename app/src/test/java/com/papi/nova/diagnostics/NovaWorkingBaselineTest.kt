package com.papi.nova.diagnostics

import com.papi.nova.diagnostics.NovaWorkingBaseline.ContextItem
import com.papi.nova.diagnostics.NovaWorkingBaseline.Match
import com.papi.nova.diagnostics.NovaWorkingBaseline.ReadingItem
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.APP
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.PC
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.entry
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.key
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures.snapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The baseline's closed schema, its bounds in both directions, its scope, and the comparison. */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaWorkingBaselineTest {
    private val scope = NovaWorkingBaseline.scope(key, entry)!!
    private fun baseline(snapshot: NovaMomentReport.Snapshot = snapshot()) = NovaWorkingBaseline.fromSnapshot(scope, snapshot)!!
    private fun stored(): JSONObject = JSONObject(NovaWorkingBaseline.encode(baseline())!!)

    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    @Test fun theStoredEntryHoldsExactlyTheClosedSchemaAndNoCumulativeCountersOrIdentities() {
        val json = stored()
        assertEquals(setOf("kind", "scope", "marked_at_utc", "basis", "client", "role", "requested_settings",
            "client_decoder", "control_channel"), keys(json))
        assertEquals(setOf("version", "build_type", "android_sdk", "device"), keys(json.getJSONObject("client")))
        assertEquals(setOf("width", "height", "fps", "bitrate_kbps", "codec_preference"), keys(json.getJSONObject("requested_settings")))
        assertEquals(setOf("sample_age_ms", "codec", "decoder", "width", "height", "rendered_fps", "received_fps",
            "decode_time_ms", "host_processing_latency_ms", "frame_loss_pct", "frame_loss_scope"), keys(json.getJSONObject("client_decoder")))
        assertEquals(setOf("rtt_ms", "rtt_variance_ms", "scope"), keys(json.getJSONObject("control_channel")))
        assertEquals("user_marked_working", json.getString("basis"))
        val text = json.toString()
        // The snapshot carried 120000 expected and 12 lost frames: session counters, left behind.
        assertFalse(text, text.contains("frames_expected") || text.contains("frames_lost") || text.contains("120000"))
        assertFalse(text, text.contains(PC) || text.contains(APP) || text.contains("log"))
    }

    @Test fun onlyAFreshClientSampleCanBecomeABaseline() {
        assertNull(NovaWorkingBaseline.fromSnapshot(scope, snapshot(sampleAt = 5_000L)))
        assertNull(NovaWorkingBaseline.fromSnapshot(scope, snapshot(sample = false)))
        assertNull(NovaWorkingBaseline.fromSnapshot("not-a-scope", snapshot()))
        assertNotNull(NovaWorkingBaseline.fromSnapshot(scope, snapshot()))
    }

    @Test fun anEmptySampleWindowStaysUnknownLossNeverZero() {
        val empty = baseline(snapshot(framesExpected = 0, packetLossPct = 0.0))
        assertNull(empty.readings.frameLossPct)
        assertTrue(stored().getJSONObject("client_decoder").has("frame_loss_pct"))
        val json = JSONObject(NovaWorkingBaseline.encode(empty)!!)
        assertTrue(json.getJSONObject("client_decoder").isNull("frame_loss_pct"))
    }

    @Test fun whatIsWrittenReadsBackUnchanged() {
        val original = baseline()
        assertEquals(original, NovaWorkingBaseline.parse(NovaWorkingBaseline.encode(original), scope))
    }

    @Test fun anyExtraOrMissingKeyAtAnyLevelIsRejected() {
        val extraTop = stored().put("app_session_id", "s")
        val extraNested = stored().apply { getJSONObject("client_decoder").put("frames_lost", 3) }
        val missing = stored().apply { getJSONObject("control_channel").remove("rtt_ms") }
        val extraControl = stored().apply { getJSONObject("control_channel").put("host", "10.0.0.2") }
        listOf(extraTop, extraNested, missing, extraControl).forEach {
            assertNull(it.toString(), NovaWorkingBaseline.parse(it.toString(), scope))
        }
    }

    @Test fun wrongTypesOutOfRangeAndUnknownWordsAreRejected() {
        fun mutated(block: JSONObject.() -> Unit) = stored().apply(block).toString()
        val cases = listOf(
            mutated { put("kind", "nova-working-baseline-v2") },
            mutated { put("basis", "inferred_from_green_ci") },
            mutated { put("role", "owner") },
            mutated { put("marked_at_utc", "yesterday") },
            mutated { getJSONObject("client").put("android_sdk", 34.5) },
            mutated { getJSONObject("client").put("device", "x".repeat(65)) },
            mutated { getJSONObject("requested_settings").put("width", 0) },
            mutated { getJSONObject("requested_settings").put("codec_preference", "vp9") },
            mutated { getJSONObject("requested_settings").put("bitrate_kbps", "20000") },
            mutated { getJSONObject("client_decoder").put("codec", "vp9") },
            mutated { getJSONObject("client_decoder").put("sample_age_ms", 3_001) },
            mutated { getJSONObject("client_decoder").put("frame_loss_pct", 100.5) },
            mutated { getJSONObject("client_decoder").put("decoder", "host=10.0.0.2") },
            mutated { getJSONObject("client_decoder").put("frame_loss_scope", "packets") },
            mutated { getJSONObject("control_channel").put("rtt_ms", 0) },
            mutated { getJSONObject("control_channel").put("scope", "media") },
        )
        cases.forEach { assertNull(it, NovaWorkingBaseline.parse(it, scope)) }
    }

    @Test fun nonFiniteNumbersAreRejectedOnReadAndCannotBeWritten() {
        val text = stored().toString().replace(Regex(""""rendered_fps":[0-9.]+"""), "\"rendered_fps\":1e400")
        assertTrue(text.contains("1e400"))
        assertNull(NovaWorkingBaseline.parse(text, scope))
        val nonFinite = baseline().let { it.copy(readings = it.readings.copy(decodeTimeMs = Double.NaN)) }
        assertNull(NovaWorkingBaseline.encode(nonFinite))
        val outOfRange = baseline().let { it.copy(readings = it.readings.copy(renderedFps = 5_000.0)) }
        assertNull(NovaWorkingBaseline.encode(outOfRange))
    }

    @Test fun anOversizedEntryIsRejectedBeforeParsingAndCannotBeWritten() {
        val padded = stored().toString().dropLast(1) + " ".repeat(NovaWorkingBaseline.MAX_ENTRY_BYTES) + "}"
        assertNull(NovaWorkingBaseline.parse(padded, scope))
        assertNull(NovaWorkingBaseline.parse("not json", scope))
        assertNull(NovaWorkingBaseline.parse(null, scope))
        val longDevice = baseline().let { it.copy(client = it.client.copy(device = "d".repeat(80))) }
        assertNull(NovaWorkingBaseline.encode(longDevice))
    }

    @Test fun onlyRealCalendarTimesAreAcceptedAndNoClockIsConsulted() {
        listOf("2026-02-30T10:00:00Z", "2026-13-01T10:00:00Z", "2026-10-08T24:00:00Z", "2026-10-08T23:60:00Z",
            "2026-10-08T23:59:60Z", "2025-02-29T00:00:00Z", "0000-01-01T00:00:00Z", "2026-19-45T99:00:00Z").forEach {
            assertFalse(it, NovaWorkingBaseline.isCalendarUtc(it))
            assertNull(it, NovaWorkingBaseline.parse(stored().put("marked_at_utc", it).toString(), scope))
        }
        // Past, leap-day and far-future times are descriptive, not expired.
        listOf("2028-02-29T23:59:59Z", "1999-01-01T00:00:00Z", "2099-12-31T23:59:59Z").forEach {
            assertTrue(it, NovaWorkingBaseline.isCalendarUtc(it))
            assertEquals(it, NovaWorkingBaseline.parse(stored().put("marked_at_utc", it).toString(), scope)?.markedAtUtc)
        }
    }

    @Test fun theEntryBoundIsUtf8BytesNotCharacters() {
        assertTrue(NovaWorkingBaseline.fitsEntry("a".repeat(NovaWorkingBaseline.MAX_ENTRY_BYTES)))
        assertFalse(NovaWorkingBaseline.fitsEntry("a".repeat(NovaWorkingBaseline.MAX_ENTRY_BYTES + 1)))
        assertFalse(NovaWorkingBaseline.fitsEntry("é".repeat(1_100)))
        assertTrue(NovaWorkingBaseline.fitsEntry("é".repeat(1_024)))
    }

    @Test fun anEntryIsOnlyEverReadForItsOwnScope() {
        val other = NovaWorkingBaseline.scope(key, NovaWorkingBaseline.RequestedEntry.of(PC, "another-app-entry")!!)!!
        assertNull(NovaWorkingBaseline.parse(NovaWorkingBaseline.encode(baseline()), other))
    }

    @Test fun theScopeIsAKeyedHashThatSeparatesEntriesAndHoldsNoIdentity() {
        val same = NovaWorkingBaseline.scope(key, NovaWorkingBaseline.RequestedEntry.of(" $PC ", APP)!!)
        assertEquals(scope, same)
        assertTrue(Regex("[0-9a-f]{64}").matches(scope))
        assertFalse(scope.contains(PC) || scope.contains(APP))
        assertNotEquals(scope, NovaWorkingBaseline.scope(key, NovaWorkingBaseline.RequestedEntry.of("other-pc", APP)!!))
        assertNotEquals(scope, NovaWorkingBaseline.scope(key, NovaWorkingBaseline.RequestedEntry.of(PC, "other-app")!!))
        // Length-prefixed, so moving characters between the two identities is a different entry.
        assertNotEquals(
            NovaWorkingBaseline.scope(key, NovaWorkingBaseline.RequestedEntry.of("ab", "c")!!),
            NovaWorkingBaseline.scope(key, NovaWorkingBaseline.RequestedEntry.of("a", "bc")!!))
        assertNotEquals(scope, NovaWorkingBaseline.scope(ByteArray(NovaWorkingBaseline.KEY_BYTES), entry))
        assertNull(NovaWorkingBaseline.scope(null, entry))
        assertNull(NovaWorkingBaseline.scope(ByteArray(16), entry))
    }

    @Test fun withoutBothRequestedIdentitiesThereIsNoEntryAndNoFallback() {
        assertNull(NovaWorkingBaseline.RequestedEntry.of(null, APP))
        assertNull(NovaWorkingBaseline.RequestedEntry.of(PC, null))
        assertNull(NovaWorkingBaseline.RequestedEntry.of(PC, ""))
        assertNull(NovaWorkingBaseline.RequestedEntry.of("   ", APP))
        assertNull(NovaWorkingBaseline.RequestedEntry.of(PC, "a".repeat(NovaWorkingBaseline.MAX_IDENTITY_CHARS + 1)))
        assertNull(NovaWorkingBaseline.RequestedEntry.of(PC, "app\nentry"))
        // Not a UUID, and still an entry: the host's own identity format isn't second-guessed here.
        assertNotNull(NovaWorkingBaseline.RequestedEntry.of("space:living-room", "42"))
        assertFalse(entry.toString().contains(PC) || entry.toString().contains(APP))
    }

    @Test fun matchingKnownContextShowsReadingsSideBySideWithoutAVerdict() {
        val comparison = NovaWorkingBaseline.compare(baseline(), snapshot(markedAtUtc = "2026-10-09T20:00:00Z", renderedFps = 41.0, decodeTimeMs = 9.5))
        assertTrue(comparison.context.all { it.match == Match.SAME })
        assertEquals(true, comparison.knownClientContextMatches)
        val rendered = comparison.readings.single { it.item == ReadingItem.RENDERED_FPS }
        assertEquals(59.8, rendered.baseline!!, 0.0)
        assertEquals(41.0, rendered.now!!, 0.0)
        assertEquals("2026-10-08T13:00:00Z", comparison.baselineMarkedAtUtc)
        assertEquals("2026-10-09T20:00:00Z", comparison.nowMarkedAtUtc)
        assertEquals(200L, comparison.nowSampleAgeMs)
    }

    @Test fun aRealDifferenceInKnownContextIsShownAsDifferent() {
        val comparison = NovaWorkingBaseline.compare(baseline(), snapshot(width = 1280, height = 720, requestedBitrateKbps = 10_000))
        assertEquals(Match.DIFFERENT, comparison.context.single { it.item == ContextItem.REQUESTED_RESOLUTION }.match)
        assertEquals(Match.DIFFERENT, comparison.context.single { it.item == ContextItem.REQUESTED_BITRATE }.match)
        assertEquals(Match.DIFFERENT, comparison.context.single { it.item == ContextItem.DECODED_RESOLUTION }.match)
        assertEquals(Match.SAME, comparison.context.single { it.item == ContextItem.ROLE }.match)
        assertEquals(false, comparison.knownClientContextMatches)
    }

    @Test fun missingContextIsUnknownNotADifference() {
        // A codec preference outside the known set and a decoder name that isn't a component.
        val comparison = NovaWorkingBaseline.compare(baseline(), snapshot(requestedCodec = "SOMETHING_NEW", codec = "hevc"))
        assertEquals(Match.UNKNOWN, comparison.context.single { it.item == ContextItem.REQUESTED_CODEC }.match)
        assertEquals(Match.UNKNOWN, comparison.context.single { it.item == ContextItem.DECODER_COMPONENT }.match)
        assertTrue(comparison.context.none { it.match == Match.DIFFERENT })
        assertNull(comparison.knownClientContextMatches)
    }

    @Test fun aStaleCurrentSampleLeavesEveryCurrentReadingUnknown() {
        val comparison = NovaWorkingBaseline.compare(baseline(), snapshot(sampleAt = 1_000L))
        assertFalse(comparison.nowCurrent)
        assertNull(comparison.nowSampleAgeMs)
        assertTrue(comparison.readings.all { it.now == null && it.baseline != null || it.item == ReadingItem.FRAME_LOSS_PCT })
        assertEquals(Match.UNKNOWN, comparison.context.single { it.item == ContextItem.DECODER_CODEC }.match)
        assertNull(comparison.knownClientContextMatches)
    }
}

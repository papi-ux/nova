package com.papi.nova

import android.app.Application
import android.os.SystemClock
import com.papi.nova.binding.video.PerfOverlaySample
import com.papi.nova.diagnostics.NovaMomentReport
import com.papi.nova.diagnostics.NovaWorkingBaseline
import com.papi.nova.diagnostics.NovaWorkingBaseline.ContextItem
import com.papi.nova.diagnostics.NovaWorkingBaseline.Match
import com.papi.nova.diagnostics.NovaWorkingBaselineFixtures
import com.papi.nova.manager.StreamSyncManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowMoonBridge
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The moment snapshot's requested bitrate is the launch request: the value this stream was
 * actually asked for after the metered choice, the host ceiling and an Auto Safe replacement,
 * which the recap already reports from the same field. A Working Baseline marked at home must be
 * compared against that request, not against the home preference, so a metered or lowered launch
 * reads as a different context rather than a matched one. Nothing here says what bitrate the host
 * applied or measured, and a Live Tuning change made mid-stream stays a known limit of the
 * requested settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [ShadowMoonBridge::class])
class GameMomentLaunchBitrateTest {
    private val homePreferenceKbps = 20_000
    private val meteredPreferenceKbps = 8_000

    @Before fun streamUp() { Game.isStreamActive = true }
    @After fun streamDown() { Game.isStreamActive = false }

    /**
     * One launch of a stream on this device, through the real launch bitrate derivation and the
     * real moment capture: the saved home and metered preferences, the host's manual ceiling, an
     * optional accepted host override, and a fresh decoder sample so the snapshot is current.
     */
    private fun launch(isMetered: Boolean, hostOverride: JSONObject? = null, hostCeilingKbps: Int = 500_000): NovaMomentReport.Snapshot {
        val game = Robolectric.buildActivity(Game::class.java).get()
        game.prefConfig = PreferenceConfiguration().apply {
            width = 1920; height = 1080; fps = 60f
            bitrate = homePreferenceKbps; meteredBitrate = meteredPreferenceKbps
            videoFormat = PreferenceConfiguration.FormatOption.FORCE_HEVC
        }
        ReflectionHelpers.setField(game, "launchManualBitrateMaximumKbps", hostCeilingKbps)
        ReflectionHelpers.setField(game, "lastCompanionPerfSample", PerfOverlaySample(
            fps = 60.0, incomingFps = 60.0, renderedFps = 59.8, width = 1920, height = 1080,
            codec = "c2.qti.hevc.decoder", rttMs = 4, rttVarianceMs = 1, decodeTimeMs = 3.2, packetLossPct = 0.5,
            monotonicTimestampMs = SystemClock.elapsedRealtime(), framesExpected = 120_000, framesLost = 12))
        game.connected = true
        game.configureLaunchBitrate(isMetered, hostOverride)
        val snapshot = game.captureMomentSnapshot()!!
        assertEquals("the saved preference itself is never changed by a launch", homePreferenceKbps, game.prefConfig.bitrate)
        return snapshot
    }

    /** The baseline the user marked on home Wi-Fi, saved from a home launch on this same device. */
    private fun homeBaseline(): NovaWorkingBaseline.Baseline {
        val scope = NovaWorkingBaseline.scope(NovaWorkingBaselineFixtures.key, NovaWorkingBaselineFixtures.entry)!!
        val home = launch(isMetered = false)
        assertEquals(homePreferenceKbps, home.requested.bitrateKbps)
        return NovaWorkingBaseline.fromSnapshot(scope, home)!!
    }

    private fun reportedBitrate(snapshot: NovaMomentReport.Snapshot): Int =
        NovaMomentReport.toJson(snapshot).getJSONObject("requested_settings").getInt("bitrate_kbps")

    private fun requestedBitrateLine(comparison: NovaWorkingBaseline.Comparison) =
        comparison.context.single { it.item == ContextItem.REQUESTED_BITRATE }

    @Test fun aMeteredLaunchReportsTheMeteredRequestNotTheHomePreference() {
        val home = homeBaseline()
        val metered = launch(isMetered = true)
        assertEquals(meteredPreferenceKbps, metered.requested.bitrateKbps)
        assertEquals(meteredPreferenceKbps, reportedBitrate(metered))
        val comparison = NovaWorkingBaseline.compare(home, metered)
        val line = requestedBitrateLine(comparison)
        assertEquals("20000", line.baseline)
        assertEquals("8000", line.now)
        assertEquals(Match.DIFFERENT, line.match)
        assertEquals("a launch at another bitrate is not a matched client context", false, comparison.knownClientContextMatches)
    }

    @Test fun aHostCeilingBelowThePreferenceIsTheRequestTheMomentReports() {
        val home = homeBaseline()
        val capped = launch(isMetered = false, hostCeilingKbps = 15_000)
        assertEquals(15_000, capped.requested.bitrateKbps)
        assertEquals(15_000, reportedBitrate(capped))
        val comparison = NovaWorkingBaseline.compare(home, capped)
        assertEquals(Match.DIFFERENT, requestedBitrateLine(comparison).match)
        assertEquals(false, comparison.knownClientContextMatches)
    }

    @Test fun anAutoSafeLaunchReplacementIsTheRequestTheMomentReports() {
        val home = homeBaseline()
        val override = resolvedProfile(targetBitrateKbps = 12_000)
        assertTrue("an accepted host override, not the saved fallback", StreamSyncManager.hasTrustedResolvedProfile(override))
        val safe = launch(isMetered = false, hostOverride = override)
        assertEquals(12_000, safe.requested.bitrateKbps)
        assertEquals(12_000, reportedBitrate(safe))
        val comparison = NovaWorkingBaseline.compare(home, safe)
        assertEquals(Match.DIFFERENT, requestedBitrateLine(comparison).match)
        assertEquals(false, comparison.knownClientContextMatches)
    }

    @Test fun aHomeLaunchAtThePreferenceStillReadsAsTheSameRequest() {
        val home = homeBaseline()
        val again = launch(isMetered = false)
        assertEquals(homePreferenceKbps, reportedBitrate(again))
        val comparison = NovaWorkingBaseline.compare(home, again)
        assertEquals(Match.SAME, requestedBitrateLine(comparison).match)
        assertTrue(comparison.context.none { it.match == Match.DIFFERENT })
    }

    /** The host's deterministic preset answer, in the shape Game already accepts at launch. */
    private fun resolvedProfile(targetBitrateKbps: Int): JSONObject {
        val fields = JSONObject()
        for ((key, value) in listOf("display_mode" to "1920x1080x60", "display_width" to 1920,
            "display_height" to 1080, "target_fps" to 60, "target_bitrate_kbps" to targetBitrateKbps, "hdr" to false)) {
            fields.put(key, JSONObject().put("value", value).put("source", "client_launch_request")
                .put("reason_code", "requested").put("locked", true).put("normalized", false))
        }
        return JSONObject().put("source", "deterministic_preset_v1")
            .put("resolved_profile", JSONObject().put("policy_version", 1).put("fields", fields))
    }
}

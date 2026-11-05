package com.papi.nova.binding.video.sourcetime

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Replays the shared Nova source-time fixture through the Android mapper: the same cases, oracle
 * and policy the Linux client replays through DeckSourceClockMapper. The fixture and contract are
 * owned by the shared contract (W4) and live outside the app; until they are in this tree the
 * replay is skipped, never passed.
 *
 * Two kinds of case: explicit steps with expected values, and compact integer series (the
 * fixture's seriesGrammar, integer-series-v1) that generate long drift, jitter and step traces
 * and hold them to re-warm counts, the true applied-offset error, the per-update slew budget and
 * target chronology. The series inputs come from the grammar alone, never from any mapper's output.
 *
 * Robolectric only for org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SourceTimeReplayFixtureTest {
    private data class Step(
        val frame: Int,
        val rtp: Long,
        val anchor: Long,
        val decoded: Long?,
        val reset: Boolean,
        val state: String,
        val target: Long?,
        val source: Long?,
        val reserve: Long?,
        val reserveLessThan: Long?,
        val reason: String?,
        val epoch: Long?,
    )

    private data class Series(
        val frames: Long,
        val ticksPerFrame: Long,
        val firstRtp: Long,
        val anchorBaseUs: Long,
        val driftPpm: Long,
        val jitterAmplitudeUs: Long,
        val jitterSeed: Long,
        val anchorResolutionUs: Long,
        val stepAtFrame: Long,
        val stepUs: Long,
        val expectedRewarms: Long,
        val expectedFirstRewarmFrame: Long,
        val maxAppliedOffsetErrorUs: Long,
        val errorCheckAfterFrame: Long,
        val settledAfterFrame: Long,
        val maxSettledAppliedOffsetErrorUs: Long,
    )

    private data class Case(
        val name: String,
        val policy: SourceTimePolicy,
        val capacity: Int,
        val period: Long,
        val steps: List<Step>,
        val series: Series?,
    )

    /** One series case's measured result, printed for the evidence whether it passed or not. */
    private data class SeriesResult(
        val name: String,
        val frames: Long,
        val rewarms: Long,
        val firstRewarm: Long,
        val maxErrorUs: Long,
        val settledErrorUs: Long,
        val invalid: Long,
        val slewViolations: Long,
        val nonMonotonic: Long,
        val ok: Boolean,
    ) {
        override fun toString() = "${if (ok) "PASS" else "FAIL"} $name frames=$frames rewarms=$rewarms " +
            "firstRewarm=$firstRewarm maxErrorUs=$maxErrorUs settledErrorUs=$settledErrorUs invalid=$invalid " +
            "slewViolations=$slewViolations nonMonotonic=$nonMonotonic"
    }

    private fun fixture(path: String): JSONObject {
        val file = File(path)
        assumeTrue("shared source-time fixture not in this tree yet: $path", file.isFile)
        return JSONObject(file.readText())
    }

    private fun policyOf(base: SourceTimePolicy, json: JSONObject): SourceTimePolicy {
        var policy = base
        for (key in json.keys()) {
            val v = json.getLong(key)
            policy = when (key) {
                "offsetWindowUs" -> policy.copy(offsetWindowUs = v)
                "offsetSamples" -> policy.copy(offsetSamples = v)
                "warmupFrames" -> policy.copy(warmupFrames = v)
                "offsetSlewUsPerSecond" -> policy.copy(offsetSlewUsPerSecond = v)
                "offsetMaxStepUs" -> policy.copy(offsetMaxStepUs = v)
                "reserveStartUs" -> policy.copy(reserveStartUs = v)
                "reserveMaximumUs" -> policy.copy(reserveMaximumUs = v)
                "readinessWindowUs" -> policy.copy(readinessWindowUs = v)
                "readinessSamples" -> policy.copy(readinessSamples = v)
                "readinessPercentile" -> policy.copy(readinessPercentile = v)
                "readinessMarginUs" -> policy.copy(readinessMarginUs = v)
                "reserveAttackUs" -> policy.copy(reserveAttackUs = v)
                "cleanHoldUs" -> policy.copy(cleanHoldUs = v)
                "reserveReleaseUsPerSecond" -> policy.copy(reserveReleaseUsPerSecond = v)
                "discontinuityUs" -> policy.copy(discontinuityUs = v)
                "hitchExclusionUs" -> policy.copy(hitchExclusionUs = v)
                "anchorResolutionUs" -> policy.copy(anchorResolutionUs = v)
                "offsetDivergenceToleranceUs" -> policy.copy(offsetDivergenceToleranceUs = v)
                "offsetDivergenceFrames" -> policy.copy(offsetDivergenceFrames = v)
                "offsetBacklogLimitUs" -> policy.copy(offsetBacklogLimitUs = v)
                "offsetBacklogHoldUs" -> policy.copy(offsetBacklogHoldUs = v)
                "offsetStepThresholdUs" -> policy.copy(offsetStepThresholdUs = v)
                "offsetStepFrames" -> policy.copy(offsetStepFrames = v)
                else -> throw AssertionError("fixture policy key this mapper does not know: $key")
            }
        }
        return policy
    }

    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)

    private fun cases(root: JSONObject): List<Case> {
        assertEquals("nova-source-time-replay-v1", root.getString("schema"))
        assertEquals(SourceTimePolicy.VERSION, root.getInt("policyVersion"))
        assertEquals(SourceTimePolicy.INSTABILITY_REVISION, root.getInt("offsetInstabilityRevision"))
        // A grammar this loader does not implement must fail here, not be half-read.
        assertEquals(SERIES_GRAMMAR, root.getJSONObject("seriesGrammar").getString("version"))
        val base = policyOf(SourceTimePolicy(), root.getJSONObject("policy"))
        val list = root.getJSONArray("cases")
        return (0 until list.length()).map { c ->
            val case = list.getJSONObject(c)
            val steps = case.optJSONArray("steps") ?: org.json.JSONArray()
            assertTrue("${case.getString("name")} has neither steps nor a series", steps.length() > 0 || case.has("series"))
            Case(
                case.getString("name"),
                policyOf(base, case.getJSONObject("policyOverrides")),
                case.getInt("capacity"),
                case.getLong("admittedPeriodUs"),
                (0 until steps.length()).map { s ->
                    val input = steps.getJSONObject(s).getJSONObject("input")
                    val expected = steps.getJSONObject(s).getJSONObject("expected")
                    Step(
                        input.getInt("frame"),
                        input.getLong("rtpTimestamp"),
                        input.getLong("anchorUs"),
                        input.longOrNull("decodedUs"),
                        input.getBoolean("resetBefore"),
                        expected.getString("state"),
                        expected.longOrNull("targetUs"),
                        expected.longOrNull("sourceUs"),
                        expected.longOrNull("reserveUs"),
                        expected.longOrNull("reserveLessThan"),
                        if (expected.isNull("reason")) null else expected.getString("reason"),
                        expected.longOrNull("epoch"),
                    )
                },
                case.optJSONObject("series")?.let(::seriesOf),
            )
        }
    }

    private fun seriesOf(json: JSONObject): Series {
        val known = setOf(
            "frames", "ticksPerFrame", "firstRtp", "anchorBaseUs", "driftPpm", "jitterAmplitudeUs", "jitterSeed",
            "anchorResolutionUs", "stepAtFrame", "stepUs", "expectedRewarms", "expectedFirstRewarmFrame",
            "maxAppliedOffsetErrorUs", "errorCheckAfterFrame", "settledAfterFrame", "maxSettledAppliedOffsetErrorUs",
        )
        for (key in json.keys()) assertTrue("series field this loader does not know: $key", key in known)
        // The grammar's cases specify every field; none is defaulted here.
        return Series(
            json.getLong("frames"), json.getLong("ticksPerFrame"), json.getLong("firstRtp"), json.getLong("anchorBaseUs"),
            json.getLong("driftPpm"), json.getLong("jitterAmplitudeUs"), json.getLong("jitterSeed"),
            json.getLong("anchorResolutionUs"), json.getLong("stepAtFrame"), json.getLong("stepUs"),
            json.getLong("expectedRewarms"), json.getLong("expectedFirstRewarmFrame"),
            json.getLong("maxAppliedOffsetErrorUs"), json.getLong("errorCheckAfterFrame"),
            json.getLong("settledAfterFrame"), json.getLong("maxSettledAppliedOffsetErrorUs"),
        )
    }

    /**
     * One series case through a fresh mapper, by the fixture's integer-series-v1 grammar, with the
     * same oracle as the Linux replay. The arrival-only control aims every valid frame at its anchor.
     */
    private fun replaySeries(case: Case, s: Series, arrivalOnly: Boolean): SeriesResult {
        val mapper = SourceTimeMapper(case.policy, case.capacity, case.period)
        var random = s.jitterSeed and U32
        var rewarms = 0L
        var firstRewarm = 0L
        var maxError = 0L
        var settledError = 0L
        var lastAnchor = -1L
        var lastBase = 0L
        var lastSource = 0L
        var lastTarget = 0L
        var creditRemainder = 0L
        var epochObservations = 0L
        var epoch = mapper.epoch
        var invalid = 0L
        var slewViolations = 0L
        var nonMonotonic = 0L
        for (frame in 1..s.frames) {
            val source = (frame - 1) * s.ticksPerFrame * 1000 / 90
            // Long division truncates toward zero, as the grammar specifies.
            val truth = s.anchorBaseUs + source * s.driftPpm / 1_000_000 + (if (s.stepAtFrame > 0 && frame >= s.stepAtFrame) s.stepUs else 0)
            random = (random * 1_664_525L + 1_013_904_223L) and U32
            val jitter = random % (s.jitterAmplitudeUs + 1)
            val anchor = (source + truth + jitter) / s.anchorResolutionUs * s.anchorResolutionUs
            var p = mapper.observe(frame.toInt(), (s.firstRtp + (frame - 1) * s.ticksPerFrame) and U32, anchor)
            if (p.epoch != epoch) {
                rewarms++
                if (firstRewarm == 0L) firstRewarm = frame
            }
            epochObservations = if (p.epoch != epoch) 1 else epochObservations + 1
            if (!p.valid || (p.epoch != epoch && p.reason != SourceTimeMapper.REASON_CLOCK_MAPPING_UNSTABLE)) invalid++
            if (arrivalOnly && p.valid) p = p.copy(baseUs = anchor, targetUs = anchor + mapper.reserveUs)
            val error = kotlin.math.abs(p.baseUs - source - truth)
            if (frame >= s.errorCheckAfterFrame) maxError = maxOf(maxError, error)
            if (s.settledAfterFrame > 0 && frame >= s.settledAfterFrame) settledError = maxOf(settledError, error)
            if (lastAnchor >= 0 && p.epoch == epoch) {
                val numerator = (anchor - lastAnchor) * case.policy.offsetSlewUsPerSecond + creditRemainder
                creditRemainder = if (epochObservations > case.policy.warmupFrames) numerator % 1_000_000 else 0
                // Warm-up may replace the startup offset; a steady update spends only its own credit.
                if (epochObservations > case.policy.warmupFrames &&
                    kotlin.math.abs((p.baseUs - p.sourceUs) - (lastBase - lastSource)) >
                    minOf(case.policy.offsetMaxStepUs, numerator / 1_000_000)
                ) {
                    slewViolations++
                }
                if (p.targetUs <= lastTarget) nonMonotonic++
            } else {
                creditRemainder = 0
            }
            lastAnchor = anchor
            lastBase = p.baseUs
            lastSource = p.sourceUs
            lastTarget = p.targetUs
            epoch = p.epoch
        }
        val ok = rewarms == s.expectedRewarms && firstRewarm == s.expectedFirstRewarmFrame &&
            maxError <= s.maxAppliedOffsetErrorUs && settledError <= s.maxSettledAppliedOffsetErrorUs &&
            invalid == 0L && slewViolations == 0L && nonMonotonic == 0L
        return SeriesResult(case.name, s.frames, rewarms, firstRewarm, maxError, settledError, invalid, slewViolations,
            nonMonotonic, ok)
    }

    /** Every oracle violation, as text; empty means the replay passed. */
    private fun replay(cases: List<Case>, arrivalOnly: Boolean, series: MutableList<SeriesResult>? = null): List<String> {
        val failures = ArrayList<String>()
        for (case in cases) {
            case.series?.let { s ->
                val result = replaySeries(case, s, arrivalOnly)
                series?.add(result)
                if (!result.ok) failures += result.toString()
            }
            val mapper = SourceTimeMapper(case.policy, case.capacity, case.period)
            for (step in case.steps) {
                if (step.reset) mapper.reset()
                var point = mapper.observe(step.frame, step.rtp, step.anchor)
                if (arrivalOnly && point.valid) {
                    // The negative control: the same frames, aimed by their arrival.
                    point = point.copy(targetUs = step.anchor + mapper.reserveUs)
                }
                if (step.decoded != null) mapper.ready(point, step.decoded)
                val ok = point.state == step.state &&
                    (step.target == null || point.targetUs == step.target) &&
                    (step.source == null || point.sourceUs == step.source) &&
                    (step.reserve == null || mapper.reserveUs == step.reserve) &&
                    (step.reserveLessThan == null || mapper.reserveUs < step.reserveLessThan) &&
                    (step.reason == null || point.reason == step.reason) &&
                    (step.epoch == null || point.epoch == step.epoch) &&
                    mapper.reserveUs >= 0 && mapper.reserveUs <= mapper.reserveCapUs() &&
                    mapper.historySize <= 1024
                if (!ok) {
                    failures += "${case.name} frame=${step.frame} target=${point.targetUs} expected=${step.target} " +
                        "source=${point.sourceUs} state=${point.state} reason=${point.reason} reserve=${mapper.reserveUs} " +
                        "epoch=${point.epoch}"
                }
            }
        }
        return failures
    }

    @Test
    fun theAndroidMapperPassesEverySharedReplayCase() {
        val cases = cases(fixture(REPLAY))
        assertTrue("the shared fixture has no cases", cases.isNotEmpty())
        val series = ArrayList<SeriesResult>()
        val failures = replay(cases, arrivalOnly = false, series = series)
        println("shared replay: cases=${cases.size} steps=${cases.sumOf { it.steps.size }} " +
            "series=${series.size} seriesFrames=${series.sumOf { it.frames }}")
        series.forEach { println("  $it") }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun anArrivalTimeOnlyMapperFailsTheSameOracle() {
        val failures = replay(cases(fixture(REPLAY)), arrivalOnly = true)
        println("arrival-only control: ${failures.size} oracle failures")
        failures.forEach { println("  $it") }
        assertTrue("aiming frames by arrival must fail the shared source-time oracle", failures.isNotEmpty())
    }

    @Test
    fun theAndroidDefaultsAreTheSharedPolicy() {
        for (path in listOf(REPLAY, CONTRACT)) {
            val root = fixture(path)
            assertEquals(path, SourceTimePolicy.VERSION, root.getInt("policyVersion"))
            assertEquals(path, SourceTimePolicy.INSTABILITY_REVISION, root.getInt("offsetInstabilityRevision"))
            assertEquals(path, SourceTimePolicy.DEFAULT, policyOf(SourceTimePolicy.DEFAULT, root.getJSONObject("policy")))
        }
        val contract = fixture(CONTRACT)
        assertEquals(64, contract.getJSONObject("bounds").getInt("timingMetadataRecords"))
        assertEquals(SourceTimeSideMap.DEFAULT_CAPACITY, contract.getJSONObject("bounds").getInt("timingMetadataRecords"))
        val reasons = contract.getJSONArray("fallbackReasons")
        val known = SourceTimeReason.values().map { it.wire }.toSet()
        for (i in 0 until reasons.length()) {
            assertTrue("shared reason ${reasons.getString(i)} has no Android token", reasons.getString(i) in known)
        }
    }

    companion object {
        // Unit tests run from the app module, so the shared tree is one level up.
        const val REPLAY = "../shared/presentation/tests/source-time-replay-v1.json"
        const val CONTRACT = "../shared/presentation/source-time-contract-v1.json"
        const val SERIES_GRAMMAR = "integer-series-v1"
        private const val U32 = 0xFFFF_FFFFL
    }
}

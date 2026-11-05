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

    private data class Case(
        val name: String,
        val policy: SourceTimePolicy,
        val capacity: Int,
        val period: Long,
        val steps: List<Step>,
    )

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
                else -> throw AssertionError("fixture policy key this mapper does not know: $key")
            }
        }
        return policy
    }

    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)

    private fun cases(root: JSONObject): List<Case> {
        assertEquals("nova-source-time-replay-v1", root.getString("schema"))
        assertEquals(SourceTimePolicy.VERSION, root.getInt("policyVersion"))
        val base = policyOf(SourceTimePolicy(), root.getJSONObject("policy"))
        val list = root.getJSONArray("cases")
        return (0 until list.length()).map { c ->
            val case = list.getJSONObject(c)
            val steps = case.getJSONArray("steps")
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
            )
        }
    }

    /** Every oracle violation, as text; empty means the replay passed. */
    private fun replay(cases: List<Case>, arrivalOnly: Boolean): List<String> {
        val failures = ArrayList<String>()
        for (case in cases) {
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
        println("shared replay: cases=${cases.size} steps=${cases.sumOf { it.steps.size }}")
        assertEquals(emptyList<String>(), replay(cases, arrivalOnly = false))
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
    }
}

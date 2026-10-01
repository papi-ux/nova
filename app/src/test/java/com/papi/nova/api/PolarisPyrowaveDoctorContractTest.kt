package com.papi.nova.api

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@org.robolectric.annotation.Config(sdk = [33])
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class PolarisPyrowaveDoctorContractTest {
    private fun fixture() = JSONObject(javaClass.getResourceAsStream("/polaris-doctor-pyrowave-v2.json")!!.bufferedReader().use { it.readText() })
    private fun payload(json: JSONObject) = json.getJSONObject("safe_recovery_action").getJSONObject("payload_preview")
    private fun parse(json: JSONObject) = PolarisApiClient.parseSessionStatusResponse(JSONObject().put("doctor", json)).doctor
    private fun ordinary(source: Any? = "launch_bitrate"): JSONObject = fixture().apply {
        put("primary_issue", "quality_reduced_live")
        getJSONArray("evidence").put(JSONObject().put("id", "effective_quality_ceiling").put("source", "launch_policy")
            .put("status", "watch").put("value", payload(this).getInt("target_bitrate_kbps")))
        if (source == null) payload(this).remove("goal_source") else payload(this).put("goal_source", source)
    }

    @Test fun hostPyrowaveStarvationOfferIsExecutable() {
        val doctor = parse(fixture())
        assertTrue("The current host's reversible PyroWave offer must reach the client", doctor.canExecuteAction)
        assertTrue(doctor.matchesExecutableActionIntent(doctor))
    }

    @Test fun ordinaryAndLegacyRestoreOffersRemainExecutable() {
        for (source in listOf("launch_bitrate", "launch_ceiling", null)) assertTrue(parse(ordinary(source)).canExecuteAction)
    }

    @Test fun malformedOrUnexpectedOrdinaryGoalCannotBecomeALegacyOffer() {
        for (source in listOf<Any>(17, JSONObject.NULL, "", "future_goal", "pyrowave_advice")) {
            assertFalse("Reject explicit invalid goal $source", parse(ordinary(source)).canExecuteAction)
        }
    }

    @Test fun aChangedGoalRequiresAnotherPress() {
        val displayed = parse(ordinary("launch_bitrate"))
        val refreshed = parse(ordinary("launch_ceiling"))
        assertTrue(displayed.canExecuteAction)
        assertTrue(refreshed.canExecuteAction)
        assertFalse("The same action ID cannot silently switch quality-goal provenance", refreshed.matchesExecutableActionIntent(displayed))
        assertFalse(refreshed.matchesConfirmedAction(displayed))
    }

    @Test fun starvationWithoutItsTypedAdviceGoalOrCleanEvidenceRemainsReadOnly() {
        for (source in listOf<Any?>(null, 17, JSONObject.NULL, "", "launch_bitrate", "future_goal")) {
            val json = fixture()
            if (source == null) payload(json).remove("goal_source") else payload(json).put("goal_source", source)
            assertFalse(parse(json).canExecuteAction)
        }
        val badLoss = fixture().apply { getJSONArray("evidence").getJSONObject(0).put("status", "fail").put("value", 5) }
        assertFalse(parse(badLoss).canExecuteAction)
        val overAutoCap = fixture().apply { payload(this).put("target_bitrate_kbps", 300001) }
        assertFalse(parse(overAutoCap).canExecuteAction)
    }

    @Test fun theActionBodyPreservesTheExactHostGoalAndStreamEnvelope() {
        val json = fixture()
        val doctor = parse(json)
        val body = PolarisApiClient.buildDoctorActionBody(
            actionId = doctor.actionId, appSessionId = doctor.actionAppSessionId,
            sessionGeneration = doctor.actionSessionGeneration, sourceResultId = doctor.actionSourceResultId,
            targetBitrateKbps = doctor.targetBitrateKbps, controllerRevision = doctor.actionControllerRevision,
            evidenceRevision = doctor.actionEvidenceRevision, requestId = "fixture-request", goalSource = doctor.actionGoalSource)
        val expected = payload(json)
        for (key in expected.keys()) {
            val value = expected.get(key)
            if (value is Number) {
                assertTrue("Typed host number $key", body.get(key) is Number)
                assertEquals("Exact host field $key", value.toLong(), (body.get(key) as Number).toLong())
            } else assertEquals("Exact host field $key", value, body.get(key))
        }
        assertEquals("fixture-request", body.getString("request_id"))
        for (source in listOf("launch_bitrate", "launch_ceiling")) {
            assertEquals(source, PolarisApiClient.buildDoctorActionBody("restore_quality", "fixture-session", goalSource = source).getString("goal_source"))
        }
        for (action in listOf("verify_run", "undo", "lower_bitrate")) {
            assertFalse(PolarisApiClient.buildDoctorActionBody(action, "fixture-session", runId = "fixture-run").has("goal_source"))
        }
    }

    @Test fun changedScopeOrDirtyNetworkCannotAuthorizeTheAdviceRaise() {
        for (key in listOf("source_result_id", "app_session_id", "controller_revision", "evidence_revision", "session_generation")) {
            val json = fixture()
            payload(json).remove(key)
            assertFalse("Missing $key fails closed", parse(json).canExecuteAction)
        }
        for (latency in listOf(45, 80)) {
            val json = fixture()
            json.getJSONArray("evidence").getJSONObject(1).put("value", latency)
            assertFalse(parse(json).canExecuteAction)
        }
        val displayed = parse(fixture())
        val refreshed = parse(fixture().apply { payload(this).put("session_generation", 42) })
        assertTrue(refreshed.canExecuteAction)
        assertFalse(refreshed.matchesExecutableActionIntent(displayed))
    }

    @Test fun liveTuningKeepsRttOwnershipWhileAHostLossStepRemainsAvailable() {
        fun lower(loss: Boolean) = fixture().apply {
            put("primary_issue", "network_jitter")
            val action = getJSONObject("safe_recovery_action")
            action.put("id", "lower_bitrate")
            payload(this).put("action_id", "lower_bitrate").put("target_bitrate_kbps", 16000).remove("goal_source")
            action.getJSONObject("verification").put("mode", "live_telemetry")
            getJSONArray("evidence").getJSONObject(if (loss) 0 else 1).put("status", "fail").put("value", if (loss) 5 else 60)
        }
        val mediaLoss = parse(lower(true))
        val rttOnly = parse(lower(false))
        assertTrue(mediaLoss.canExecuteAction)
        assertTrue(rttOnly.canExecuteAction)
        assertTrue(mediaLoss.canExecuteWithLiveTuning(true))
        assertFalse(rttOnly.canExecuteWithLiveTuning(true))
        assertTrue(rttOnly.canExecuteWithLiveTuning(false))
        assertFalse(parse(fixture()).canExecuteWithLiveTuning(true))
        assertTrue(parse(fixture()).canExecuteWithLiveTuning(false))
    }

    @Test fun unavailableLossRequiresAnExplicitNullInsteadOfMalformedEvidence() {
        for (value in listOf<Any?>(null, "bad", false, JSONObject(), -1, 101)) {
            val json = fixture()
            val loss = json.getJSONArray("evidence").getJSONObject(0)
            loss.put("source", "unavailable").put("status", "unknown")
            if (value == null) loss.remove("value") else loss.put("value", value)
            assertFalse("Unknown loss cannot hide invalid value $value", parse(json).canExecuteAction)
        }
        val unknown = fixture().apply {
            getJSONArray("evidence").getJSONObject(0).put("source", "unavailable")
                .put("status", "unknown").put("value", JSONObject.NULL)
        }
        assertTrue("The host's explicit unknown loss remains supported", parse(unknown).canExecuteAction)
    }

    @Test fun measuredLossAndLatencyMustBeInTheirPhysicalRange() {
        for (loss in listOf(-1, 101, "0")) {
            val json = fixture().apply { getJSONArray("evidence").getJSONObject(0).put("value", loss) }
            assertFalse(parse(json).canExecuteAction)
        }
        val negativeRtt = fixture().apply { getJSONArray("evidence").getJSONObject(1).put("value", -1) }
        assertFalse(parse(negativeRtt).canExecuteAction)
    }

    @Test fun aLossStepNeedsValidMeasuredLossWithTheHostFailedVerdict() {
        for (loss in listOf<Any>(JSONObject.NULL, "bad", -1, 101)) {
            val json = fixture().apply {
                put("primary_issue", "network_jitter")
                val action = getJSONObject("safe_recovery_action")
                action.put("id", "lower_bitrate")
                payload(this).put("action_id", "lower_bitrate").put("target_bitrate_kbps", 16000).remove("goal_source")
                action.getJSONObject("verification").put("mode", "live_telemetry")
                getJSONArray("evidence").getJSONObject(0).put("status", "fail").put("value", loss)
            }
            val doctor = parse(json)
            assertFalse("Malformed media loss cannot authorize a write: $loss", doctor.canExecuteAction)
            assertFalse(doctor.canExecuteWithLiveTuning(true))
        }
    }

    @Test fun pyrowaveRaiseRequiresAPositiveObservedBitrate() {
        for (value in listOf<Any?>(null, JSONObject.NULL, "20000", 0, -1)) {
            val json = fixture()
            val bitrate = json.getJSONArray("evidence").getJSONObject(2)
            if (value == null) bitrate.remove("value") else bitrate.put("value", value)
            assertFalse("No measured bitrate for $value", parse(json).canExecuteAction)
        }
        for ((source, status) in listOf("configuration" to "watch", "stream_stats" to "pass")) {
            val json = fixture().apply { getJSONArray("evidence").getJSONObject(2).put("source", source).put("status", status) }
            assertFalse(parse(json).canExecuteAction)
        }
        val absent = fixture().apply { getJSONArray("evidence").remove(2) }
        assertFalse(parse(absent).canExecuteAction)
    }
}

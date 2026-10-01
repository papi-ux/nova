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
}

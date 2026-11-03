package com.papi.nova.api

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoctorAiWireTest {
    private val request = DoctorAiRequest("11111111-1111-4111-8111-111111111111",
        DoctorAiScope("opaque-session", 7, "doctor-network", "a".repeat(64), 9))
    private fun descriptor() = JSONObject("""{"schema":1,"enabled":true,"permitted":true,"available":true,"blocked_reason":"","source_result_id":"doctor-network","finding_fingerprint":"${"a".repeat(64)}","controller_revision":9}""")
    private fun response() = JSONObject("""{
      "schema":1,"status":true,"fallback":false,"changed":false,"code":"explained",
      "request_id":"${request.requestId}","app_session_id":"opaque-session","session_generation":7,
      "source_result_id":"doctor-network","finding_fingerprint":"${"a".repeat(64)}","controller_revision":9,
      "snapshot_id":"${"b".repeat(32)}","snapshot_age_ms":1000,"expires_in_ms":59000,"retry_after_ms":null,
      "authority":"explanation_only","may_define_settings":false,
      "source":{"kind":"ai-explanation","mode":"host-configured","informational":true},
      "explanation":{"likely_cause":"Captured network pressure","evidence":["Loss is measured"],
       "try_first":["Review Doctor"],"advanced_detail":"Bitrate 20 Mbps is prose, not a setting.","confidence":"high","destructive_action_allowed":false}
    }""")
    private fun accepted(j: JSONObject) = DoctorAiWire.response(200, j.toString(), request).text != null

    @Test fun requestContainsOnlyTheSevenComparisonFields() {
        val body = JSONObject(DoctorAiWire.body(request))
        assertEquals(setOf("schema", "request_id", "app_session_id", "session_generation", "source_result_id", "finding_fingerprint", "controller_revision"), body.keys().asSequence().toSet())
        assertFalse(body.has("evidence")); assertFalse(body.has("provider"))
    }
    @Test fun strictCapabilityAndDescriptorDoNotBorrowLegacyAiControls() {
        for (value in listOf<Any>("true", 1, JSONObject.NULL, false)) {
            val c = PolarisApiClient.parseCapabilitiesResponse(JSONObject().put("features", JSONObject().put("doctor_ai_explanation_v1", value).put("ai_optimizer_control", true)))
            assertFalse(c.features.doctorAiExplanationV1)
        }
        assertTrue(PolarisApiClient.parseCapabilitiesResponse(JSONObject().put("features", JSONObject().put("doctor_ai_explanation_v1", true))).features.doctorAiExplanationV1)
        assertNotNull(DoctorAiWire.descriptor(descriptor()))
        for ((key, value) in listOf("enabled" to "true", "permitted" to 1, "available" to "true", "controller_revision" to 9.0,
            "finding_fingerprint" to "bad", "extra" to true, "source_result_id" to 3)) {
            assertNull(key, DoctorAiWire.descriptor(descriptor().put(key, value)))
        }
        for (key in descriptor().keys().asSequence().toList()) assertNull(key, DoctorAiWire.descriptor(descriptor().also { it.remove(key) }))
    }
    @Test fun optionalScopeRequiresTypedFreshOwnerMetadataWithoutTuningAuthority() {
        val json = JSONObject("""{"state":"streaming","streaming_active":true,"shutdown_requested":false,"owned_by_client":true,"client_role":"owner","viewer_count":0,"app_session_id":"opaque-session","session_generation":7,"controls":{"host_tuning_allowed":false},"doctor":{"result_id":"doctor-network"}}""")
        json.getJSONObject("doctor").put("ai_explanation_request", descriptor())
        val status = PolarisApiClient.parseSessionStatusResponse(json)
        assertEquals(request.scope, status.doctorAiScope())
        assertFalse(status.canAdjustHostTuning)
        for ((key, value) in listOf("streaming_active" to "true", "shutdown_requested" to "false", "viewer_count" to "0", "session_generation" to 7.0,
            "client_role" to "viewer", "owned_by_client" to false)) {
            assertNull(key, PolarisApiClient.parseSessionStatusResponse(JSONObject(json.toString()).put(key, value)).doctorAiScope())
        }
    }
    @Test fun acceptsOnlyTheBoundSixFieldInformationalResponse() {
        assertTrue(accepted(response()))
        val text = DoctorAiWire.response(200, response().toString(), request).text!!
        assertTrue(text.advancedDetail.contains("20 Mbps"))
        // Provider confidence is deliberately absent from DoctorAiText.
        assertEquals("Captured network pressure", text.likelyCause)
    }
    @Test fun everyIdentityFlagAndShapeMutationRefusesAValidBaseline() {
        val mutations = listOf("request_id" to "22222222-2222-4222-8222-222222222222", "app_session_id" to "other",
            "session_generation" to 8, "source_result_id" to "other", "finding_fingerprint" to "c".repeat(64),
            "controller_revision" to 10, "controller_revision" to "9", "schema" to "1", "status" to "true", "fallback" to true,
            "changed" to true, "authority" to "action", "may_define_settings" to true, "snapshot_id" to "bad", "extra" to true,
            "snapshot_age_ms" to 60000, "expires_in_ms" to 60000, "expires_in_ms" to 0, "retry_after_ms" to 1)
        for ((key, value) in mutations) assertFalse("$key=$value", accepted(response().put(key, value)))
        for (key in response().keys().asSequence().toList()) assertFalse(key, accepted(response().also { it.remove(key) }))
        for (status in listOf(302, 400, 401, 403, 404, 409, 413, 415, 429, 502, 503, 504))
            assertNull(DoctorAiWire.response(status, response().toString(), request).text)
    }
    @Test fun decimalNumberTokensAreNotCoercedToIntegers() {
        for ((key, value) in listOf("schema" to 1, "session_generation" to 7, "controller_revision" to 9,
            "snapshot_age_ms" to 1000, "expires_in_ms" to 59000)) {
            val valid = response().toString()
            val fractionalToken = valid.replace("\"$key\":$value", "\"$key\":$value.0")
            assertNotEquals(valid, fractionalToken)
            assertNull(key, DoctorAiWire.response(200, fractionalToken, request).text)
        }
    }
    @Test fun explanationAndSourceCannotClaimAuthorityOrExceedBounds() {
        for ((key, value) in listOf("destructive_action_allowed" to true, "confidence" to "deterministic", "evidence" to "text", "likely_cause" to "x".repeat(513), "target_bitrate_kbps" to 20000)) {
            val j = response(); j.getJSONObject("explanation").put(key, value); assertFalse(key, accepted(j))
        }
        val j = response(); j.getJSONObject("source").put("informational", "true"); assertFalse(accepted(j))
        assertNull(DoctorAiWire.response(200, " ".repeat(8193), request).text)
        val tooMany = response(); tooMany.getJSONObject("explanation").put("evidence", org.json.JSONArray(List(7) { "line" })); assertFalse(accepted(tooMany))
    }
    @Test fun refusesDuplicateKeysTrailingDataLenientJsonAndInvalidText() {
        val valid = response().toString()
        for (bad in listOf(valid.replace("\"status\":true", "\"status\":false,\"status\":true"),
            valid.replace("\"confidence\":\"high\"", "\"confidence\":\"low\",\"confidence\":\"high\""),
            valid + "{}", "/*comment*/$valid", valid.replace("\"schema\":1", "\"schema\":01"))) {
            assertNotEquals(valid, bad); assertNull(DoctorAiWire.response(200, bad, request).text)
        }
        val j = response(); j.getJSONObject("explanation").put("likely_cause", "bad\u0000text"); assertFalse(accepted(j))
    }
}

package com.papi.nova.api

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolarisStreamAdviceTest {
    private fun advice() = JSONObject("""{"version":1,"available":true,"width":1920,"height":1080,"fps":120,
        "raise_goal_kbps":201125,"cap_kbps":300000,"raise_goal_limited_by":"advice"}""")
    @Test fun parsesPrelaunchAndSessionRequestUnits() {
        assertEquals(201125,PolarisPyrowaveAdvice.parse(advice())!!.raiseGoalKbps)
        val session = advice().apply { remove("available") }
        assertEquals(201125,PolarisApiClient.parseSessionStatusResponse(JSONObject().put("pyrowave_bitrate",session)).pyrowaveBitrate!!.raiseGoalKbps)
        assertNull(PolarisPyrowaveAdvice.parse(advice().put("available",false)))
        assertNull(PolarisPyrowaveAdvice.parse(advice().put("version",2)))
        assertNull(PolarisPyrowaveAdvice.parse(advice().put("raise_goal_kbps","201125")))
        assertNull(PolarisPyrowaveAdvice.parse(advice().put("raise_goal_kbps",201125.5)))
        assertNull(PolarisPyrowaveAdvice.parse(advice().put("raise_goal_kbps",300001)))
    }
    @Test fun captureRefusalsAndAdviceFeatureRemainOptionalAndStrict() {
        val json = JSONObject().put("features",JSONObject().put("pyrowave_advice_v1",true))
            .put("capture",JSONObject().put("pyrowave_unavailable",JSONObject().put("reason","fp16_capture")
                .put("message","PyroWave needs SDR capture")))
        val caps = PolarisApiClient.parseCapabilitiesResponse(json)
        assertTrue(caps.features.pyrowaveAdviceV1)
        assertEquals("fp16_capture",caps.capture.pyrowaveUnavailable!!.reason)
        assertEquals("PyroWave needs SDR capture",caps.capture.pyrowaveUnavailable!!.message)
        assertNull(PolarisApiClient.parseCapabilitiesResponse(JSONObject()).capture.pyrowaveUnavailable)
        assertFalse(PolarisApiClient.parseCapabilitiesResponse(JSONObject().put("features",
            JSONObject().put("pyrowave_advice_v1","true"))).features.pyrowaveAdviceV1)
    }
}

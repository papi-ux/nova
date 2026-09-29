package com.papi.nova.manager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaStreamSourceLineTest {
    @Test fun savedCopyAndPreflightCapNameTheLayerAndFigure() {
        fun field(value: Any, source: String, reason: String="") = JSONObject().put("value",value).put("source",source).put("reason_code",reason)
        val fields = JSONObject().put("display_width",field(1280,"paired_client"))
            .put("display_height",field(720,"paired_client"))
        val preflight = JSONObject().put("resolved_profile",JSONObject().put("policy_version",1).put("fields",fields))
        assertEquals("Host's saved copy · 1280×720",NovaStreamSourceLine.fromPreflight(preflight).text)
        fields.put("target_bitrate_kbps",field(28000,"host_capability","host_bitrate_cap"))
        val cap = NovaStreamSourceLine.fromPreflight(preflight)
        assertEquals(NovaStreamSource.HOST_CAP,cap.source)
        assertEquals(28000,cap.capKbps)
        assertEquals("Host bitrate cap · 28 Mbps",cap.text)
    }
    @Test fun spaceAndWatchCannotPretendToBeThisDevicesRequest() {
        assertEquals(NovaStreamSource.SPACE,NovaStreamSourceLine.fromPreflight(JSONObject().put("source","worker_profile_v1")).source)
        val watch = NovaStreamSourceLine.watch(1920,1080,24)
        assertEquals(NovaStreamSource.WATCH,watch.source)
        assertTrue(watch.text.contains("24 fps"))
        assertTrue(watch.text.length <= 56)
        assertEquals("Watching",NovaStreamSourceLine.watch(0,0,0).text)
    }
}

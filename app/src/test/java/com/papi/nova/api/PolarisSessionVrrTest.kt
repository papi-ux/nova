package com.papi.nova.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolarisSessionVrrTest {
    private fun record(
        effective: Boolean = true,
        fallback: Any? = JSONObject.NULL,
        pacing: JSONObject? = JSONObject().put("fresh", true).put("state", "source_time").put("last_event", "x"),
    ) = JSONObject()
        .put("schema", 1).put("session_generation", 41).put("requested", "true").put("requested_from", "launch")
        .put("host_permission", true).put("effective", effective).put("pacing_mode", if (effective) "source_time" else "fixed_grid")
        .put("fallback_reason", fallback).put("capture_pacing", "source_driven").put("host_adaptive_sync", JSONObject.NULL)
        .apply { if (pacing != null) put("host_pacing", pacing) }

    @Test
    fun aFreshHostSourceTimeRecordIsRead() {
        val vrr = PolarisSessionVrr.parse(record())!!
        assertEquals(41L, vrr.sessionGeneration)
        assertTrue(vrr.effective)
        assertNull(vrr.fallbackReason)
        assertEquals(PolarisSessionVrr.HostPacing(true, "source_time"), vrr.hostPacing)
        assertTrue(vrr.hostSourcePaced)
    }

    @Test
    fun theHostSidePacesOnlyWithAFreshSourceTimeReceipt() {
        assertFalse(PolarisSessionVrr.parse(record(pacing = null))!!.hostSourcePaced)
        assertFalse(PolarisSessionVrr.parse(record(pacing = JSONObject().put("fresh", false).put("state", "source_time")))!!.hostSourcePaced)
        assertFalse(PolarisSessionVrr.parse(record(pacing = JSONObject().put("fresh", true).put("state", "latched_fallback")))!!.hostSourcePaced)
        val fixed = PolarisSessionVrr.parse(record(effective = false, fallback = "not_requested"))!!
        assertEquals("not_requested", fixed.fallbackReason)
        assertFalse(fixed.hostSourcePaced)
    }

    @Test
    fun absentNullAndMalformedStayDistinctFromARecord() {
        assertEquals(PolarisSessionVrr.Reading.Absent, PolarisSessionVrr.reading(JSONObject().opt("vrr")))
        assertTrue(PolarisSessionVrr.reading(JSONObject().put("vrr", JSONObject.NULL).opt("vrr")) is PolarisSessionVrr.Reading.Malformed)
        assertTrue(PolarisSessionVrr.reading("yes") is PolarisSessionVrr.Reading.Malformed)
        for (broken in listOf(
            record().put("schema", 2),
            record().put("effective", "true"),
            record().put("session_generation", 4.5),
            record().put("fallback_reason", 7),
            record().apply { remove("pacing_mode") },
            record().apply { remove("fallback_reason") },
            record().put("host_pacing", JSONObject().put("fresh", "yes").put("state", "source_time")),
            record().put("host_pacing", "source_time"),
        )) {
            assertTrue(broken.toString(), PolarisSessionVrr.reading(broken) is PolarisSessionVrr.Reading.Malformed)
        }
        assertTrue(PolarisSessionVrr.reading(record()) is PolarisSessionVrr.Reading.Present)
    }

    @Test
    fun theSessionStatusCarriesTheReadingAndAnOlderHostIsUnknown() {
        val base = JSONObject().put("state", "streaming").put("streaming_active", true)
        assertEquals(PolarisSessionVrr.Reading.Absent, PolarisApiClient.parseSessionStatusResponse(base).vrr)
        val withRecord = PolarisApiClient.parseSessionStatusResponse(JSONObject(base.toString()).put("vrr", record()))
        assertTrue((withRecord.vrr as PolarisSessionVrr.Reading.Present).record.hostSourcePaced)
    }
}

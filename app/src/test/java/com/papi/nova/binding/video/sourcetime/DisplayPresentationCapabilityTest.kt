package com.papi.nova.binding.video.sourcetime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for org.json in the report test.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DisplayPresentationCapabilityTest {
    private fun facts(sdk: Int, hz: Float, arr: Boolean? = null, alternatives: List<Float> = emptyList()) =
        DisplayFacts(
            sdkInt = sdk,
            hasArrSupport = arr,
            modeRefreshHz = hz,
            supportedRefreshRatesHz = listOf(60f, hz).distinct(),
            alternativeRefreshRatesHz = alternatives,
        )

    @Test
    fun beforeAndroid15TheDisplayRefreshesAtItsModesFixedCadence() {
        // The RP6 (expected API 33) and the Shield (API 30) are here whatever their panels run at.
        for (sdk in listOf(30, 33, 34)) {
            val capability = DisplayPresentationCapability.from(facts(sdk, 120f))
            assertEquals(ArrSupport.UNSUPPORTED, capability.arr)
            assertEquals(SourceTimeReason.DISPLAY_FIXED_REFRESH, capability.reason)
        }
    }

    @Test
    fun android15MayHaveArrButCannotSaySo() {
        val capability = DisplayPresentationCapability.from(facts(35, 120f))
        assertEquals(ArrSupport.UNKNOWN, capability.arr)
        assertEquals(SourceTimeReason.DISPLAY_CAPABILITY_UNKNOWN, capability.reason)
    }

    @Test
    fun fromAndroid16TheDisplaysOwnAnswerDecides() {
        assertEquals(ArrSupport.SUPPORTED, DisplayPresentationCapability.from(facts(36, 120f, arr = true)).arr)
        assertEquals(
            "possible, never verified on this device",
            SourceTimeReason.COMPOSITOR_UNVERIFIED,
            DisplayPresentationCapability.from(facts(36, 120f, arr = true)).reason,
        )
        assertEquals(ArrSupport.UNSUPPORTED, DisplayPresentationCapability.from(facts(36, 120f, arr = false)).arr)
        assertEquals(ArrSupport.UNKNOWN, DisplayPresentationCapability.from(facts(36, 120f, arr = null)).arr)
    }

    @Test
    fun noRefreshRateModeListOrSeamlessSwitchingIsEverArr() {
        // The negative control: inferring adaptive refresh from a high rate or from MRR modes,
        // which is what this class exists to refuse.
        fun naive(f: DisplayFacts) = f.modeRefreshHz >= 90f || f.alternativeRefreshRatesHz.isNotEmpty()
        for (sdk in listOf(30, 33, 34, 35)) {
            for (hz in listOf(90f, 120f, 144f, 165f)) {
                val f = facts(sdk, hz, alternatives = listOf(60f, 90f, hz))
                assertTrue(naive(f))
                assertNotEquals(ArrSupport.SUPPORTED, DisplayPresentationCapability.from(f).arr)
            }
        }
        val androidSixteenFixed = facts(36, 144f, arr = false, alternatives = listOf(72f, 144f))
        assertTrue(naive(androidSixteenFixed))
        assertEquals(ArrSupport.UNSUPPORTED, DisplayPresentationCapability.from(androidSixteenFixed).arr)
    }

    @Test
    fun theReportSaysWhatWasReadAndThatNothingWasRequested() {
        val json = DisplayPresentationCapability.from(
            DisplayFacts(36, true, 120f, listOf(60f, 120f), listOf(60f, 120f), 60f, 120f, 1_000_000, 8_000_000),
        ).toJson()
        assertEquals(1, json.getInt("schema"))
        assertEquals("true", json.getString("arr"))
        assertEquals("compositor_unverified", json.getString("arr_reason"))
        assertEquals("none", json.getString("adaptive_request"))
        assertEquals(120.0, json.getDouble("mode_refresh_hz"), 0.0)
        assertEquals(2, json.getJSONArray("alternative_refresh_rates_hz").length())
        assertEquals(60.0, json.getDouble("suggested_frame_rate_normal_hz"), 0.0)
        assertFalse(json.has("vrr"))

        val old = DisplayPresentationCapability.from(DisplayFacts(33, null, 60f)).toJson()
        assertEquals("false", old.getString("arr"))
        assertFalse("no answer is never written as one", old.has("suggested_frame_rate_normal_hz"))
        assertTrue(json.toString().length < 1024)
    }
}

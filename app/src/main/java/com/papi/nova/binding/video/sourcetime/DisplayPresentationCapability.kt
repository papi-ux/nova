package com.papi.nova.binding.video.sourcetime

import org.json.JSONArray
import org.json.JSONObject

/** Whether the display can change its refresh with content: yes, no, or nobody can say. */
enum class ArrSupport(val wire: String) {
    SUPPORTED("true"),
    UNSUPPORTED("false"),
    UNKNOWN("unknown"),
}

/**
 * What a display says about its own presentation timing, as read once. Null where the platform
 * has no answer at this API level; nothing here is inferred.
 */
data class DisplayFacts(
    val sdkInt: Int,
    /** Display.hasArrSupport(), API 36+; null below that or if the call failed. */
    val hasArrSupport: Boolean?,
    val modeRefreshHz: Float,
    val supportedRefreshRatesHz: List<Float> = emptyList(),
    /** Display.Mode.getAlternativeRefreshRates(), API 31+: seamless MRR switching, not ARR. */
    val alternativeRefreshRatesHz: List<Float> = emptyList(),
    /** Display.getSuggestedFrameRate(FRAME_RATE_CATEGORY_NORMAL / HIGH), API 36+. */
    val suggestedFrameRateNormalHz: Float? = null,
    val suggestedFrameRateHighHz: Float? = null,
    val appVsyncOffsetNs: Long = 0,
    val presentationDeadlineNs: Long = 0,
)

/**
 * Whether a display can do adaptive refresh, and why not, without ever asking it to.
 *
 * Android's adaptive refresh rate (ARR) needs Android 15 or later, version 3 of the hardware
 * composer API and an ARR panel (source.android.com/docs/core/graphics/arr); on any other panel
 * the display refreshes at the fixed cadence of its mode. Apps can only ask from API 36, through
 * Display.hasArrSupport(). So:
 *
 * - below API 35 the platform predates ARR: unsupported, display_fixed_refresh;
 * - at API 35 ARR may exist but cannot be asked about: unknown, display_capability_unknown;
 * - from API 36 the display answers: supported means adaptive refresh is possible but nothing has
 *   verified it on this device (compositor_unverified); unsupported is display_fixed_refresh; a
 *   failed call is unknown.
 *
 * A high refresh rate, a list of modes or seamless switching between them (MRR) is never ARR, and
 * no refresh number changes the answer. Phase 1 and 2 of #242 have no request path: this is
 * reported, never acted on.
 */
data class DisplayPresentationCapability(
    val facts: DisplayFacts,
    val arr: ArrSupport,
    /** Why adaptive refresh is not in use on this display. */
    val reason: SourceTimeReason,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("arr", arr.wire)
        put("arr_reason", reason.wire)
        put("adaptive_request", "none")
        put("sdk_int", facts.sdkInt)
        if (facts.modeRefreshHz > 0f) put("mode_refresh_hz", facts.modeRefreshHz.toDouble())
        if (facts.supportedRefreshRatesHz.isNotEmpty()) put("supported_refresh_rates_hz", rates(facts.supportedRefreshRatesHz))
        if (facts.alternativeRefreshRatesHz.isNotEmpty()) {
            put("alternative_refresh_rates_hz", rates(facts.alternativeRefreshRatesHz))
        }
        facts.suggestedFrameRateNormalHz?.let { put("suggested_frame_rate_normal_hz", it.toDouble()) }
        facts.suggestedFrameRateHighHz?.let { put("suggested_frame_rate_high_hz", it.toDouble()) }
        put("app_vsync_offset_ns", facts.appVsyncOffsetNs)
        put("presentation_deadline_ns", facts.presentationDeadlineNs)
    }

    companion object {
        const val SCHEMA = 1

        /** ARR arrived with Android 15 (API 35); apps can ask from Android 16 (API 36). */
        const val ARR_PLATFORM_SDK = 35
        const val ARR_QUERY_SDK = 36

        @JvmStatic
        fun from(facts: DisplayFacts): DisplayPresentationCapability {
            val (arr, reason) = when {
                facts.sdkInt < ARR_PLATFORM_SDK -> ArrSupport.UNSUPPORTED to SourceTimeReason.DISPLAY_FIXED_REFRESH
                facts.sdkInt < ARR_QUERY_SDK -> ArrSupport.UNKNOWN to SourceTimeReason.DISPLAY_CAPABILITY_UNKNOWN
                facts.hasArrSupport == true -> ArrSupport.SUPPORTED to SourceTimeReason.COMPOSITOR_UNVERIFIED
                facts.hasArrSupport == false -> ArrSupport.UNSUPPORTED to SourceTimeReason.DISPLAY_FIXED_REFRESH
                else -> ArrSupport.UNKNOWN to SourceTimeReason.DISPLAY_CAPABILITY_UNKNOWN
            }
            return DisplayPresentationCapability(facts, arr, reason)
        }

        private fun rates(values: List<Float>) = JSONArray().apply {
            values.forEach { put(it.toDouble()) }
        }
    }
}

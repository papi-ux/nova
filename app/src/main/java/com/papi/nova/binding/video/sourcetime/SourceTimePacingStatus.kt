package com.papi.nova.binding.video.sourcetime

/**
 * What the player asked for about host source time (Nova #242, shared definition D4).
 *
 * OFF is the legacy presentation path. AUTO uses source time whenever the path qualifies and falls
 * back silently with a recorded reason; it is the intended future default. SOURCE_TIME is an
 * explicit opt-in that falls back the same way but says so prominently. Neither ever forces a path
 * that does not qualify.
 */
enum class SourceTimeRequest(val wire: String) {
    OFF("off"),
    AUTO("auto"),
    SOURCE_TIME("source_time"),
}

/** What the renderer is actually doing. Never a claim about the display beyond its own path. */
enum class SourceTimeEffective(val wire: String) {
    LEGACY("legacy"),
    PENDING("pending"),
    SOURCE_TIME_FIXED("source_time_fixed"),
    SOURCE_TIME_VRR("source_time_vrr"),
    UNAVAILABLE("unavailable"),
}

/**
 * One machine reason per state, from the shared Nova source-time contract (W4 section 5) plus the
 * reasons ROOT accepted for Android. PACING_MODE_INCOMPATIBLE is an Android-side addition still to
 * be agreed for the shared list: source time asked for with a frame-pacing mode that has no
 * presentation queue to schedule. STALE_EPOCH (W26 F3) is the Deck delivery fence's name for a
 * frame whose timing was made under a mapper epoch that no longer exists (contract "Delivery epoch
 * fence"); Android also uses it as a state reason, listed in addendum 2 for the contract owner.
 * SOURCE_TIMESTAMP_MISSING also ends a source-paced claim that has gone a second of frames or a
 * second of time without a valid mapped target (W26 F4), as the shared contract's one-second
 * freshness expiry does on the Deck.
 */
enum class SourceTimeReason(val wire: String) {
    NONE("none"),
    NOT_REQUESTED("not_requested"),
    HOST_STATUS_MISSING("host_status_missing"),
    HOST_STATUS_STALE("host_status_stale"),
    SOURCE_TIMESTAMP_MISSING("source_timestamp_missing"),
    SOURCE_DISCONTINUITY("source_discontinuity"),
    TIMING_METADATA_MISSING("timing_metadata_missing"),
    CLOCK_MAPPING_UNSTABLE("clock_mapping_unstable"),
    DECODER_PRESSURE("decoder_pressure"),
    BUFFER_LIMIT("buffer_limit"),
    DISPLAY_FIXED_REFRESH("display_fixed_refresh"),
    DISPLAY_CAPABILITY_UNKNOWN("display_capability_unknown"),
    COMPOSITOR_UNVERIFIED("compositor_unverified"),
    PRESENT_MODE_UNSUPPORTED("present_mode_unsupported"),
    PRESENT_FEEDBACK_UNAVAILABLE("present_feedback_unavailable"),
    PRESENT_FEEDBACK_STALE("present_feedback_stale"),
    SURFACE_RECREATED("surface_recreated"),
    RENDERER_NOT_PARTICIPATING("renderer_not_participating"),
    PACING_MODE_INCOMPATIBLE("pacing_mode_incompatible"),
    STALE_EPOCH("stale_epoch"),
}

/**
 * A renderer's source-time state as a value, and its `client_presentation.frame_pacing_state`
 * wire form (the shared `st1` grammar: fixed key order, enumerated values, at most 256 ASCII
 * bytes, never a token, device identity or trace).
 */
data class SourceTimePacingStatus(
    val requested: SourceTimeRequest,
    val effective: SourceTimeEffective,
    val fallback: SourceTimeReason,
    val vrrRequested: Boolean = false,
    val vrrEffective: Boolean = false,
) {
    fun toFramePacingState(): String =
        "st1;requested=${requested.wire};effective=${effective.wire};" +
            "vrr_requested=${bit(vrrRequested)};vrr_effective=${bit(vrrEffective)};" +
            "fallback=${fallback.wire}"

    companion object {
        const val MAX_WIRE_LENGTH = 256

        private fun bit(value: Boolean) = if (value) "1" else "0"

        /** The legacy arrival path, with the reason source time is not in use. */
        @JvmStatic
        fun legacy(requested: SourceTimeRequest, reason: SourceTimeReason): SourceTimePacingStatus =
            SourceTimePacingStatus(requested, SourceTimeEffective.LEGACY, reason)

        /** A renderer that cannot pace by design, whatever was asked for. */
        @JvmStatic
        fun notParticipating(requested: SourceTimeRequest): SourceTimePacingStatus =
            SourceTimePacingStatus(
                requested,
                SourceTimeEffective.UNAVAILABLE,
                SourceTimeReason.RENDERER_NOT_PARTICIPATING,
            )
    }
}

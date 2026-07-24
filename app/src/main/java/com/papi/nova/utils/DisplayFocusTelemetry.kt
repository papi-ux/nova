package com.papi.nova.utils

/** Stable, privacy-safe focus ownership events for dual-display field diagnostics. */
object DisplayFocusTelemetry {
    @JvmStatic
    fun game(displayId: Int, hasWindowFocus: Boolean, isGameTopResumed: Boolean): String =
        format("game", displayId, hasWindowFocus, isGameTopResumed)

    @JvmStatic
    fun companion(displayId: Int, hasWindowFocus: Boolean, isGameTopResumed: Boolean): String =
        format("companion", displayId, hasWindowFocus, isGameTopResumed)

    private fun format(
        role: String,
        displayId: Int,
        hasWindowFocus: Boolean,
        isGameTopResumed: Boolean,
    ): String =
        "Nova: Android display focus role=$role display_id=$displayId " +
            "window=$hasWindowFocus game_top_resumed=$isGameTopResumed"
}

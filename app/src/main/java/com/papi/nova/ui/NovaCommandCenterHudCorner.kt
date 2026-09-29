package com.papi.nova.ui

import com.papi.nova.ui.panel.NovaPanelMetrics

/**
 * Where the HUD sits across the stream window, for the Command Center's HUD rows, which say when
 * the panel covers it (in-game #4, review finding 2).
 *
 * The HUD stores its position on every mode change as well as on every drag, so a stored position
 * is where the HUD is, and a HUD that never stored one sits in its own corner, the top start, at
 * the margin it keeps from the screen's edges as NovaStreamHud lays it out: 12dp, or on a
 * television its title-safe 48dp at the sides.
 */
object NovaCommandCenterHudCorner {
    /** The HUD's margin from the screen's edges away from a television, NovaStreamHud's own. */
    const val MARGIN_DP = 12f

    /** The preference NovaStreamHud stores its left edge in, in pixels. */
    const val PREF_HUD_X = "nova_polaris_hud_x"

    /** The HUD's left edge in pixels: [storedX], or its corner's margin when none was stored (NaN). */
    fun leftPx(storedX: Float, density: Float, television: Boolean): Float {
        if (!storedX.isNaN()) return storedX
        val margin = if (television) NovaPanelMetrics.TvSafeHorizontal.value else MARGIN_DP
        return margin * density
    }
}

package com.papi.nova.ui

import com.papi.nova.ui.panel.NovaPanelMetrics

/**
 * Where the HUD sits across the stream window, for the Command Center's HUD rows, which say when
 * the panel covers it (in-game #4, review finding 2).
 *
 * Use the HUD's measured position. Before layout, its initial corner is the top start at its
 * normal margin: 12dp, or a television's title-safe 48dp at the sides.
 */
object NovaCommandCenterHudCorner {
    /** The HUD's margin from the screen's edges away from a television, NovaStreamHud's own. */
    const val MARGIN_DP = 12f

    /** The HUD's left edge in pixels: [measuredX], or its initial margin before layout (NaN). */
    fun leftPx(measuredX: Float, density: Float, television: Boolean): Float {
        if (measuredX.isFinite()) return measuredX
        val margin = if (television) NovaPanelMetrics.TvSafeHorizontal.value else MARGIN_DP
        return margin * density
    }
}

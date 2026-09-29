package com.papi.nova.ui

import com.papi.nova.ui.panel.NovaPanelMetrics
import kotlin.math.abs

/**
 * Whether the HUD sits in its own corner, the top start, which the Command Center's edge panel
 * covers (in-game #4).
 *
 * A stored position says nothing by itself: the HUD saves where it is on every mode change as well
 * as on every drag, and papi's RP6 stores 27.675 for both, which is the corner. So the stored
 * position is compared with the corner, the margin the HUD keeps from the screen's edges as
 * NovaStreamHud lays it out: 12dp, or on a television its title-safe 48dp at the sides and 27dp at
 * the top.
 */
object NovaCommandCenterHudCorner {
    /** The HUD's margin from the screen's edges away from a television, NovaStreamHud's own. */
    const val MARGIN_DP = 12f

    /** How far from the corner still counts as in it: the rounding of a stored pixel, and a nudge. */
    const val TOLERANCE_DP = 4f

    /** [x] and [y] are the stored position in pixels, NaN when none was ever stored. */
    fun isAtItsCorner(x: Float, y: Float, density: Float, television: Boolean): Boolean {
        if (x.isNaN() || y.isNaN()) return true
        val marginX = if (television) NovaPanelMetrics.TvSafeHorizontal.value else MARGIN_DP
        val marginY = if (television) NovaPanelMetrics.TvSafeVertical.value else MARGIN_DP
        val tolerance = TOLERANCE_DP * density
        return abs(x - marginX * density) <= tolerance && abs(y - marginY * density) <= tolerance
    }
}

package com.papi.nova.ui.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papi.nova.ui.NovaSheetChrome

/**
 * The corner scale for the whole app.
 *
 * Nova had thirteen distinct corner values across seventy-odd call sites, and two spellings
 * of "round this fully" — 99.dp and 999.dp — that are indistinguishable at every size the app
 * actually draws. The game detail window was rebuilt onto three steps and a pill; this is that
 * scale, lifted out of the detail window so the rest of the app shares it instead of each
 * screen picking a number.
 *
 * Three steps is the point rather than an accident of taste. A scale with a step every two dp
 * is one nobody can hold in their head, and that is how thirteen values happen: each is
 * individually defensible and the set is not.
 *
 * Sheets are deliberately outside this. [NovaSheetChrome.SHEET_CORNER_RADIUS_DP] stays at 26dp
 * because a sheet reads as an edge of the screen rather than as a card on it.
 */
object NovaRadius {
    /** chips, tabs, the scope switch — anything under roughly 24dp tall */
    val chip: Dp = 4.dp

    /** every selectable row, card and artwork tile */
    val row: Dp = 6.dp

    /** the primary action, the panels, notice cards */
    val hero: Dp = 8.dp

    /**
     * Fully round: pills, badges and status dots.
     *
     * A Dp rather than CircleShape so it sits in the same scale as the other three and reads
     * identically at the call site — every use is already inside a RoundedCornerShape(...).
     */
    val pill: Dp = 999.dp

    /**
     * The inner edge of a side drawer.
     *
     * Tied to the sheet radius rather than given its own number, because a drawer and a
     * sheet are the same gesture from different edges of the screen. The three drawers were
     * drawing 24dp, 24dp and 28dp for one concept, which is the drift this scale exists to
     * stop -- and a sheet sitting next to a drawer at a different radius is the visible
     * version of that.
     */
    val drawer: Dp = NovaSheetChrome.SHEET_CORNER_RADIUS_DP.dp
}

package com.papi.nova.ui.panel

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaFormFactor

/**
 * A full-screen surface's content padding: [base] on every side, and on a television at least the
 * title-safe area, 48dp at the sides and 27dp at top and bottom. Only panels kept it before, so on
 * the Shield the library header, the first poster column and the host list sat 9 to 35dp from the
 * edge, where a television's overscan cuts them.
 */
@Composable
fun novaScreenPadding(base: Dp): PaddingValues =
    novaScreenPadding(base, LocalNovaFormFactor.current == NovaFormFactor.Television)

internal fun novaScreenPadding(base: Dp, television: Boolean): PaddingValues =
    if (television) {
        PaddingValues(
            horizontal = maxOf(base, NovaPanelMetrics.TvSafeHorizontal),
            vertical = maxOf(base, NovaPanelMetrics.TvSafeVertical),
        )
    } else {
        PaddingValues(base)
    }

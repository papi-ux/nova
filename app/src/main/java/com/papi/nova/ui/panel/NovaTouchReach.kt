package com.papi.nova.ui.panel

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A touch target [extra] taller above and below than the element it wraps, which still takes only
 * the element's own height in the layout: [target], the tap and its meaning, spans the reach, and
 * the element is drawn inside it where it would have been. A compact header's 40dp back line and a
 * 40dp Reset keep their look and their place, and a finger gets the 48dp a target needs (C24).
 *
 * The reach is drawn past the element's parent, so a clip on the way up would cut it back, and it
 * only takes a tap that nothing drawn over it takes first.
 */
internal fun Modifier.novaTouchReach(extra: Dp, target: Modifier): Modifier {
    if (extra <= 0.dp) return this.then(target)
    return this
        .layout { measurable, constraints ->
            val reach = extra.roundToPx()
            val placeable = measurable.measure(
                if (constraints.hasBoundedHeight) constraints.copy(maxHeight = constraints.maxHeight + reach * 2) else constraints,
            )
            layout(placeable.width, (placeable.height - reach * 2).coerceAtLeast(0)) {
                placeable.place(0, -reach)
            }
        }
        .then(target)
        .padding(vertical = extra)
}

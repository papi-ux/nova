package com.papi.nova.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil

/** Sizes from the actual Stage content height. Posters stay exactly 2:3, without focus gutters. */
internal data class NovaLibraryStageGeometry(
    val selected: NovaPortraitPosterSize,
    val neighbour: NovaPortraitPosterSize,
    val infoHeightDp: Int,
    val posterGapDp: Int = 12,
    val selectedGapDp: Int = 20,
    val positionHeightDp: Int = 18,
)

/** Each text line rounds independently; artwork width/aspect and card height round too. */
internal fun novaLibraryStageCaptionHeightDp(lineHeightPx: Float, topPaddingPx: Int, density: Float): Int {
    val twoLinesPx = 2 * ceil(lineHeightPx).toInt()
    // The separately rounded art width/aspect and card height can consume up to two pixels.
    return ceil((twoLinesPx + topPaddingPx + 2) / density).toInt()
}

internal fun novaLibraryStageGeometry(widthDp: Int, heightDp: Int, fontScale: Float, captionHeightDp: Int = 0): NovaLibraryStageGeometry {
    val positionHeight = max(18, ceil(14f * fontScale).toInt())
    val selectedUnits = min((heightDp - positionHeight).coerceAtLeast(3) / 3, (widthDp * 0.30f).toInt().coerceAtLeast(2) / 2)
    val selected = NovaPortraitPosterSize(selectedUnits * 2, selectedUnits * 3)
    val naturalInfoHeight = (128f + 64f * (fontScale - 1f).coerceAtLeast(0f)).toInt()
    // On a short large-text window the identity uses one title/stats line, keeping row
    // covers at their readable 132dp floor when both can fit. With large-text captions,
    // preserve the readable identity and both caption lines before slightly reducing covers.
    // Full titles remain semantic.
    val minimumReadableInfo = ceil(47f * fontScale + 8f).toInt()
    val infoWithRowFloor = (selected.heightDp - 132 - 16 - captionHeightDp).coerceAtLeast(0)
    val minimumInfoHeight = when {
        infoWithRowFloor >= minimumReadableInfo -> min(naturalInfoHeight, infoWithRowFloor)
        captionHeightDp > 0 && fontScale >= 1.5f -> min(naturalInfoHeight, minimumReadableInfo)
        else -> naturalInfoHeight
    }
    val rowHeight = min((heightDp * 0.51f).toInt().coerceIn(132, 240),
        (selected.heightDp - minimumInfoHeight - 16 - captionHeightDp).coerceAtLeast(0))
    val rowUnits = rowHeight / 3
    return NovaLibraryStageGeometry(
        selected = selected,
        neighbour = NovaPortraitPosterSize(rowUnits * 2, rowUnits * 3),
        infoHeightDp = (selected.heightDp - rowUnits * 3 - 16 - captionHeightDp).coerceAtLeast(0),
        positionHeightDp = positionHeight,
    )
}

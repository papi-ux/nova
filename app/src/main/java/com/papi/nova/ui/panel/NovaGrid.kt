package com.papi.nova.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier

/**
 * How many columns a page of [width] lays its rows in: two for a [NovaPanelWidth.Grid] page on a
 * compact landscape panel, which is Wide for them, and one everywhere else, including the
 * portrait sheet and a television.
 */
@Composable
@ReadOnlyComposable
internal fun novaPanelColumns(width: NovaPanelWidth): Int =
    if (width == NovaPanelWidth.Grid && LocalNovaPanelDensity.current == NovaPanelDensity.Compact && LocalNovaPanelFillsHeight.current) {
        2
    } else {
        1
    }

/**
 * [items] in lines of [columns], in their own order, so reading and the D-pad go line by line.
 * An item that [standsAlone] takes a line of its own, and a short line keeps its columns, so its
 * tiles stay as wide as the ones above them.
 */
internal fun <T> novaGridRows(items: List<T>, columns: Int, standsAlone: (T) -> Boolean = { false }): List<List<T>> {
    val lines = mutableListOf<List<T>>()
    val run = mutableListOf<T>()
    fun flush() {
        lines.addAll(run.chunked(columns.coerceAtLeast(1)))
        run.clear()
    }
    items.forEach { item ->
        if (standsAlone(item)) {
            flush()
            lines += listOf(item)
        } else {
            run += item
        }
    }
    flush()
    return lines
}

/**
 * One line of a grid: [cells] side by side at equal widths and one height, as far apart as rows
 * are, with room kept for the columns a short line lacks. With one column the cell is the full
 * width row it always was. [cell] draws each with the modifier that places it.
 */
@Composable
internal fun <T> NovaGridRow(cells: List<T>, columns: Int, cell: @Composable (T, Modifier) -> Unit) {
    if (columns <= 1) {
        cells.forEach { cell(it, Modifier.fillMaxWidth()) }
        return
    }
    // One height for the line: a caption that takes a second line lifts its neighbour too.
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
    ) {
        cells.forEach { cell(it, Modifier.weight(1f).fillMaxHeight()) }
        repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
    }
}

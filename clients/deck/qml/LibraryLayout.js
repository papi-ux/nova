.pragma library

// Android's viewport solver: Grid favors large posters with a glimpse of the
// next row; Compact fits two complete poster rows whenever the viewport allows.
function columns(width, height, mode, unit) {
    const compact = mode === "compact"
    const base = compact ? 6 : 5
    const targetRows = compact ? 2 : 1
    let rowsOnly = 0
    let first = 0
    for (let count = base; count <= base + 6; ++count) {
        const posterWidth = width / count - 24 * unit
        if (posterWidth < 80 * unit) break
        if (!first) first = count
        const pitch = posterWidth * 1.5 + 20 * unit
        const rows = Math.floor(height / pitch)
        if (rows >= targetRows) {
            if (height - rows * pitch >= 20 * unit) return count
            if (!rowsOnly) rowsOnly = count
        }
    }
    return rowsOnly || first || Math.max(1, Math.floor(width / (104 * unit)))
}

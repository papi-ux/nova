.pragma library

// Semantics mirror Android's NovaLibraryUiStateMapper. Work on copies so a
// local view never changes host order or the backend's game/launch identity.
var sortChoices = [
    { id: "library", title: "Library order" },
    { id: "recent", title: "Recently played" },
    { id: "name", title: "Name A–Z" },
    { id: "name-desc", title: "Name Z–A" },
    { id: "source", title: "Source" },
    { id: "hdr", title: "HDR first" }
]
function text(value) { return value === undefined || value === null ? "" : String(value) }
function compare(a, b) { return a < b ? -1 : a > b ? 1 : 0 }
function stableSort(items, comparator) {
    return items.map((game, index) => ({ game: game, index: index }))
        .sort((a, b) => comparator(a.game, b.game) || a.index - b.index).map(item => item.game)
}
function sourceOrder(source) {
    return source === "steam" ? 0 : source === "lutris" ? 1 : source === "heroic" ? 2 : 3
}
function sourceLabel(source) {
    const labels = { steam: "Steam", lutris: "Lutris", heroic: "Heroic", manual: "Manual",
        emulator: "Emulator", gamestream: "GameStream", moonlight: "Moonlight" }
    return Object.prototype.hasOwnProperty.call(labels, source) ? labels[source] : source
}
function categoryLabel(category) {
    const labels = { fast_action: "Action", cinematic: "Cinematic", desktop: "Desktop", vr: "VR" }
    return Object.prototype.hasOwnProperty.call(labels, category) ? labels[category] : ""
}
function normalizedFilter(primary, value) {
    if (primary === "recent" || primary === "hdr") return { primary: primary, value: "" }
    if (["source", "category", "genre"].indexOf(primary) >= 0 && text(value).trim())
        return { primary: primary, value: text(value).trim() }
    return { primary: "all", value: "" }
}
function filterTitle(primary, value) {
    if (primary === "recent") return "Recent games"
    if (primary === "hdr") return "HDR games"
    if (primary === "source") return sourceLabel(value)
    if (primary === "category") return categoryLabel(value) || value
    if (primary === "genre") return value
    return "All games"
}
function select(games, search, primary, value, sort) {
    const query = text(search).trim().toLowerCase()
    let items = games.filter(function(game) {
        if (text(game.title).toLowerCase().indexOf(query) < 0) return false
        if (primary === "recent") return game.lastLaunched > 0
        if (primary === "hdr") return game.hdrSupported === true
        if (primary === "source") return game.source === value
        if (primary === "category") return game.category === value
        if (primary === "genre") return (game.genres || []).some(genre => text(genre).toLowerCase() === text(value).toLowerCase())
        return true
    })
    if (primary === "recent") items = stableSort(items, (a, b) => (b.lastLaunched || 0) - (a.lastLaunched || 0))
    const name = (a, b) => compare(text(a.title).toLowerCase(), text(b.title).toLowerCase())
    if (sort === "name") items = stableSort(items, name)
    else if (sort === "name-desc") items = stableSort(items, (a, b) => -name(a, b))
    else if (sort === "recent") items = stableSort(items, (a, b) =>
        Number(b.lastLaunched > 0) - Number(a.lastLaunched > 0) ||
        (b.lastLaunched || 0) - (a.lastLaunched || 0) || name(a, b))
    else if (sort === "source") items = stableSort(items, (a, b) =>
        sourceOrder(text(a.source).toLowerCase()) - sourceOrder(text(b.source).toLowerCase()) ||
        compare(text(a.source).toLowerCase(), text(b.source).toLowerCase()))
    else if (sort === "hdr") items = stableSort(items, (a, b) => Number(b.hdrSupported === true) - Number(a.hdrSupported === true) || name(a, b))
    return items
}
function sources(games) {
    const sources = []
    games.forEach(game => {
        if (text(game.source).trim() && sources.indexOf(game.source) < 0) sources.push(game.source)
    })
    sources.sort((a, b) => sourceOrder(a) - sourceOrder(b) || compare(a, b))
    return sources.map(source => ({ id: source, title: sourceLabel(source), primary: "source" }))
}
function moreFilters(games) {
    const categories = ["fast_action", "cinematic", "desktop", "vr"].filter(category => games.some(game => game.category === category))
        .map(category => ({ id: category, title: categoryLabel(category), primary: "category", section: "Category" }))
    const genres = []
    games.forEach(game => (game.genres || []).forEach(raw => {
        const genre = text(raw).trim()
        if (genre && !genres.some(value => value.toLowerCase() === genre.toLowerCase())) genres.push(genre)
    }))
    genres.sort(compare)
    return categories.concat(genres.slice(0, 10).map(genre => ({ id: genre, title: genre, primary: "genre", section: "Genre" })))
}

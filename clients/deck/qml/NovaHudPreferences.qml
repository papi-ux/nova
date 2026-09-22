pragma Singleton
import QtQuick
import QtCore

QtObject {
    id: hud
    property Settings preferences: Settings {
        category: "NovaHUD"
        property bool enabled: false
        property string mode: "minimal"
        property int panelOpacity: 64
        property real positionX: 0
        property real positionY: 0
    }
    readonly property var modes: [
        { id: "slim", title: "Slim" }, { id: "minimal", title: "Minimal" },
        { id: "performance", title: "Performance" }, { id: "debug", title: "Debug" }
    ]
    readonly property bool enabled: preferences.enabled
    readonly property string mode: modes.some(item => item.id === preferences.mode) ? preferences.mode : "minimal"
    readonly property string title: modes.find(item => item.id === mode).title
    readonly property int panelOpacity: Math.max(0, Math.min(100, preferences.panelOpacity))
    readonly property real positionX: bounded(preferences.positionX)
    readonly property real positionY: bounded(preferences.positionY)
    function bounded(value) { return Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0 }
    function setEnabled(value) { preferences.enabled = value }
    function setMode(value) { if (modes.some(item => item.id === value)) preferences.mode = value }
    function cycleMode() {
        const ring = ["minimal", "performance", "debug", "slim"]
        setMode(ring[(ring.indexOf(mode) + 1) % ring.length])
    }
    function setOpacity(value) { if (Number.isFinite(value)) preferences.panelOpacity = Math.round(bounded(value / 100) * 100) }
    function setPosition(x, y) { preferences.positionX = bounded(x); preferences.positionY = bounded(y) }
}

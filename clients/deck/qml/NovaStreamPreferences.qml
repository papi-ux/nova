pragma Singleton
import QtQuick
import QtCore

QtObject {
    property Settings preferences: Settings { category: "InGameControls" }
    property bool commandCenterButton: true
    property bool shortcutHint: true
    property int menuOpacityPercent: 64
    Component.onCompleted: {
        commandCenterButton = preferences.value("commandCenterButton", true)
        shortcutHint = preferences.value("shortcutHint", true)
        const savedOpacity = preferences.value("menuOpacityPercent", 64)
        // Unix QSettings reloads INI numbers as strings. Accept only integer
        // decimal text here; malformed, fractional and out-of-range saves use64.
        const opacityNumber = typeof savedOpacity === "number" ? savedOpacity
            : typeof savedOpacity === "string" && /^\d+$/.test(savedOpacity.trim()) ? Number(savedOpacity.trim()) : NaN
        menuOpacityPercent = Number.isInteger(opacityNumber) && opacityNumber >= 0 && opacityNumber <= 100 ? opacityNumber : 64
    }
    function setCommandCenterButton(value) {
        commandCenterButton = value
        preferences.setValue("commandCenterButton", value)
    }
    // Device appearance only; this never changes HUD opacity or session authority.
    function setMenuOpacity(value) {
        if (!Number.isFinite(value)) return
        menuOpacityPercent = Math.max(0, Math.min(100, Math.round(value)))
        preferences.setValue("menuOpacityPercent", menuOpacityPercent)
    }
    function setShortcutHint(value) {
        shortcutHint = value
        preferences.setValue("shortcutHint", value)
    }
}

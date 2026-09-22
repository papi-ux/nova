pragma Singleton
import QtQuick
import QtCore

QtObject {
    property Settings preferences: Settings { category: "InGameControls" }
    property bool commandCenterButton: true
    property bool shortcutHint: true
    Component.onCompleted: {
        commandCenterButton = preferences.value("commandCenterButton", true)
        shortcutHint = preferences.value("shortcutHint", true)
    }
    function setCommandCenterButton(value) {
        commandCenterButton = value
        preferences.setValue("commandCenterButton", value)
    }
    function setShortcutHint(value) {
        shortcutHint = value
        preferences.setValue("shortcutHint", value)
    }
}

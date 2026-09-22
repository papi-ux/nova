import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: rumble
    objectName: "rumble-settings-popup"
    required property var settingsProvider
    property real unit: 1
    property string error: ""
    anchors.centerIn: Overlay.overlay
    width: Math.min(620 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 28 * unit
    modal: true
    focus: true
    enter: Transition { }
    exit: Transition { }
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    onAboutToShow: error = ""
    onOpened: enabledButton.forceActiveFocus()
    function state() {
        function center(item) {
            const point = item.mapToItem(null, item.width / 2, item.height / 2)
            return { x: Math.round(point.x), y: Math.round(point.y) }
        }
        return { opened: opened, enabled: settingsProvider.rumbleEnabled, error: error,
            toggle: center(enabledButton), reset: center(resetButton), done: center(done) }
    }
    background: Rectangle { color: NovaTheme.panel; radius: 12 * unit; border.color: NovaTheme.divider }
    component Copy: Label {
        textFormat: Text.PlainText
        color: NovaTheme.text
        font.pixelSize: 18 * rumble.unit * NovaTheme.fontScale
        wrapMode: Text.WordWrap
        Layout.fillWidth: true
    }
    component Action: NovaButton { unit: rumble.unit; Layout.fillWidth: true }
    contentItem: NovaScrollColumn {
        spacing: 16 * rumble.unit
        Copy { text: "Controller rumble"; font.pixelSize: 28 * rumble.unit * NovaTheme.fontScale; font.bold: true }
        Copy { text: "This device · Applies to the next new stream"; color: NovaTheme.secondary }
        Copy { text: "Feel vibration from your game on the active controller when supported by SteamOS. Other controllers stay quiet."; color: NovaTheme.secondary }
        Action {
            id: enabledButton
            objectName: "rumble-enabled"
            text: "Controller rumble: " + (settingsProvider.rumbleEnabled ? "On" : "Off")
            onClicked: rumble.error = settingsProvider.setRumbleEnabled(!settingsProvider.rumbleEnabled)
                ? "" : "Couldn't save rumble settings. Try again."
            Keys.onDownPressed: resetButton.forceActiveFocus()
        }
        Copy { text: "Rumble pauses when you open controls or leave the game."; color: NovaTheme.secondary }
        Copy { visible: rumble.error.length > 0; text: rumble.error; color: NovaTheme.warning }
        Action {
            id: resetButton
            objectName: "rumble-reset"
            text: "Reset rumble default"
            onClicked: rumble.error = settingsProvider.resetRumble() ? "" : "Couldn't reset rumble settings. Try again."
            Keys.onUpPressed: enabledButton.forceActiveFocus()
            Keys.onDownPressed: done.forceActiveFocus()
        }
        Action {
            id: done
            objectName: "rumble-done"
            text: "Back to System"
            onClicked: rumble.close()
            Keys.onUpPressed: resetButton.forceActiveFocus()
        }
    }
}

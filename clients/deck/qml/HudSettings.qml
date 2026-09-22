import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: settings
    objectName: "hud-settings-popup"
    property real unit: 1
    anchors.centerIn: Overlay.overlay
    width: Math.min(600 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 24 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    onOpened: toggle.forceActiveFocus()
    background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
    contentItem: NovaScrollColumn {
        spacing: 12 * settings.unit
        Label { text: "NovaHUD"; color: NovaTheme.text; font.pixelSize: 28 * settings.unit * NovaTheme.fontScale; font.bold: true }
        Label {
            Layout.fillWidth: true
            text: "Stream readings at a glance. Drag the HUD to move it; hold it to open Command Center."
            wrapMode: Text.WordWrap; color: NovaTheme.secondary
            font.pixelSize: 17 * settings.unit * NovaTheme.fontScale
        }
        NovaButton {
            id: toggle
            objectName: "hud-toggle"
            Layout.fillWidth: true; unit: settings.unit
            text: "NovaHUD: " + (NovaHudPreferences.enabled ? "On" : "Off")
            Accessible.checkable: true; Accessible.checked: NovaHudPreferences.enabled
            onClicked: NovaHudPreferences.setEnabled(!NovaHudPreferences.enabled)
            Keys.onDownPressed: choices.itemAt(0).forceActiveFocus()
        }
        Repeater {
            id: choices
            model: NovaHudPreferences.modes
            NovaButton {
                required property var modelData
                required property int index
                objectName: "hud-mode-" + modelData.id
                Layout.fillWidth: true; unit: settings.unit
                text: modelData.title + (NovaHudPreferences.mode === modelData.id ? "  ✓" : "")
                Accessible.checkable: true; Accessible.checked: NovaHudPreferences.mode === modelData.id
                onClicked: NovaHudPreferences.setMode(modelData.id)
                Keys.onUpPressed: index ? choices.itemAt(index - 1).forceActiveFocus() : toggle.forceActiveFocus()
                Keys.onDownPressed: index < choices.count - 1 ? choices.itemAt(index + 1).forceActiveFocus() : alpha.forceActiveFocus()
            }
        }
        NovaButton {
            id: alpha
            objectName: "hud-opacity"
            Layout.fillWidth: true; unit: settings.unit
            text: "Background opacity: " + NovaHudPreferences.panelOpacity + "%"
            onClicked: {
                const values = [0, 25, 64, 90, 100]
                NovaHudPreferences.setOpacity(values[(values.indexOf(NovaHudPreferences.panelOpacity) + 1) % values.length])
            }
            Keys.onUpPressed: choices.itemAt(choices.count - 1).forceActiveFocus()
            Keys.onDownPressed: position.forceActiveFocus()
        }
        NovaButton {
            id: position
            objectName: "hud-position"
            Layout.fillWidth: true; unit: settings.unit
            text: "Position: " + (NovaHudPreferences.positionY >= 0.5 ? "Bottom " : "Top ") + (NovaHudPreferences.positionX >= 0.5 ? "right" : "left")
            onClicked: {
                const x = NovaHudPreferences.positionX >= 0.5, y = NovaHudPreferences.positionY >= 0.5
                NovaHudPreferences.setPosition(y ? 0 : 1, x ? 1 : 0)
            }
            Keys.onUpPressed: alpha.forceActiveFocus()
            Keys.onDownPressed: done.forceActiveFocus()
        }
        Label {
            Layout.fillWidth: true
            text: "FPS counts composed frames, not display refreshes. Compact BIT and Debug VIDEO measure received video; Debug HOST BIT shows encoder-confirmed bitrate. Change Live Tuning in Command Center. Dashes mean unavailable; LOSS never substitutes control-channel loss."
            wrapMode: Text.WordWrap; color: NovaTheme.secondary
            font.pixelSize: 15 * settings.unit * NovaTheme.fontScale
        }
        NovaButton {
            id: done
            objectName: "hud-done"
            Layout.fillWidth: true; unit: settings.unit
            text: "Done"
            onClicked: settings.close()
            Keys.onUpPressed: position.forceActiveFocus()
        }
    }
}

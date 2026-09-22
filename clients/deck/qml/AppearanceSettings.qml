import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: appearance
    objectName: "appearance-settings-popup"
    property real unit: 1
    anchors.centerIn: Overlay.overlay
    width: Math.min(620 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 24 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    onOpened: themes.itemAt(NovaTheme.choices.findIndex(choice => choice.id === NovaTheme.themeId)).forceActiveFocus()
    background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
    contentItem: ColumnLayout {
        spacing: 12 * appearance.unit
        Label { text: "Appearance"; color: NovaTheme.text; font.pixelSize: 28 * appearance.unit * NovaTheme.fontScale; font.bold: true }
        Label { text: "Theme · " + NovaTheme.label; color: NovaTheme.secondary; font.pixelSize: 18 * appearance.unit * NovaTheme.fontScale }
        NovaScrollColumn {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.minimumHeight: 0
            spacing: 12 * appearance.unit
        Repeater {
            id: themes
            model: NovaTheme.choices
            NovaButton {
                required property var modelData
                required property int index
                objectName: "appearance-theme-" + modelData.id
                unit: appearance.unit
                Layout.fillWidth: true
                text: modelData.title + (NovaTheme.themeId === modelData.id ? "  ✓" : "")
                Accessible.checkable: true
                Accessible.checked: NovaTheme.themeId === modelData.id
                onClicked: NovaTheme.setTheme(modelData.id)
                Keys.onUpPressed: themes.itemAt(Math.max(0, index - 1)).forceActiveFocus()
                Keys.onDownPressed: index < themes.count - 1 ? themes.itemAt(index + 1).forceActiveFocus() : textSize.forceActiveFocus()
            }
        }
        NovaButton {
            id: textSize
            objectName: "appearance-text-size"
            unit: appearance.unit
            Layout.fillWidth: true
            text: "Text size: " + Math.round(NovaTheme.fontScale * 100) + "%"
            onClicked: NovaTheme.setFontScale(NovaTheme.fontScale === 1 ? 1.15 : NovaTheme.fontScale === 1.15 ? 1.3 : 1)
            Keys.onUpPressed: themes.itemAt(themes.count - 1).forceActiveFocus()
            Keys.onDownPressed: commandCenterButton.forceActiveFocus()
        }
        Label {
            Layout.fillWidth: true
            text: "In-game controls"
            color: NovaTheme.text; font.bold: true
            font.pixelSize: 20 * appearance.unit * NovaTheme.fontScale
        }
        NovaButton {
            id: commandCenterButton
            objectName: "appearance-command-center-button"
            unit: appearance.unit
            Layout.fillWidth: true
            text: "Command Center button: " + (NovaStreamPreferences.commandCenterButton ? "On" : "Off")
            Accessible.checkable: true
            Accessible.checked: NovaStreamPreferences.commandCenterButton
            onClicked: NovaStreamPreferences.setCommandCenterButton(!NovaStreamPreferences.commandCenterButton)
            Keys.onUpPressed: textSize.forceActiveFocus()
            Keys.onDownPressed: shortcutHint.forceActiveFocus()
        }
        NovaButton {
            id: shortcutHint
            objectName: "appearance-shortcut-hint"
            unit: appearance.unit
            Layout.fillWidth: true
            text: "Controller shortcut hint: " + (NovaStreamPreferences.shortcutHint ? "On" : "Off")
            Accessible.checkable: true
            Accessible.checked: NovaStreamPreferences.shortcutHint
            onClicked: NovaStreamPreferences.setShortcutHint(!NovaStreamPreferences.shortcutHint)
            Keys.onUpPressed: commandCenterButton.forceActiveFocus()
            Keys.onDownPressed: done.forceActiveFocus()
        }
        DeckMenuShortcut { unit: appearance.unit }
        Label {
            Layout.fillWidth: true
            text: "Open Command Center with this shortcut even when both are hidden. Without a controller, the touch button stays available."
            color: NovaTheme.secondary; wrapMode: Text.WordWrap
            font.pixelSize: 16 * appearance.unit * NovaTheme.fontScale
        }
        NovaButton {
            id: done
            objectName: "appearance-done"
            unit: appearance.unit
            Layout.fillWidth: true
            text: "Done"
            onClicked: appearance.close()
            Keys.onUpPressed: shortcutHint.forceActiveFocus()
        }
        }
    }
}

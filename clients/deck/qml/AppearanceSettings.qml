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
        spacing: 12 * appearance.unit * NovaTheme.controlScale
        Label { text: "Appearance"; color: NovaTheme.text; font.pixelSize: 28 * appearance.unit * NovaTheme.fontScale; font.bold: true }
        Label { Layout.fillWidth: true; text: "Theme · " + NovaTheme.label; color: NovaTheme.secondary; wrapMode: Text.WordWrap; font.pixelSize: 18 * appearance.unit * NovaTheme.fontScale }
        NovaScrollColumn {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.minimumHeight: 0
            spacing: 12 * appearance.unit * NovaTheme.controlScale
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
                Keys.onDownPressed: index < themes.count - 1 ? themes.itemAt(index + 1).forceActiveFocus() : sizes.itemAt(0).forceActiveFocus()
            }
        }
        Label {
            Layout.fillWidth: true
            text: "Interface Size"
            color: NovaTheme.text; font.bold: true
            font.pixelSize: 20 * appearance.unit * NovaTheme.fontScale
        }
        Repeater {
            id: sizes
            model: NovaTheme.controlSizes
            NovaButton {
                required property var modelData
                required property int index
                objectName: "appearance-control-size-" + modelData.id
                unit: appearance.unit
                Layout.fillWidth: true
                text: modelData.title + (NovaTheme.controlSize === modelData.id ? "  ✓" : "")
                Accessible.checkable: true
                Accessible.checked: NovaTheme.controlSize === modelData.id
                onClicked: NovaTheme.setControlSize(modelData.id)
                Keys.onUpPressed: index > 0 ? sizes.itemAt(index - 1).forceActiveFocus() : themes.itemAt(themes.count - 1).forceActiveFocus()
                Keys.onDownPressed: index < sizes.count - 1 ? sizes.itemAt(index + 1).forceActiveFocus() : textSize.forceActiveFocus()
            }
        }
        NovaButton {
            id: textSize
            objectName: "appearance-text-size"
            unit: appearance.unit
            Layout.fillWidth: true
            text: "Text Size: " + Math.round(NovaTheme.fontScale * 100) + "%"
            onClicked: textEditor.open()
            Keys.onUpPressed: sizes.itemAt(sizes.count - 1).forceActiveFocus()
            Keys.onDownPressed: commandCenterButton.forceActiveFocus()
        }
        Label {
            Layout.fillWidth: true
            text: "In-Game Controls"
            color: NovaTheme.text; font.bold: true
            font.pixelSize: 20 * appearance.unit * NovaTheme.fontScale
        }
        NovaButton {
            id: commandCenterButton
            objectName: "appearance-command-center-button"
            unit: appearance.unit
            Layout.fillWidth: true
            text: "Command Center Button: " + (NovaStreamPreferences.commandCenterButton ? "On" : "Off")
            Accessible.checkable: true
            Accessible.checked: NovaStreamPreferences.commandCenterButton
            onClicked: NovaStreamPreferences.setCommandCenterButton(!NovaStreamPreferences.commandCenterButton)
            Keys.onUpPressed: textSize.forceActiveFocus()
            Keys.onDownPressed: menuOpacity.forceActiveFocus()
        }
        NovaButton {
            id: menuOpacity
            objectName: "appearance-menu-opacity"
            unit: appearance.unit
            Layout.fillWidth: true
            text: "Menu Opacity: " + NovaStreamPreferences.menuOpacityPercent + "%"
            onClicked: opacityEditor.open()
            Keys.onUpPressed: commandCenterButton.forceActiveFocus()
            Keys.onDownPressed: shortcutHint.forceActiveFocus()
        }
        NovaButton {
            id: shortcutHint
            objectName: "appearance-shortcut-hint"
            unit: appearance.unit
            Layout.fillWidth: true
            text: "Controller Shortcut Hint: " + (NovaStreamPreferences.shortcutHint ? "On" : "Off")
            Accessible.checkable: true
            Accessible.checked: NovaStreamPreferences.shortcutHint
            onClicked: NovaStreamPreferences.setShortcutHint(!NovaStreamPreferences.shortcutHint)
            Keys.onUpPressed: menuOpacity.forceActiveFocus()
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
    TextSizeSettings { id: textEditor; unit: appearance.unit }
    MenuOpacitySettings { id: opacityEditor; unit: appearance.unit }
}

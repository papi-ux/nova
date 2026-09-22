import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: sheet
    objectName: "game-shortcut-sheet"
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(720, parent.width - 48)
    height: Math.min(implicitHeight, parent.height - 48)
    padding: 28
    modal: true; focus: true
    property var controller: null
    property var game: ({})
    readonly property var shortcutState: controller ? controller.state : ({})
    onOpened: add.forceActiveFocus()
    background: Rectangle { color: NovaTheme.panel; radius: 16; border.color: NovaTheme.divider }
    contentItem: NovaScrollColumn {
        spacing: 18
        Label { text: "Add to Steam"; color: NovaTheme.text; font.pixelSize: 28 * NovaTheme.fontScale; font.bold: true }
        Label { text: game.title || ""; color: NovaTheme.text; textFormat: Text.PlainText; wrapMode: Text.WordWrap; Layout.fillWidth: true; font.pixelSize: 20 * NovaTheme.fontScale }
        Label {
            Layout.fillWidth: true; color: NovaTheme.secondary; textFormat: Text.PlainText; wrapMode: Text.WordWrap
            font.pixelSize: 17 * NovaTheme.fontScale
            text: "Launch this game directly from Steam using its saved PC, destination and Nova settings. Available poster, backdrop, logo and icon artwork are copied to Steam.\n\nSteam must be closed while the entry is saved. In Desktop Mode, leave Nova open, close Steam, then return here and choose Retry."
        }
        Label { Layout.fillWidth: true; text: shortcutState.copy || ""; textFormat: Text.PlainText; color: NovaTheme.secondary; wrapMode: Text.WordWrap; font.pixelSize: 17 * NovaTheme.fontScale }
        NovaButton {
            id: add; objectName: "game-shortcut-add"; Layout.fillWidth: true
            text: shortcutState.busy ? "Preparing…" : shortcutState.retry ? "Retry" : shortcutState.ok ? "Update Steam entry" : "Add to Steam"
            enabled: !!controller && !shortcutState.busy
            onClicked: controller.add(game.id)
            Keys.onDownPressed: back.forceActiveFocus()
        }
        NovaButton { id: back; text: "Back"; Layout.fillWidth: true; onClicked: sheet.close(); Keys.onUpPressed: add.forceActiveFocus() }
    }
}

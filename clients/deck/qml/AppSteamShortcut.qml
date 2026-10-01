import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: sheet
    objectName: "app-steam-popup"
    required property var controller
    property real unit: 1
    readonly property var status: controller ? controller.state : ({})
    anchors.centerIn: Overlay.overlay
    width: Math.min(640 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 24 * unit
    modal: true; focus: true
    closePolicy: Popup.CloseOnEscape
    function observation() { return { opened: opened, status: status } }
    onOpened: Qt.callLater(() => status.canAdd ? add.forceActiveFocus() : done.forceActiveFocus())
    background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
    contentItem: NovaScrollColumn {
        spacing: 16 * sheet.unit
        Label { Layout.fillWidth: true; text: "Add Nova to Steam"; wrapMode: Text.WordWrap; color: NovaTheme.text; font.pixelSize: 28 * sheet.unit * NovaTheme.fontScale; font.bold: true }
        Label { Layout.fillWidth: true; text: "Create a Non-Steam game for this installed Nova app. This lets Steam launch Nova and keeps pairing and streaming inside Nova. It does not install an app or start Steam."; textFormat: Text.PlainText; wrapMode: Text.WordWrap; color: NovaTheme.secondary; font.pixelSize: 18 * sheet.unit * NovaTheme.fontScale }
        Label { objectName: "app-steam-status"; Layout.fillWidth: true; text: sheet.status.copy || ""; textFormat: Text.PlainText; wrapMode: Text.WordWrap; color: NovaTheme.secondary; font.pixelSize: 18 * sheet.unit * NovaTheme.fontScale }
        NovaButton {
            id: add; objectName: "app-steam-add"; Layout.fillWidth: true; unit: sheet.unit
            text: sheet.status.busy ? "Adding…" : sheet.status.retry ? "Retry" : sheet.status.ok ? "Update Steam entry" : "Add Nova to Steam"
            enabled: sheet.status.canAdd === true
            onClicked: sheet.controller.add()
            Keys.onDownPressed: done.forceActiveFocus()
        }
        NovaButton { id: done; objectName: "app-steam-back"; Layout.fillWidth: true; unit: sheet.unit; text: "Back"; onClicked: sheet.close(); Keys.onUpPressed: if (add.enabled) add.forceActiveFocus() }
    }
}

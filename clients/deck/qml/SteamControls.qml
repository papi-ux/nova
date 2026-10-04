import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: steam
    objectName: "steam-controls"
    property var controller: null
    property real unit: 1
    readonly property var status: controller ? controller.state : ({})
    property bool confirmQuit: false
    anchors.centerIn: Overlay.overlay
    width: Math.min(660 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 24 * unit
    modal: true; focus: true; closePolicy: Popup.CloseOnEscape
    onOpened: { confirmQuit = false; if (controller) controller.refresh(); done.forceActiveFocus() }
    onClosed: { confirmQuit = false; if (controller) controller.cancel() }
    background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
    contentItem: NovaScrollColumn {
        spacing: 12 * steam.unit
        Label { text: "Steam · " + (steam.status.hostName || "Selected PC"); color: NovaTheme.text; font.pixelSize: 26 * steam.unit * NovaTheme.fontScale; wrapMode: Text.WordWrap; Layout.fillWidth: true; textFormat: Text.PlainText }
        Label { font.pixelSize: 18 * steam.unit * NovaTheme.fontScale; text: steam.status.message || "Refresh Steam status."; color: NovaTheme.secondary; wrapMode: Text.WordWrap; Layout.fillWidth: true; textFormat: Text.PlainText }
        Label {
            visible: steam.status.available === true
            text: steam.status.effectiveKeepRunning ? "After End Session: keep desktop Steam running." : "After End Session: close managed desktop Steam."
            font.pixelSize: 18 * steam.unit * NovaTheme.fontScale
            color: NovaTheme.secondary; wrapMode: Text.WordWrap; Layout.fillWidth: true
        }
        NovaButton { id: refresh; objectName: "steam-refresh"; text: "Refresh"; unit: steam.unit; Layout.fillWidth: true; enabled: !steam.status.busy; onClicked: { steam.confirmQuit = false; steam.controller.refresh() }
            Keys.onDownPressed: open.forceActiveFocus() }
        NovaButton { id: open; objectName: "steam-open"; text: "Open Steam"; unit: steam.unit; Layout.fillWidth: true; enabled: steam.status.available === true && !steam.status.busy; onClicked: steam.controller.request("open"); Keys.onUpPressed: refresh.forceActiveFocus(); Keys.onDownPressed: bigPicture.forceActiveFocus() }
        NovaButton { id: bigPicture; objectName: "steam-big-picture"; text: "Open Big Picture"; unit: steam.unit; Layout.fillWidth: true; enabled: open.enabled; onClicked: steam.controller.request("big_picture"); Keys.onUpPressed: open.forceActiveFocus(); Keys.onDownPressed: quit.forceActiveFocus() }
        NovaButton { id: quit; objectName: "steam-quit"; text: "Quit Steam"; unit: steam.unit; Layout.fillWidth: true; enabled: open.enabled && steam.status.state === "running"; onClicked: { if (steam.status.gameActive) { steam.confirmQuit = true; keep.forceActiveFocus() } else steam.controller.request("quit") }
            Keys.onUpPressed: bigPicture.forceActiveFocus(); Keys.onDownPressed: done.forceActiveFocus() }
        Label { visible: steam.confirmQuit; text: "A game is running. Quitting Steam may close it."; color: NovaTheme.warning; wrapMode: Text.WordWrap; Layout.fillWidth: true }
        NovaButton { id: keep; objectName: "steam-keep"; visible: steam.confirmQuit; text: "Keep Steam running"; unit: steam.unit; Layout.fillWidth: true; onClicked: { steam.confirmQuit = false; quit.forceActiveFocus() }
            Keys.onDownPressed: confirm.forceActiveFocus() }
        NovaButton { id: confirm; objectName: "steam-confirm-quit"; visible: steam.confirmQuit; enabled: quit.enabled; text: "Confirm Quit Steam"; destructive: true; unit: steam.unit; Layout.fillWidth: true; onClicked: { steam.confirmQuit = false; steam.controller.request("quit", true); done.forceActiveFocus() }
            Keys.onUpPressed: keep.forceActiveFocus(); Keys.onDownPressed: done.forceActiveFocus() }
        NovaButton { id: done; objectName: "steam-close"; text: "Close"; unit: steam.unit; Layout.fillWidth: true; onClicked: steam.close(); Keys.onUpPressed: { if (steam.confirmQuit) keep.forceActiveFocus(); else refresh.forceActiveFocus() } }
    }
}

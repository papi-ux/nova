import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: wake
    objectName: "host-wake-popup"
    required property var controller
    property real unit: 1
    readonly property var status: controller ? controller.state : ({})
    readonly property bool dirty: mac.text.trim() !== (status.mac || "")
    property string openedHost: ""
    anchors.centerIn: Overlay.overlay
    width: Math.min(660 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 24 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    function back() { if (keyboard.opened) keyboard.close(); else close() }
    function observation() { return { opened: opened, keyboardOpen: keyboard.opened, draft: mac.text, dirty: dirty, status: status } }
    onOpened: { openedHost = status.hostId || ""; mac.text = status.mac || ""; Qt.callLater(() => status.canWake ? send.forceActiveFocus() : mac.forceActiveFocus()) }
    onClosed: keyboard.close()
    background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
    EndpointKeyboard { id: keyboard; parent: Overlay.overlay }
    Connections {
        target: wake.controller
        function onStateChanged() { if (wake.opened && wake.openedHost !== (wake.status.hostId || "")) wake.close() }
    }
    contentItem: NovaScrollColumn {
        spacing: 14 * wake.unit
        Label { text: "Wake PC"; color: NovaTheme.text; font.pixelSize: 28 * wake.unit * NovaTheme.fontScale; font.bold: true }
        Label {
            Layout.fillWidth: true
            text: wake.status.hostName || "Selected PC"
            textFormat: Text.PlainText; wrapMode: Text.WordWrap
            color: NovaTheme.text; font.pixelSize: 22 * wake.unit * NovaTheme.fontScale
        }
        Label {
            Layout.fillWidth: true
            text: "Wake-on-LAN needs the PC's wired adapter MAC, Wake-on-LAN enabled on the PC, and a local network that allows Wake packets. It may not work over Wi-Fi or outside your home network."
            textFormat: Text.PlainText; wrapMode: Text.WordWrap
            color: NovaTheme.secondary; font.pixelSize: 17 * wake.unit * NovaTheme.fontScale
        }
        Label { text: "Wired MAC address"; color: NovaTheme.text; font.pixelSize: 18 * wake.unit * NovaTheme.fontScale }
        TextField {
            id: mac
            objectName: "host-wake-mac"
            Layout.fillWidth: true
            Layout.preferredHeight: Math.max(52, 54 * wake.unit * NovaTheme.fontScale)
            maximumLength: 17; placeholderText: "02:11:22:33:44:55"
            Accessible.name: "Wired MAC address"
            inputMethodHints: Qt.ImhNoPredictiveText | Qt.ImhNoAutoUppercase
            color: NovaTheme.text; font.pixelSize: 21 * wake.unit * NovaTheme.fontScale
            selectByMouse: true
            enabled: wake.status.canSave === true
            background: Rectangle { color: NovaTheme.input; radius: 8; border.color: mac.activeFocus ? NovaTheme.focus : NovaTheme.divider; border.width: mac.activeFocus ? 3 : 1 }
            function edit() { keyboard.edit(mac, false, "Wired MAC address", false, true) }
            TapHandler { onTapped: mac.edit() }
            Keys.onReturnPressed: edit()
            Keys.onEnterPressed: edit()
            Keys.onDownPressed: save.forceActiveFocus()
        }
        NovaButton {
            id: save; objectName: "host-wake-save"
            Layout.fillWidth: true; unit: wake.unit
            text: mac.text.trim().length ? "Save MAC address" : "Clear MAC address"
            enabled: wake.status.canSave === true
            onClicked: if (wake.controller.saveMac(mac.text)) { mac.text = wake.status.mac || ""; if (wake.status.canWake) send.forceActiveFocus(); else done.forceActiveFocus() }
            Keys.onUpPressed: mac.forceActiveFocus()
            Keys.onDownPressed: if (send.enabled) send.forceActiveFocus(); else done.forceActiveFocus()
        }
        Label {
            objectName: "host-wake-status"
            Layout.fillWidth: true
            text: (wake.status.copy || "") + (wake.dirty ? "\nSave the changed MAC address before sending a Wake packet." : "")
            textFormat: Text.PlainText; wrapMode: Text.WordWrap
            color: NovaTheme.secondary; font.pixelSize: 17 * wake.unit * NovaTheme.fontScale
        }
        NovaButton {
            id: send; objectName: "host-wake-send"
            Layout.fillWidth: true; unit: wake.unit
            text: "Wake PC"
            enabled: wake.status.canWake === true && !wake.dirty
            onClicked: wake.controller.wake()
            Keys.onUpPressed: save.forceActiveFocus()
            Keys.onDownPressed: done.forceActiveFocus()
        }
        NovaButton {
            id: done; objectName: "host-wake-back"
            Layout.fillWidth: true; unit: wake.unit
            text: "Back"
            onClicked: wake.back()
            Keys.onUpPressed: if (send.enabled) send.forceActiveFocus(); else save.forceActiveFocus()
        }
    }
}

import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: power
    objectName: "host-power-popup"
    required property var controller
    property var gamepad: null
    property real unit: 1
    readonly property var status: controller ? controller.state : ({})
    property string previousPhase: "idle"
    readonly property bool sending: status.phase === "requesting" || status.phase === "confirming"
    anchors.centerIn: Overlay.overlay
    width: Math.min(660 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 24 * unit
    modal: true
    focus: true
    closePolicy: sending ? Popup.NoAutoClose : Popup.CloseOnEscape
    function interactionState() {
        function center(item) {
            const point = item.mapToItem(null, item.width / 2, item.height / 2)
            return { x: Math.round(point.x), y: Math.round(point.y) }
        }
        return { hold: center(hold), cancel: center(done) }
    }
    function requestClose() { if (!sending) { controller.cancel(); close() } }
    onOpened: { controller.refresh(); done.forceActiveFocus() }
    onClosed: controller.cancel()
    background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
    Connections {
        target: power.controller
        function onStateChanged() {
            const previous = power.previousPhase
            power.previousPhase = power.status.phase
            if (!power.opened) return
            if (power.status.phase === "ready" && previous === "checking") hold.forceActiveFocus()
            else if (power.status.phase !== "holding" && power.status.phase !== "ready") done.forceActiveFocus()
        }
    }
    Connections {
        target: power.gamepad
        ignoreUnknownSignals: true
        function onPrimaryHeldChanged() { if (!power.gamepad.primaryHeld) power.controller.releaseHold() }
    }
    contentItem: NovaScrollColumn {
        spacing: 16 * power.unit
        Label { text: "Sleep Host"; color: NovaTheme.text; font.pixelSize: 28 * power.unit * NovaTheme.fontScale; font.bold: true }
        Label {
            Layout.fillWidth: true
            text: power.status.hostName || "Selected PC"
            textFormat: Text.PlainText
            wrapMode: Text.WordWrap
            color: NovaTheme.text
            font.pixelSize: 23 * power.unit * NovaTheme.fontScale
        }
        Label {
            Layout.fillWidth: true
            text: "This puts the PC to sleep for everyone using it. Make sure you can wake it again."
            textFormat: Text.PlainText
            wrapMode: Text.WordWrap
            color: NovaTheme.secondary
            font.pixelSize: 18 * power.unit * NovaTheme.fontScale
        }
        Label {
            objectName: "host-power-status"
            Layout.fillWidth: true
            text: power.status.copy || ""
            textFormat: Text.PlainText
            wrapMode: Text.WordWrap
            color: NovaTheme.secondary
            font.pixelSize: 18 * power.unit * NovaTheme.fontScale
            Accessible.role: Accessible.StaticText
        }
        Label {
            objectName: "host-power-countdown"
            Layout.fillWidth: true
            visible: power.status.phase === "countdown"
            text: "Sleeping in " + power.status.remaining + "…"
            color: NovaTheme.warning
            font.pixelSize: 32 * power.unit * NovaTheme.fontScale
            font.bold: true
        }
        NovaButton {
            id: hold
            objectName: "host-power-hold"
            Layout.fillWidth: true
            unit: power.unit
            text: power.status.phase === "holding" ? "Keep holding…" : "Hold to sleep"
            destructive: true
            visible: power.status.phase === "ready" || power.status.phase === "holding"
            enabled: power.status.canHold === true
            onPressed: power.controller.beginHold()
            onReleased: power.controller.releaseHold()
            onCanceled: power.controller.releaseHold()
            onActiveFocusChanged: if (!activeFocus) power.controller.releaseHold()
            Keys.onReturnPressed: event => { if (!event.isAutoRepeat) power.controller.beginHold() }
            Keys.onEnterPressed: event => { if (!event.isAutoRepeat) power.controller.beginHold() }
            Keys.onReleased: event => {
                if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                    // The navigation bridge emits a tap to activate controls;
                    // the physical A release is observed separately below.
                    if (!event.isAutoRepeat && (!power.gamepad || power.gamepad.primaryHeld !== true)) power.controller.releaseHold()
                    event.accepted = true
                }
            }
            Keys.onDownPressed: done.forceActiveFocus()
            Rectangle {
                anchors.left: parent.left; anchors.bottom: parent.bottom; anchors.bottomMargin: 3
                width: parent.width * (power.status.progress || 0); height: 5
                color: hold.activeFocus ? NovaTheme.focusText : NovaTheme.accent
                radius: 2
            }
        }
        NovaButton {
            id: check
            objectName: "host-power-refresh"
            Layout.fillWidth: true
            unit: power.unit
            visible: !power.status.busy && !hold.visible
            text: "Check again"
            onClicked: power.controller.refresh()
            Keys.onDownPressed: done.forceActiveFocus()
        }
        NovaButton {
            id: done
            objectName: "host-power-cancel"
            Layout.fillWidth: true
            unit: power.unit
            text: power.sending ? "Waiting for PC…" : power.status.phase === "countdown" || power.status.phase === "checking" ? "Cancel" : "Back"
            enabled: !power.sending
            onClicked: power.requestClose()
            Keys.onUpPressed: { if (hold.visible) hold.forceActiveFocus(); else if (check.visible) check.forceActiveFocus() }
        }
    }
}

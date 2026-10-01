import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: sheet
    objectName: "moonlight-handoff-review"
    required property var controller
    property string hostId: ""
    property string gameId: ""
    property string gameTitle: ""
    property string reviewedHost: ""
    property string reviewedGame: ""
    property string reviewedTitle: ""
    readonly property var status: controller ? controller.state : ({})
    readonly property bool current: hostId === reviewedHost && gameId === reviewedGame && gameTitle === reviewedTitle
    readonly property string limits: "Moonlight runs this stream in its own app, with its own controls, decoder and codec options.\n\nNovaHUD, Command Center, Doctor, Live Tuning and Live Bitrate are unavailable during this handoff. Nova's PyroWave stream path is unavailable here.\n\nUse Nova's built-in player to keep these features."
    anchors.centerIn: Overlay.overlay
    width: Math.min(700, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 24
    modal: true; focus: true
    closePolicy: Popup.CloseOnEscape
    function review() {
        if (opened || !controller || !status.available || status.running || !hostId || !gameId || !gameTitle) return false
        reviewedHost = hostId; reviewedGame = gameId; reviewedTitle = gameTitle
        controller.activate(reviewedHost, reviewedGame, reviewedTitle)
        if (!status.armed) return false
        open()
        return true
    }
    function observation() { return { opened: opened, limits: limits, current: current, status: status } }
    onOpened: Qt.callLater(() => proceed.enabled ? proceed.forceActiveFocus() : done.forceActiveFocus())
    onClosed: if (controller && status.armed) controller.cancel()
    onHostIdChanged: if (opened && hostId !== reviewedHost) close()
    onGameIdChanged: if (opened && gameId !== reviewedGame) close()
    onGameTitleChanged: if (opened && gameTitle !== reviewedTitle) close()
    background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
    Connections {
        target: sheet.controller
        function onStateChanged() {
            if (!sheet.opened) return
            if (!sheet.status.available || sheet.status.running) sheet.close()
            else if (!sheet.status.armed) Qt.callLater(() => done.forceActiveFocus())
        }
    }
    contentItem: NovaScrollColumn {
        spacing: 16
        Label { Layout.fillWidth: true; text: "Open in Moonlight?"; wrapMode: Text.WordWrap; color: NovaTheme.text; font.pixelSize: 28 * NovaTheme.fontScale; font.bold: true }
        Label { Layout.fillWidth: true; text: sheet.reviewedTitle; textFormat: Text.PlainText; wrapMode: Text.WordWrap; color: NovaTheme.text; font.pixelSize: 21 * NovaTheme.fontScale; font.bold: true }
        Label { objectName: "moonlight-review-limits"; Layout.fillWidth: true; text: sheet.limits; textFormat: Text.PlainText; wrapMode: Text.WordWrap; color: NovaTheme.secondary; font.pixelSize: 18 * NovaTheme.fontScale }
        Label { objectName: "moonlight-review-expired"; Layout.fillWidth: true; visible: !sheet.status.armed; text: "This review expired. Go back and choose Play in Moonlight to review again."; wrapMode: Text.WordWrap; color: NovaTheme.warning; font.pixelSize: 18 * NovaTheme.fontScale }
        NovaButton {
            id: proceed; objectName: "moonlight-review-continue"; Layout.fillWidth: true
            text: "Open in Moonlight"
            enabled: sheet.status.available === true && sheet.status.armed === true && !sheet.status.running && sheet.current
            onClicked: if (enabled) { sheet.controller.activate(sheet.reviewedHost, sheet.reviewedGame, sheet.reviewedTitle); if (!sheet.status.armed) sheet.close() }
            Keys.onDownPressed: done.forceActiveFocus()
        }
        NovaButton { id: done; objectName: "moonlight-review-back"; Layout.fillWidth: true; text: "Back"; onClicked: sheet.close(); Keys.onUpPressed: if (proceed.enabled) proceed.forceActiveFocus() }
    }
}

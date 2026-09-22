import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: sheet
    objectName: "space-artwork"
    property var controller: null
    property var settingsProvider: null
    property string hostId: ""
    property var game: ({})
    readonly property var status: controller ? controller.state : ({})
    readonly property real unit: Math.max(0.85, Math.min(1.15, width / 1280))
    property string kind: "hero"
    property var placement: ({scale: 1, x: 0.5, y: 0.5})
    property bool wasBusy: false
    property bool returnAfterWork: false
    parent: Overlay.overlay
    width: parent ? parent.width : 1280
    height: parent ? parent.height : 800
    padding: 28 * unit
    modal: true; focus: true; closePolicy: Popup.CloseOnEscape
    onHostIdChanged: if (opened) close()
    onGameChanged: {
        if (opened && status.game && game.id !== status.game) close()
        reloadPlacement()
    }
    function reloadPlacement() {
        if (settingsProvider) placement = settingsProvider.logoPlacement(hostId, game.id || "", game.logoTransform || {})
    }
    function focusAction() {
        if (status.uncertain && check.enabled) check.forceActiveFocus()
        else if (refresh.enabled) refresh.forceActiveFocus()
        else back.forceActiveFocus()
    }
    function focusKind(index) { kinds.itemAt(Math.max(0, Math.min(3, index))).forceActiveFocus() }
    function leave() { if (logoEditor.opened) logoEditor.close(); else close() }
    function state() {
        function center(item) { const p = item.mapToItem(null, item.width/2, item.height/2); return {x:Math.round(p.x), y:Math.round(p.y)} }
        return {opened: opened, kind: kind, status: status, logoEditorOpen: logoEditor.opened, logoPlacement: placement,
            controls: {refresh: center(refresh), check: center(check), layout: center(layout)}}
    }
    onAboutToShow: {
        kind = "hero"; returnAfterWork = false; reloadPlacement()
        if (controller) controller.prepare(hostId, game.id || "", settingsProvider.load(hostId, game.id).configuration)
    }
    onOpened: focusKind(1)
    onClosed: { logoEditor.close(); if (controller) controller.close() }
    Connections { target: sheet.settingsProvider; function onLogoPlacementChanged() { sheet.reloadPlacement() } }
    Connections {
        target: sheet.controller
        function onStateChanged() {
            const busy = sheet.status.busy
            if (sheet.wasBusy && !busy && sheet.returnAfterWork && sheet.opened && !logoEditor.opened) {
                sheet.returnAfterWork = false
                Qt.callLater(function() { if (sheet.opened && !sheet.status.busy) sheet.focusAction() })
            }
            sheet.wasBusy = busy
        }
    }
    background: Rectangle { color: NovaTheme.window }
    component Copy: Label {
        Layout.fillWidth: true; textFormat: Text.PlainText; wrapMode: Text.WordWrap
        color: NovaTheme.text; font.pixelSize: 17 * sheet.unit * NovaTheme.fontScale
    }
    component Action: NovaButton { unit: sheet.unit; Keys.onEscapePressed: sheet.leave() }
    LogoPlacement {
        id: logoEditor; settingsProvider: sheet.settingsProvider; hostId: sheet.hostId; gameId: sheet.game.id || ""
        logoSource: sheet.game.logo || ""; heroSource: sheet.game.hero || ""; fallback: sheet.game.logoTransform || ({})
        returnFocus: layout
    }
    contentItem: ColumnLayout {
        spacing: 14 * sheet.unit
        RowLayout {
            Layout.fillWidth: true
            Copy { text: "Space artwork"; font.bold: true; font.pixelSize: 30 * sheet.unit * NovaTheme.fontScale }
            Action { id: back; objectName: "space-artwork-back"; text: "Back"; onClicked: sheet.close()
                Keys.onDownPressed: sheet.focusKind(0); Keys.onLeftPressed: sheet.focusAction() }
        }
        Copy { text: (sheet.game.title || "Game") + " · " + (sheet.game.spaceName || "Selected Space"); color: NovaTheme.secondary }
        RowLayout {
            Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 0; spacing: 24 * sheet.unit
            Item {
                Layout.fillWidth: true; Layout.fillHeight: true; Layout.preferredWidth: 1.5; clip: true
                Rectangle { anchors.fill: parent; radius: 12; color: NovaTheme.panel }
                Image { anchors.fill: parent; source: sheet.kind === "logo" ? sheet.game.hero || "" : ""; fillMode: Image.PreserveAspectCrop; opacity: 0.4; asynchronous: true }
                Image {
                    id: current; objectName: "space-artwork-image"; anchors.fill: parent; anchors.margins: 12 * sheet.unit
                    source: sheet.kind === "logo" ? "" : sheet.game[sheet.kind] || ""; fillMode: Image.PreserveAspectFit; asynchronous: true
                    visible: sheet.kind !== "logo"
                }
                LogoArtwork { id: logo; anchors.fill: parent; source: sheet.game.logo || ""; placement: sheet.placement; visible: sheet.kind === "logo"; description: sheet.game.title || "Game logo" }
                Copy {
                    anchors.centerIn: parent; width: parent.width - 32; horizontalAlignment: Text.AlignHCenter
                    readonly property int imageStatus: sheet.kind === "logo" ? logo.status : current.status
                    visible: imageStatus !== Image.Ready
                    text: imageStatus === Image.Loading ? "Loading artwork…" : "No " + (sheet.kind === "hero" ? "backdrop" : sheet.kind) + " available yet. Refresh art checks for missing images."
                }
            }
            NovaScrollColumn {
                Layout.fillWidth: true; Layout.fillHeight: true; Layout.preferredWidth: 1; Layout.minimumHeight: 0
                spacing: 10 * sheet.unit; reserveScrollBarSpace: true
                Repeater {
                    id: kinds
                    model: [{id:"poster",label:"Poster"},{id:"hero",label:"Backdrop"},{id:"logo",label:"Logo"},{id:"icon",label:"Icon"}]
                    Action {
                        required property var modelData; required property int index
                        objectName: "space-artwork-kind-" + modelData.id; Layout.fillWidth: true
                        text: modelData.label + (sheet.kind === modelData.id ? " · Selected" : "")
                        onClicked: sheet.kind = modelData.id
                        Keys.onUpPressed: index ? sheet.focusKind(index - 1) : back.forceActiveFocus()
                        Keys.onDownPressed: index < 3 ? sheet.focusKind(index + 1) : sheet.focusAction()
                        Keys.onLeftPressed: sheet.focusAction(); Keys.onRightPressed: if (layout.enabled) layout.forceActiveFocus()
                    }
                }
                Copy { text: "Refresh fetches missing artwork from Steam and keeps existing images. Manual image selection is not available for Spaces on this PC."; color: NovaTheme.secondary }
            }
        }
        Copy { objectName: "space-artwork-status"; text: sheet.status.copy || "Checking Space artwork…"; color: sheet.status.uncertain ? NovaTheme.warning : NovaTheme.secondary }
        RowLayout {
            Layout.fillWidth: true
            Action { id: refresh; objectName: "space-artwork-refresh"; text: sheet.status.busy ? "Working…" : "Refresh art"; primary: true
                enabled: sheet.status.canRefreshArtwork || false
                onClicked: { sheet.returnAfterWork = true; back.forceActiveFocus(); sheet.controller.refreshArtwork() }
                Keys.onUpPressed: sheet.focusKind(3); Keys.onRightPressed: check.forceActiveFocus() }
            Action { id: check; objectName: "space-artwork-check"; text: "Check again"; enabled: sheet.status.canCheckArtwork || false
                onClicked: { sheet.returnAfterWork = true; back.forceActiveFocus(); sheet.controller.checkArtwork() }
                Keys.onUpPressed: sheet.focusKind(3); Keys.onLeftPressed: refresh.enabled ? refresh.forceActiveFocus() : back.forceActiveFocus()
                Keys.onRightPressed: layout.enabled ? layout.forceActiveFocus() : back.forceActiveFocus() }
            Item { Layout.fillWidth: true }
            Action { id: layout; objectName: "space-artwork-layout"; text: "Logo placement"; enabled: logo.status === Image.Ready && !sheet.status.busy
                onClicked: logoEditor.open(); Keys.onUpPressed: sheet.focusKind(2); Keys.onLeftPressed: check.enabled ? check.forceActiveFocus() : back.forceActiveFocus(); Keys.onRightPressed: back.forceActiveFocus() }
        }
    }
}

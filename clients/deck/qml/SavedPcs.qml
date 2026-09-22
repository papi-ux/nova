import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

ApplicationWindow {
    id: root
    width: 1280
    height: 800
    visible: true
    title: "Nova — Saved PCs"
    color: NovaTheme.window
    readonly property var model: novaPairing.state
    readonly property bool busy: model.busy
    property bool openLibrary: false
    property bool closeAfterCancel: false
    property var selected: null
    property bool confirming: false

    function selectedHost() { return hosts.currentIndex >= 0 ? novaPairing.savedHosts[hosts.currentIndex] : null }
    function focusList() { if (hosts.count) hosts.forceActiveFocus(); else add.forceActiveFocus() }
    function finish() { openLibrary = novaPairing.savedHosts.length > 0; root.close() }
    function back() {
        if (pairLoader.active) return
        if (root.busy) novaPairing.cancel()
        else if (confirming) { confirming = false; focusList() }
        else finish()
    }
    function choose() {
        selected = selectedHost()
        if (selected) { confirming = true; Qt.callLater(function() { keep.forceActiveFocus() }) }
    }
    onClosing: (event) => {
        if (pairLoader.active) { event.accepted = false; pairLoader.item.close(); return }
        if (busy) { event.accepted = false; closeAfterCancel = true; novaPairing.cancel() }
    }
    Connections {
        target: novaPairing
        function onStateChanged() {
            if (root.closeAfterCancel && !root.busy) { root.close(); return }
            if (pairLoader.active) return
            Qt.callLater(function() {
                if (root.busy) cancel.forceActiveFocus()
                else { root.confirming = false; root.focusList() }
            })
        }
    }
    Connections {
        target: novaGamepad
        function onPrimaryActionPressed(count) { if (!pairLoader.active) novaGamepad.activateFocusedItem() }
        function onSecondaryActionPressed(count) { root.back() }
    }
    Loader {
        id: pairLoader
        active: false
        source: "PairHost.qml"
        onLoaded: { item.transientParent = root; item.modality = Qt.ApplicationModal }
    }
    Connections {
        target: pairLoader.item
        function onVisibleChanged() {
            if (pairLoader.item && !pairLoader.item.visible) Qt.callLater(function() {
                pairLoader.active = false
                root.requestActivate()
                root.focusList()
            })
        }
    }

    component Action: NovaButton {
        id: action
        Layout.preferredHeight: 58
        Layout.fillWidth: true
        font.pixelSize: 20 * NovaTheme.fontScale
        font.bold: true
        Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat && enabled) clicked() }
        Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat && enabled) clicked() }
        contentItem: Text {
            text: action.text; font: action.font; color: action.activeFocus ? NovaTheme.window : "white"
            horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
        }
        background: Rectangle {
            radius: 12; color: action.activeFocus ? NovaTheme.focus : NovaTheme.raised
            border.color: action.activeFocus ? NovaTheme.focus : NovaTheme.divider
            border.width: action.activeFocus ? 4 : 1
            opacity: action.enabled ? 1 : 0.45
        }
    }

    NovaScrollColumn {
        anchors.centerIn: parent
        width: Math.min(880, root.width - 64)
        height: Math.min(implicitHeight, root.height - 64)
        spacing: 20
        enabled: !pairLoader.active
        Label { text: "Saved PCs"; font.pixelSize: 36 * NovaTheme.fontScale; font.bold: true; color: NovaTheme.text }
        Label {
            Layout.fillWidth: true
            text: "Add a PC, manage its pairing, or return to your games."
            font.pixelSize: 21 * NovaTheme.fontScale; color: NovaTheme.secondary; wrapMode: Text.WordWrap
        }
        ListView {
            id: hosts
            objectName: "saved-pcs-list"
            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(root.confirming ? 84 : 248, Math.max(84, count * 80))
            clip: true
            spacing: 8
            model: novaPairing.savedHosts
            enabled: !root.busy && !root.confirming
            keyNavigationEnabled: false
            boundsBehavior: Flickable.StopAtBounds
            delegate: Rectangle {
                required property var modelData
                required property int index
                width: hosts.width; height: 72; radius: 12
                color: NovaTheme.panel
                border.color: hosts.activeFocus && hosts.currentIndex === index ? NovaTheme.focus : NovaTheme.divider
                border.width: hosts.activeFocus && hosts.currentIndex === index ? 4 : 1
                Label {
                    anchors.fill: parent; anchors.margins: 18
                    text: modelData.name; elide: Text.ElideRight; color: NovaTheme.text; font.pixelSize: 23 * NovaTheme.fontScale
                    verticalAlignment: Text.AlignVCenter
                }
                MouseArea { anchors.fill: parent; onClicked: { hosts.currentIndex = index; root.choose() } }
            }
            Label { anchors.centerIn: parent; visible: hosts.count === 0; text: "No saved PCs yet"; color: NovaTheme.secondary; font.pixelSize: 22 * NovaTheme.fontScale }
            Keys.onUpPressed: { if (currentIndex > 0) --currentIndex }
            Keys.onDownPressed: { if (currentIndex < count - 1) ++currentIndex; else add.forceActiveFocus() }
            Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) root.choose() }
            Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) root.choose() }
        }
        Rectangle {
            visible: root.confirming
            Layout.fillWidth: true
            implicitHeight: confirmation.implicitHeight + 40
            color: NovaTheme.panel; radius: 14
            ColumnLayout {
                id: confirmation
                anchors.fill: parent; anchors.margins: 20; spacing: 16
                Label {
                    Layout.fillWidth: true
                    text: "Manage “" + (root.selected ? root.selected.name : "PC") + "”"
                    color: NovaTheme.text; font.pixelSize: 23 * NovaTheme.fontScale; font.bold: true; wrapMode: Text.WordWrap
                }
                Label {
                    Layout.fillWidth: true
                    text: "Unpair asks this PC to remove Nova's access. Forget only removes the saved PC here; the host may still trust this Deck. You can add the PC again afterward."
                    color: NovaTheme.secondary; font.pixelSize: 19 * NovaTheme.fontScale; wrapMode: Text.WordWrap
                }
                RowLayout {
                    spacing: 12
                    Action {
                        id: unpair; objectName: "saved-pcs-unpair"; text: "Unpair from PC"
                        onClicked: novaPairing.removeHost(root.selected.id, false)
                        Keys.onRightPressed: forget.forceActiveFocus()
                    }
                    Action {
                        id: forget; objectName: "saved-pcs-forget"; text: "Forget on this Deck"
                        onClicked: novaPairing.removeHost(root.selected.id, true)
                        Keys.onLeftPressed: unpair.forceActiveFocus()
                        Keys.onRightPressed: keep.forceActiveFocus()
                    }
                    Action {
                        id: keep; objectName: "saved-pcs-keep"; text: "Keep PC"
                        onClicked: { root.confirming = false; root.focusList() }
                        Keys.onLeftPressed: forget.forceActiveFocus()
                    }
                }
            }
            enabled: !root.busy
        }
        Label {
            objectName: "saved-pcs-status"
            Layout.fillWidth: true
            visible: root.model.phase !== "idle"
            text: root.model.copy; font.pixelSize: 20 * NovaTheme.fontScale; wrapMode: Text.WordWrap
            color: root.model.phase === "failed" ? NovaTheme.danger : NovaTheme.secondary
        }
        RowLayout {
            spacing: 20
            visible: !root.confirming && !root.busy
            Action {
                id: add; objectName: "saved-pcs-add"; text: "Add PC"
                onClicked: { novaPairing.reset(); pairLoader.active = true }
                Keys.onUpPressed: root.focusList()
                Keys.onRightPressed: library.forceActiveFocus()
            }
            Action {
                id: library; objectName: "saved-pcs-library"
                text: hosts.count ? "Back to library" : "Close"
                onClicked: root.finish()
                Keys.onLeftPressed: add.forceActiveFocus()
                Keys.onUpPressed: root.focusList()
            }
        }
        Action {
            id: cancel; objectName: "saved-pcs-cancel"; text: "Cancel"
            visible: root.busy
            onClicked: novaPairing.cancel()
        }
        Keys.onEscapePressed: root.back()
        Component.onCompleted: Qt.callLater(root.focusList)
    }
}

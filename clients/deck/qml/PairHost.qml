import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Basic as Basic
import QtQuick.Layouts

ApplicationWindow {
    id: root
    objectName: "pair-window"
    width: 1280
    height: 800
    visible: true
    title: "Nova — Pair a PC"
    color: NovaTheme.window
    readonly property var pairingState: novaPairing.state
    readonly property bool busy: pairingState.busy
    property bool openLibrary: false
    property bool closeAfterCancel: false
    property bool lastTrusted: false
    property var discovery: typeof novaDiscovery !== "undefined" ? novaDiscovery : null
    property string discoveredId: ""
    property string discoveryError: ""
    function readyToPair() {
        if (discoveredId.length > 0) {
            const endpoint = discovery ? discovery.endpoint(discoveredId) : ({})
            if (!endpoint.id || endpoint.address !== address.text || endpoint.port !== parseInt(port.text)) {
                discoveryError = "That search result has expired or changed. Find the PC again, or enter its address."
                searchButton.forceActiveFocus(); return false
            }
        }
        if (discovery) discovery.pause()
        discoveryError = ""; return true
    }
    HostSearch {
        id:hostSearch; provider:root.discovery
        onSelected: host => {
            address.text = host.address; port.text = String(host.port)
            root.discoveredId = host.id; root.discoveryError = ""
            Qt.callLater(function() { trustedButton.forceActiveFocus() })
        }
        onClosed: if (!selectedHost) searchButton.forceActiveFocus()
    }
    Component.onDestruction: if (discovery) discovery.stop()

    EndpointKeyboard { id: keyboard; parent: Overlay.overlay }

    onClosing: (event) => {
        if (busy) {
            event.accepted = false
            closeAfterCancel = true
            novaPairing.cancel()
        }
    }
    Connections {
        target: novaPairing
        function onStateChanged() {
            if (root.closeAfterCancel && !root.busy) { root.close(); return }
            Qt.callLater(function() {
                if (root.busy) cancelButton.forceActiveFocus()
                else if (root.lastTrusted && root.pairingState.phase !== "paired") trustedButton.forceActiveFocus()
                else pairButton.forceActiveFocus()
            })
        }
    }
    Connections {
        target: novaGamepad
        function onPrimaryActionPressed(count) { novaGamepad.activateFocusedItem() }
        function onSecondaryActionPressed(count) {
            if (hostSearch.opened) hostSearch.close()
            else if (keyboard.opened) keyboard.close()
            else if (root.busy) novaPairing.cancel()
            else root.close()
        }
    }

    NovaScrollColumn {
        anchors.centerIn: parent
        width: Math.min(760, root.width - 64)
        height: Math.min(implicitHeight, root.height - 64)
        spacing: 20
        Label {
            text: "Pair a PC"
            color: NovaTheme.text
            font.pixelSize: 36 * NovaTheme.fontScale
            font.bold: true
        }
        Label {
            Layout.fillWidth: true
            text: "Find a streaming PC on this network, or enter its name or address. Trusted Pair connects without a PIN when Polaris trusts this network. You can also pair with a PIN."
            color: NovaTheme.secondary
            font.pixelSize: 20 * NovaTheme.fontScale
            wrapMode: Text.WordWrap
        }
        NovaButton {
            id:searchButton; objectName:"pair-search"; Layout.fillWidth:true
            text:"Find PCs on This Network"; enabled:!!root.discovery && !root.busy && pairingState.phase !== "paired"
            onClicked:hostSearch.open()
            Keys.onDownPressed:address.forceActiveFocus()
        }
        Label {
            Layout.fillWidth:true; visible:root.discoveryError.length > 0
            text:root.discoveryError; textFormat:Text.PlainText; color:NovaTheme.warning
            font.pixelSize:18 * NovaTheme.fontScale; wrapMode:Text.WordWrap
        }
        Label { text: "PC Address"; color: NovaTheme.text; font.pixelSize: 18 * NovaTheme.fontScale }
        Basic.TextField {
            id: address
            objectName: "pair-address"
            Layout.fillWidth: true
            Layout.preferredHeight: 58
            placeholderText: "PC name or IP address"
            placeholderTextColor: NovaTheme.secondary
            color: NovaTheme.text
            font.pixelSize: 22 * NovaTheme.fontScale
            selectByMouse: true
            onTextEdited: { root.discoveredId = ""; root.discoveryError = "" }
            onTextChanged: if (root.discoveredId.length > 0) { root.discoveredId = ""; root.discoveryError = "" }
            enabled: !root.busy && pairingState.phase !== "paired"
            inputMethodHints: Qt.ImhNoAutoUppercase | Qt.ImhNoPredictiveText
            background: Rectangle {
                radius: 10; color: NovaTheme.panel
                border.color: address.activeFocus ? NovaTheme.focus : NovaTheme.divider
                border.width: address.activeFocus ? 4 : 1
            }
            TapHandler { onTapped: keyboard.edit(address, false) }
            Keys.onUpPressed: if (searchButton.enabled) searchButton.forceActiveFocus()
            Keys.onReturnPressed: port.forceActiveFocus()
            Keys.onEnterPressed: port.forceActiveFocus()
            Keys.onDownPressed: port.forceActiveFocus()
        }
        RowLayout {
            spacing: 20
            Label { text: "Host HTTP Port"; color: NovaTheme.secondary; font.pixelSize: 18 * NovaTheme.fontScale }
            Basic.TextField {
                id: port
                objectName: "pair-port"
                Layout.preferredWidth: 150
                Layout.preferredHeight: 48
                text: "47989"
                onTextChanged: if (root.discoveredId.length > 0) { root.discoveredId = ""; root.discoveryError = "" }
                color: NovaTheme.text
                font.pixelSize: 20 * NovaTheme.fontScale
                validator: IntValidator { bottom: 1; top: 65535 }
                enabled: address.enabled
                inputMethodHints: Qt.ImhDigitsOnly
                background: Rectangle {
                    radius: 10; color: NovaTheme.panel
                    border.color: port.activeFocus ? NovaTheme.focus : NovaTheme.divider
                    border.width: port.activeFocus ? 4 : 1
                }
                TapHandler { onTapped: keyboard.edit(port, true) }
                Keys.onUpPressed: address.forceActiveFocus()
                Keys.onDownPressed: trustedButton.forceActiveFocus()
                Keys.onReturnPressed: trustedButton.forceActiveFocus()
                Keys.onEnterPressed: trustedButton.forceActiveFocus()
            }
            Label { text: "Keep the default unless your host uses another port."; color: NovaTheme.secondary; font.pixelSize: 15 * NovaTheme.fontScale; Layout.fillWidth: true; wrapMode: Text.WordWrap }
        }
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 118
            visible: root.busy && pairingState.pin.length > 0
            color: NovaTheme.panel
            radius: 16
            Label {
                objectName: "pair-pin"
                anchors.centerIn: parent
                text: pairingState.pin
                color: NovaTheme.text
                font.pixelSize: 68 * NovaTheme.fontScale
                font.bold: true
                font.letterSpacing: 12
            }
        }
        Label {
            objectName: "pair-status"
            Layout.fillWidth: true
            text: pairingState.copy
            color: pairingState.phase === "failed" ? NovaTheme.danger : NovaTheme.secondary
            font.pixelSize: 20 * NovaTheme.fontScale
            wrapMode: Text.WordWrap
        }
        RowLayout {
            spacing: 20
            Button {
                id: trustedButton
                implicitHeight: Math.max(60, contentItem.implicitHeight + 20)
                Layout.minimumHeight: implicitHeight
                topPadding: 10; bottomPadding: 10
                topInset: 0; bottomInset: 0
                objectName: "pair-trusted"
                Layout.fillWidth: true
                Layout.preferredHeight: 60
                visible: pairingState.phase !== "paired"
                enabled: visible && !root.busy
                text: "Trusted Pair"
                function activate() {
                    if (!enabled) return
                    if (!root.readyToPair()) return
                    root.lastTrusted = true
                    novaPairing.startTrusted(address.text, parseInt(port.text))
                }
                onClicked: activate()
                Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) activate() }
                Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) activate() }
                Keys.onUpPressed: port.forceActiveFocus()
                Keys.onRightPressed: pairButton.forceActiveFocus()
                contentItem: Text {
                    text: trustedButton.text; color: trustedButton.activeFocus ? NovaTheme.window : "white"
                    font.pixelSize: 22 * NovaTheme.fontScale; font.bold: true
                    horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                    wrapMode: Text.Wrap
                }
                background: Rectangle {
                    radius: 12; color: trustedButton.activeFocus ? NovaTheme.focus : NovaTheme.raised
                    opacity: trustedButton.enabled ? 1 : 0.5
                    border.color: trustedButton.activeFocus ? NovaTheme.focus : NovaTheme.divider
                    border.width: trustedButton.activeFocus ? 5 : 1
                }
            }
            Button {
                id: pairButton
                implicitHeight: Math.max(60, contentItem.implicitHeight + 20)
                Layout.minimumHeight: implicitHeight
                topPadding: 10; bottomPadding: 10
                topInset: 0; bottomInset: 0
                objectName: "pair-primary"
                Layout.fillWidth: true
                Layout.preferredHeight: 60
                enabled: !root.busy
                text: pairingState.phase === "paired" ? "Open Library"
                    : "Pair with PIN"
                function activate() {
                    if (!enabled) return
                    if (pairingState.phase === "paired") { root.openLibrary = true; root.close() }
                    else if (root.readyToPair()) { root.lastTrusted = false; novaPairing.start(address.text, parseInt(port.text)) }
                }
                onClicked: activate()
                Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) activate() }
                Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) activate() }
                Keys.onUpPressed: port.forceActiveFocus()
                Keys.onLeftPressed: { if (trustedButton.enabled) trustedButton.forceActiveFocus() }
                Keys.onRightPressed: cancelButton.forceActiveFocus()
                contentItem: Text {
                    text: pairButton.text; color: pairButton.activeFocus ? NovaTheme.window : "white"
                    font.pixelSize: 22 * NovaTheme.fontScale; font.bold: true
                    horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                    wrapMode: Text.Wrap
                }
                background: Rectangle {
                    radius: 12; color: pairButton.activeFocus ? NovaTheme.focus : NovaTheme.raised
                    opacity: pairButton.enabled ? 1 : 0.5
                    border.color: pairButton.activeFocus ? NovaTheme.focus : NovaTheme.divider
                    border.width: pairButton.activeFocus ? 5 : 1
                }
            }
            Button {
                id: cancelButton
                implicitHeight: Math.max(60, contentItem.implicitHeight + 20)
                Layout.minimumHeight: implicitHeight
                topPadding: 10; bottomPadding: 10
                topInset: 0; bottomInset: 0
                objectName: "pair-cancel"
                Layout.preferredWidth: 140
                Layout.preferredHeight: 60
                text: root.busy ? "Cancel" : "Close"
                function activate() { if (root.busy) novaPairing.cancel(); else root.close() }
                onClicked: activate()
                Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) activate() }
                Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) activate() }
                Keys.onLeftPressed: { if (pairButton.enabled) pairButton.forceActiveFocus() }
                contentItem: Text {
                    text: cancelButton.text; color: cancelButton.activeFocus ? NovaTheme.window : "white"
                    font.pixelSize: 22 * NovaTheme.fontScale; font.bold: true
                    horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                    wrapMode: Text.Wrap
                }
                background: Rectangle {
                    radius: 12; color: cancelButton.activeFocus ? NovaTheme.focus : NovaTheme.raised
                    border.color: cancelButton.activeFocus ? NovaTheme.focus : NovaTheme.divider
                    border.width: cancelButton.activeFocus ? 5 : 1
                }
            }
        }
        Keys.onEscapePressed: { if (root.busy) novaPairing.cancel(); else root.close() }
        Component.onCompleted: address.forceActiveFocus()
    }
}

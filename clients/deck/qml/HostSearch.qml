import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: search
    objectName: "host-search-popup"
    property var provider: null
    property bool selectedHost: false
    property string focusedId: ""
    readonly property var state: provider ? provider.state : ({busy:false, copy:"Local search is unavailable. Enter the PC address."})
    readonly property var hosts: provider ? provider.hosts : []
    signal selected(var host)
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(800, parent ? parent.width - 40 : 800)
    height: Math.min(660, parent ? parent.height - 40 : 660)
    modal: true; focus: true; padding: 20
    closePolicy: Popup.CloseOnEscape
    background: Rectangle { color:NovaTheme.panel; radius:16; border.color:NovaTheme.divider }
    onOpened: { selectedHost = false; focusedId = ""; if (provider) provider.start(); searchButton.forceActiveFocus() }
    onClosed: { if (provider && !selectedHost) provider.stop() }
    function choose(id) {
        const host = provider ? provider.endpoint(id) : ({})
        if (!host.id) return
        selectedHost = true; provider.pause(); selected(host); close()
    }
    Connections {
        target:search.provider
        function onHostsChanged() {
            if (!search.opened || !search.focusedId) return
            const id = search.focusedId
            Qt.callLater(function() {
                if (!search.opened) return
                const index = search.hosts.findIndex(host => host.id === id)
                if (index >= 0 && results.itemAt(index)) results.itemAt(index).forceActiveFocus()
                else searchButton.forceActiveFocus()
            })
        }
    }
    contentItem: ColumnLayout {
        spacing: 12
        Label { text:"Find a PC"; color:NovaTheme.text; font.bold:true; font.pixelSize:28 * NovaTheme.fontScale }
        Label {
            Layout.fillWidth:true; wrapMode:Text.WordWrap; textFormat:Text.PlainText
            text:"PCs advertising on this local network appear here. Select one, then use Trusted Pair or a PIN. Your host decides whether this network is trusted."
            color:NovaTheme.secondary; font.pixelSize:16 * NovaTheme.fontScale
        }
        NovaButton {
            id:searchButton; objectName:"host-search-start"; Layout.fillWidth:true
            onActiveFocusChanged: if (activeFocus) search.focusedId = ""
            text:search.state.busy ? "Stop Search" : "Search Again"
            onClicked: { if (search.provider) { if (search.state.busy) search.provider.stop(); else search.provider.start() } }
            Keys.onDownPressed: results.count ? results.itemAt(0).forceActiveFocus() : back.forceActiveFocus()
        }
        Label {
            objectName:"host-search-status"; Layout.fillWidth:true; wrapMode:Text.WordWrap; textFormat:Text.PlainText
            text:search.state.copy; color:NovaTheme.secondary; font.pixelSize:16 * NovaTheme.fontScale
        }
        NovaScrollColumn {
            Layout.fillWidth:true; Layout.fillHeight:true; spacing:8; reserveScrollBarSpace:true
            Repeater {
                id:results; model:search.hosts
                NovaButton {
                    required property var modelData
                    required property int index
                    objectName:"host-search-result-" + index
                    Layout.fillWidth:true
                    text:modelData.name + "\n" + (modelData.address.indexOf(":") >= 0 ? "[" + modelData.address + "]" : modelData.address) + ":" + modelData.port + " · " + modelData.network
                    onActiveFocusChanged: if (activeFocus) search.focusedId = modelData.id
                    onClicked:search.choose(modelData.id)
                    Keys.onUpPressed: index ? results.itemAt(index - 1).forceActiveFocus() : searchButton.forceActiveFocus()
                    Keys.onDownPressed: index + 1 < results.count ? results.itemAt(index + 1).forceActiveFocus() : back.forceActiveFocus()
                }
            }
        }
        NovaButton {
            id:back; objectName:"host-search-back"; Layout.fillWidth:true; text:"Back to Pairing"
            onActiveFocusChanged: if (activeFocus) search.focusedId = ""
            onClicked:search.close()
            Keys.onUpPressed: results.count ? results.itemAt(results.count - 1).forceActiveFocus() : searchButton.forceActiveFocus()
        }
    }
}

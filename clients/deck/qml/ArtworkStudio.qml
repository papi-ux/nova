import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: studio
    objectName: "artwork-studio"
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: parent ? parent.width : 1280
    height: parent ? parent.height : 800
    padding: 28
    modal: true
    focus: true
    property var controller: null
    property var settingsProvider: null
    property string hostId: ""
    property var game: ({})
    onHostIdChanged: if (opened) close()
    onGameChanged: if (opened && studioState.game && game.id !== studioState.game) close()
    readonly property var studioState: controller ? controller.state : ({})
    readonly property var candidates: studioState.candidates || []
    readonly property var choices: studioState.choices || []
    readonly property bool choosing: Object.keys(studioState.candidate || {}).length > 0
    readonly property var items: choosing ? choices : candidates
    property int current: 0
    property bool resetConfirm: false
    property bool wasBusy: false
    property string returnAfterWork: "search"
    readonly property var logoFallback: game.logoTransform || ({scale: 1, x: 0.5, y: 0.5})
    Connections {
        target: studio.controller
        function onStateChanged() {
            const busy = studio.controller.state.busy
            if (studio.wasBusy && !busy && studio.opened) Qt.callLater(function() {
                if (!studio.opened || studio.controller.state.busy || logoEditor.opened) return
                const focused = studio.contentItem.Window.window ? studio.contentItem.Window.window.activeFocusItem : null
                if (focused && focused.objectName === "artwork-back") return
                if (studio.returnAfterWork === "choice" && studio.items.length) studio.itemFocus(studio.current)
                else searchButton.forceActiveFocus()
            })
            studio.wasBusy = busy
        }
    }
    readonly property real unit: Math.max(0.85, Math.min(1.15, width / 1280))
    closePolicy: Popup.CloseOnEscape
    onAboutToShow: {
        resetConfirm = false; current = 0; searchText.text = game.title || ""
        if (controller) controller.prepare(hostId, game.id, settingsProvider.load(hostId, game.id).configuration)
    }
    onOpened: searchButton.forceActiveFocus()
    onClosed: { keyboard.close(); logoEditor.close(); if (controller) controller.close() }
    onItemsChanged: current = Math.min(current, Math.max(0, items.length - 1))
    function itemFocus(index) {
        current = Math.max(0, Math.min(items.length - 1, index))
        results.positionViewAtIndex(current, GridView.Contain)
        if (results.itemAtIndex(current)) results.itemAtIndex(current).forceActiveFocus()
    }
    function leave() { if (logoEditor.opened) logoEditor.close(); else if (resetConfirm) resetConfirm = false; else close() }
    background: Rectangle { color: NovaTheme.window }
    component Copy: Label {
        color: NovaTheme.text; textFormat: Text.PlainText; wrapMode: Text.WordWrap
        font.pixelSize: 17 * unit * NovaTheme.fontScale
    }
    component Action: NovaButton {
        unit: studio.unit
        Layout.preferredHeight: Math.max(52, 50 * unit * NovaTheme.fontScale)
        Keys.onEscapePressed: studio.leave()
    }
    EndpointKeyboard { id: keyboard; parent: Overlay.overlay; titleEntry: true }
    LogoPlacement {
        id: logoEditor
        settingsProvider: studio.settingsProvider; hostId: studio.hostId; gameId: studio.game.id || ""
        logoSource: studio.game.logo || ""; heroSource: studio.game.hero || ""; fallback: studio.logoFallback
        returnFocus: placement
    }
    contentItem: ColumnLayout {
        spacing: 12 * unit
        RowLayout {
            Layout.fillWidth: true
            Copy { text: "Artwork Studio"; font.pixelSize: 30 * unit * NovaTheme.fontScale; font.bold: true; Layout.fillWidth: true }
            Action { id: placement; objectName: "artwork-logo-placement"; text: "Logo placement"
                enabled: !!settingsProvider && !!game.logo && !studioState.busy
                onClicked: logoEditor.open()
                Keys.onRightPressed: back.forceActiveFocus(); Keys.onLeftPressed: searchButton.forceActiveFocus(); Keys.onDownPressed: searchButton.forceActiveFocus() }
            Action { id: back; objectName: "artwork-back"; text: "Back"; onClicked: studio.close(); Keys.onDownPressed: searchButton.forceActiveFocus(); Keys.onLeftPressed: placement.enabled ? placement.forceActiveFocus() : searchButton.forceActiveFocus() }
        }
        Copy { text: game.title || ""; color: NovaTheme.secondary; Layout.fillWidth: true; maximumLineCount: 1; elide: Text.ElideRight }
        RowLayout {
            Layout.fillWidth: true
            TextField {
                id: searchText; objectName: "artwork-query"; Layout.fillWidth: true
                placeholderText: "Search for a game title"; maximumLength: 160
                font.pixelSize: 18 * unit * NovaTheme.fontScale
                color: NovaTheme.text
                background: Rectangle { color: NovaTheme.panel; radius: 8; border.width: searchText.activeFocus ? 2 : 1; border.color: searchText.activeFocus ? NovaTheme.focus : NovaTheme.divider }
                onAccepted: if (searchButton.enabled) searchButton.clicked()
                Keys.onDownPressed: searchButton.forceActiveFocus()
            }
            Action { id: typeButton; text: "Type"; onClicked: { keyboard.edit(searchText, false); keyboard.alphabet = true }
                Keys.onRightPressed: searchButton.forceActiveFocus(); Keys.onLeftPressed: searchText.forceActiveFocus(); Keys.onDownPressed: searchButton.forceActiveFocus() }
            Action {
                id: searchButton; objectName: "artwork-search"; text: studioState.busy ? "Working…" : "Search"
                enabled: !!controller && !studioState.busy && searchText.text.trim().length > 0
                onClicked: { resetConfirm = false; returnAfterWork = "search"; controller.search(searchText.text) }
                Keys.onLeftPressed: typeButton.forceActiveFocus()
                Keys.onUpPressed: back.forceActiveFocus()
                Keys.onDownPressed: choosing ? kindButtons.itemAt(0).forceActiveFocus() : items.length ? studio.itemFocus(0) : refresh.forceActiveFocus()
            }
        }
        RowLayout {
            visible: choosing
            Layout.fillWidth: true
            Repeater {
                id: kindButtons
                model: [{id:"poster",label:"Poster"},{id:"hero",label:"Backdrop"},{id:"logo",label:"Logo"},{id:"icon",label:"Icon"}]
                Action {
                    required property var modelData; required property int index
                    objectName: "artwork-kind-" + modelData.id
                    Layout.fillWidth: true
                    text: modelData.label + (studioState.kind === modelData.id ? " · Current" : "")
                    enabled: !studioState.busy
                    onClicked: { studio.returnAfterWork = "choice"; controller.selectKind(modelData.id) }
                    Keys.onLeftPressed: index > 0 ? kindButtons.itemAt(index - 1).forceActiveFocus() : searchButton.forceActiveFocus()
                    Keys.onRightPressed: index < 3 ? kindButtons.itemAt(index + 1).forceActiveFocus() : apply.forceActiveFocus()
                    Keys.onUpPressed: searchButton.forceActiveFocus()
                    Keys.onDownPressed: items.length ? studio.itemFocus(0) : refresh.forceActiveFocus()
                }
            }
        }
        RowLayout {
            Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 100
            spacing: 24 * unit
            GridView {
                id: results; objectName: "artwork-results"
                Layout.fillWidth: true; Layout.fillHeight: true; Layout.preferredWidth: 2
                clip: true; model: studio.items
                cellWidth: width / Math.max(1, Math.floor(width / (175 * unit)))
                cellHeight: 238 * unit * Math.min(1.15, NovaTheme.fontScale)
                delegate: NovaButton {
                    required property var modelData; required property int index
                    objectName: "artwork-result-" + index
                    unit: studio.unit; width: results.cellWidth - 12; height: results.cellHeight - 12
                    enabled: !studioState.busy
                    Accessible.name: studio.choosing ? "Artwork choice " + (index + 1) : modelData.title
                    Accessible.checked: studio.choosing && ((studioState.selections || {})[studioState.kind] || {}).token === modelData.token
                    onActiveFocusChanged: if (activeFocus) studio.current = index
                    onClicked: { studio.returnAfterWork = "choice"; studio.choosing ? controller.selectArtwork(index) : controller.selectCandidate(index) }
                    Keys.onUpPressed: index >= Math.floor(results.width / results.cellWidth) ? studio.itemFocus(index - Math.floor(results.width / results.cellWidth)) : studio.choosing ? kindButtons.itemAt(0).forceActiveFocus() : searchButton.forceActiveFocus()
                    Keys.onDownPressed: index + Math.floor(results.width / results.cellWidth) < studio.items.length ? studio.itemFocus(index + Math.floor(results.width / results.cellWidth)) : apply.enabled ? apply.forceActiveFocus() : refresh.forceActiveFocus()
                    Keys.onLeftPressed: studio.itemFocus(index - 1)
                    Keys.onRightPressed: index + 1 < studio.items.length ? studio.itemFocus(index + 1) : apply.forceActiveFocus()
                    Keys.onEscapePressed: studio.leave()
                    contentItem: ColumnLayout {
                        Image { Layout.fillWidth: true; Layout.fillHeight: true; source: modelData.preview || ""; asynchronous: true; fillMode: Image.PreserveAspectFit }
                        Copy {
                            Layout.fillWidth: true; color: parent.parent.activeFocus ? NovaTheme.focusText : NovaTheme.text
                            text: studio.choosing ? "Choice " + (index + 1) + (((studioState.selections || {})[studioState.kind] || {}).token === modelData.token ? " · Selected" : "") : modelData.title
                            maximumLineCount: 2; elide: Text.ElideRight; font.pixelSize: 14 * unit * NovaTheme.fontScale
                        }
                    }
                }
            }
            NovaScrollColumn {
                Layout.fillWidth: true; Layout.fillHeight: true; Layout.preferredWidth: 1
                spacing: 10 * unit
                Copy { text: "Current → Preview"; font.bold: true }
                RowLayout {
                    Layout.fillWidth: true; Layout.preferredHeight: 210 * unit
                    Repeater {
                        model: [false, true]
                        Item {
                            required property bool modelData
                            Layout.fillWidth: true; Layout.fillHeight: true
                            Image { anchors.fill: parent; source: game.hero || ""; fillMode: Image.PreserveAspectCrop; clip: true; opacity: 0.3 }
                            Image {
                                anchors.fill: parent; anchors.margins: 5
                                source: modelData && ((studioState.selections || {})[studioState.kind] || {}).preview ? studioState.selections[studioState.kind].preview
                                    : game[studioState.kind === "hero" ? "hero" : studioState.kind || "poster"] || ""
                                asynchronous: true; fillMode: Image.PreserveAspectFit
                            }
                        }
                    }
                }
                Copy { Layout.fillWidth: true; text: choosing ? "Match: " + ((studioState.candidate || {}).title || "") : "Search, choose a game match, then select images." }
                Copy { Layout.fillWidth: true; color: NovaTheme.secondary; text: "Selections stay in preview until you apply them. Saved artwork is shared through this PC." }
                Copy { Layout.fillWidth: true; text: studioState.copy || ""; color: studioState.uncertain ? NovaTheme.warning : NovaTheme.secondary }
            }
        }
        Copy { visible: resetConfirm; text: "Clear the custom artwork match on this PC? The next refresh can find automatic artwork again."; color: NovaTheme.warning; Layout.fillWidth: true }
        RowLayout {
            Layout.fillWidth: true
            Action { id: refresh; text: "Refresh art"; enabled: !!controller && !studioState.busy && !studioState.uncertain; onClicked: controller.refreshArtwork(); Keys.onRightPressed: clear.forceActiveFocus(); Keys.onUpPressed: items.length ? studio.itemFocus(current) : searchButton.forceActiveFocus() }
            Action { id: clear; objectName: "artwork-reset"; text: resetConfirm ? "Confirm reset" : "Reset match"; enabled: refresh.enabled; onClicked: { if (resetConfirm) { controller.resetArtwork(); resetConfirm = false } else resetConfirm = true }
                Keys.onLeftPressed: refresh.forceActiveFocus(); Keys.onRightPressed: discard.forceActiveFocus(); Keys.onUpPressed: searchButton.forceActiveFocus() }
            Action { id: discard; text: "Discard"; enabled: !!controller && !studioState.busy; onClicked: { controller.discard(); resetConfirm = false }
                Keys.onLeftPressed: clear.forceActiveFocus(); Keys.onRightPressed: apply.forceActiveFocus(); Keys.onUpPressed: searchButton.forceActiveFocus() }
            Item { Layout.fillWidth: true }
            Action { id: apply; objectName: "artwork-apply"; text: "Apply"; primary: true; enabled: studioState.canApply || false; onClicked: { studio.returnAfterWork = "search"; controller.applyArtwork() }
 Keys.onLeftPressed: discard.forceActiveFocus(); Keys.onUpPressed: items.length ? studio.itemFocus(current) : searchButton.forceActiveFocus() }
        }
    }
}

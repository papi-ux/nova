import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ApplicationWindow {
    id: root

    width: novaDeckWidth
    height: novaDeckHeight
    visible: true
    title: novaDeckShellName
    color: "#070B18"

    readonly property int deckSafeMargin: 32
    readonly property int deckShellSpacing: 16
    readonly property int deckPanelSpacing: 12
    readonly property int deckRowSpacing: 16
    readonly property int hostColumnWidth: 336
    readonly property int sampleCardWidth: 392
    readonly property int detailColumnWidth: 424
    readonly property int hostCardHeight: 104
    readonly property int detailPanelHeight: 184
    readonly property int launchPreviewHeight: 258
    readonly property int hostTextWidth: hostColumnWidth - 40
    readonly property int sampleTextWidth: sampleCardWidth - 48
    readonly property int detailTextWidth: detailColumnWidth - 48
    property int previewCopyActivationCount: 0
    property var selectedHostForPreview: novaSelectedHostDetail
    property var selectedGameForPreview: novaSelectedGameCard
    property string selectedLaunchPreviewText: novaSelectedLaunchPreviewText
    property var launchPreviewCopyAction: novaLaunchPreviewCopyAction
    property var launchIntentPreview: novaLaunchIntentPreview
    property string selectedLaunchPublicCopy: launchIntentPreview.publicCopy
    property string selectedStreamLifecycleCopy: launchIntentPreview.streamLifecycleCopy

    function selectedHostSubtitle() {
        return "Selected host only — not discovered from the network."
    }

    function previewComponent(value) {
        return encodeURIComponent(value === undefined || value === null ? "" : String(value))
    }

    function refreshLaunchPreviewBinding() {
        const hostId = selectedHostForPreview && selectedHostForPreview.id
            ? selectedHostForPreview.id
            : "host-empty-state"
        const hostName = selectedHostForPreview && selectedHostForPreview.displayName
            ? selectedHostForPreview.displayName
            : "No host selected"
        const gameTitle = selectedGameForPreview && selectedGameForPreview.title
            ? selectedGameForPreview.title
            : "No game selected"
        const launchModeLabel = selectedGameForPreview && selectedGameForPreview.launchModeLabel
            ? selectedGameForPreview.launchModeLabel
            : "Stream: preview · Steam: direct"
        const streamMode = launchModeLabel.indexOf("virtual_display") >= 0 ? "virtual_display"
            : launchModeLabel.indexOf("headless") >= 0 ? "headless"
            : "preview"
        const steamMode = launchModeLabel.indexOf("big-picture") >= 0 ? "steam-big-picture" : "steam-direct"
        const steamCopy = steamMode === "steam-big-picture" ? "Steam Big Picture" : "Steam direct"
        selectedLaunchPreviewText = "preview://nova-deck/launch?host="
            + previewComponent(hostId)
            + "&game="
            + previewComponent(gameTitle)
            + "&mode="
            + steamMode
            + "&stream="
            + previewComponent(streamMode)
            + "&state=noop-preview"
        selectedLaunchPublicCopy = "Review " + gameTitle + " on " + hostName + " via " + steamCopy + ". Safe preview only; no game or stream starts."
        selectedStreamLifecycleCopy = "Safe preview of " + gameTitle + " on " + hostName + "; stream remains not started."
        launchPreviewCopyAction = {
            "id": novaLaunchPreviewCopyAction.id,
            "label": novaLaunchPreviewCopyAction.label,
            "previewText": selectedLaunchPreviewText,
            "idleStatusLabel": novaLaunchPreviewCopyAction.idleStatusLabel,
            "successToast": novaLaunchPreviewCopyAction.successToast,
            "inertToast": novaLaunchPreviewCopyAction.inertToast,
            "enabled": selectedLaunchPreviewText.length > 0,
            "copyOnly": true,
            "uiLocalClipboardOnly": true,
            "executable": false
        }
    }

    function selectHostForPreview(hostModel) {
        selectedHostForPreview = {
            "id": hostModel.id,
            "displayName": hostModel.displayName,
            "statusLabel": hostModel.statusLabel,
            "subtitle": selectedHostSubtitle()
        }
        refreshLaunchPreviewBinding()
    }

    function selectGameForPreview(gameModel) {
        selectedGameForPreview = {
            "id": gameModel.id,
            "title": gameModel.title,
            "sourceRuntimeLabel": gameModel.sourceRuntimeLabel,
            "launchModeLabel": gameModel.launchModeLabel,
            "installedLabel": gameModel.installedLabel
        }
        refreshLaunchPreviewBinding()
    }

    function focusSelectedLibraryItem() {
        for (let i = 0; i < libraryGameRepeater.count; ++i) {
            const gameItem = libraryGameRepeater.itemAt(i)
            if (gameItem !== null && selectedGameForPreview && gameItem.objectName === selectedGameForPreview.id) {
                gameItem.forceActiveFocus()
                return
            }
        }
        for (let i = 0; i < hostRepeater.count; ++i) {
            const hostItem = hostRepeater.itemAt(i)
            if (hostItem !== null && selectedHostForPreview && hostItem.objectName === selectedHostForPreview.id) {
                hostItem.forceActiveFocus()
                return
            }
        }
        if (novaLibraryHosts.length === 0) {
            emptyHostState.forceActiveFocus()
            return
        }
        if (novaLibraryGames.length === 0 && emptyGameState.visible) {
            emptyGameState.forceActiveFocus()
            return
        }
        if (hostRepeater.itemAt(0) !== null) {
            hostRepeater.itemAt(0).forceActiveFocus()
        } else {
            emptyHostState.forceActiveFocus()
        }
    }

    function activateLaunchPreviewCopyFromController() {
        const canCopyPreview = launchPreviewCopyAction.enabled
            && launchPreviewCopyAction.previewText.length > 0
            && launchPreviewCopyAction.copyOnly
            && launchPreviewCopyAction.uiLocalClipboardOnly
            && !launchPreviewCopyAction.executable
        const didCopyPreview = canCopyPreview
            && novaLocalClipboard.copyPreviewText(launchPreviewCopyAction.previewText)
        if (didCopyPreview) {
            previewCopyActivationCount += 1
        }
        copyStatusLabel.text = didCopyPreview
            ? launchPreviewCopyAction.successToast + " · A pressed #" + previewCopyActivationCount
            : launchPreviewCopyAction.inertToast + " · A press stayed preview-only"
        copyStatusLabel.color = didCopyPreview ? "#8AFFC1" : "#FFDDA8"
    }

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#111936" }
            GradientStop { position: 1.0; color: "#070B18" }
        }
    }

    Connections {
        target: novaGamepad
        function onPrimaryActionPressed(activationCount) {
            activateLaunchPreviewCopyFromController()
        }
    }

    FocusScope {
        id: libraryFocusScope
        anchors.fill: parent
        focus: true
        Component.onCompleted: Qt.callLater(function() {
            refreshLaunchPreviewBinding()
            if (novaLibraryHosts.length > 0 && hostRepeater.itemAt(0) !== null) {
                hostRepeater.itemAt(0).forceActiveFocus()
            } else {
                emptyHostState.forceActiveFocus()
            }
        })

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: deckSafeMargin
            spacing: deckShellSpacing

            Label {
                text: novaDeckShellName
                color: "#E9ECFF"
                font.pixelSize: 48
                font.bold: true
            }

            Label {
                text: "Your couch-ready Nova command center"
                color: "#A8B0D8"
                font.pixelSize: 24
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 2
                color: "#7C73FF"
                opacity: 0.65
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: deckRowSpacing

                ColumnLayout {
                    Layout.preferredWidth: hostColumnWidth
                    spacing: deckPanelSpacing

                    Label {
                        text: "Library hosts"
                        color: "#E9ECFF"
                        font.pixelSize: 28
                        font.bold: true
                    }

                    Rectangle {
                        id: emptyHostState
                        objectName: "host-empty-state"
                        visible: novaLibraryHosts.length === 0
                        Layout.preferredWidth: hostColumnWidth
                        Layout.preferredHeight: visible ? 120 : 0
                        radius: 20
                        color: activeFocus ? "#202B55" : "#151D39"
                        border.color: activeFocus ? "#B8C2FF" : "#39466F"
                        border.width: activeFocus ? 5 : 2
                        focus: visible
                        activeFocusOnTab: visible
                        KeyNavigation.right: hostDetailPanel
                        Keys.onRightPressed: hostDetailPanel.forceActiveFocus()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 18
                            spacing: 5

                            Label {
                                text: "No demo hosts yet"
                                color: "#E9ECFF"
                                font.pixelSize: 22
                                font.bold: true
                            }

                            Label {
                                text: "Empty host state is focusable and deterministic."
                                color: "#A8B0D8"
                                font.pixelSize: 14
                            }
                        }
                    }

                    Repeater {
                        id: hostRepeater
                        model: novaLibraryHosts

                        delegate: Rectangle {
                            required property int index
                            required property var modelData

                            objectName: modelData.id
                            Layout.preferredWidth: hostColumnWidth
                            Layout.preferredHeight: hostCardHeight
                            radius: 20
                            color: selectedHostForPreview.id === modelData.id ? "#202B55" : "#151D39"
                            border.color: activeFocus ? "#B8C2FF" : selectedHostForPreview.id === modelData.id ? "#8AFFC1" : "#7C73FF"
                            border.width: activeFocus ? 5 : selectedHostForPreview.id === modelData.id ? 4 : 3
                            focus: modelData.initialFocus
                            activeFocusOnTab: true
                            KeyNavigation.right: hostDetailPanel
                            onActiveFocusChanged: if (activeFocus) selectHostForPreview(modelData)
                            Keys.onRightPressed: {
                                selectHostForPreview(modelData)
                                hostDetailPanel.forceActiveFocus()
                            }
                            Keys.onReturnPressed: selectHostForPreview(modelData)
                            Keys.onEnterPressed: selectHostForPreview(modelData)
                            Keys.onSpacePressed: selectHostForPreview(modelData)
                            Keys.onDownPressed: {
                                const next = hostRepeater.itemAt((index + 1) % hostRepeater.count)
                                if (next !== null) {
                                    next.forceActiveFocus()
                                }
                            }
                            Keys.onUpPressed: {
                                const previous = hostRepeater.itemAt((index + hostRepeater.count - 1) % hostRepeater.count)
                                if (previous !== null) {
                                    previous.forceActiveFocus()
                                }
                            }

                            ColumnLayout {
                                anchors.fill: parent
                                anchors.margins: 18
                                spacing: 5

                                Label {
                                    text: modelData.displayName
                                    color: "#E9ECFF"
                                    font.pixelSize: 20
                                    font.bold: true
                                }

                                Label {
                                    text: modelData.statusLabel
                                    color: "#B8C2F0"
                                    font.pixelSize: 16
                                }

                                Label {
                                    visible: selectedHostForPreview.id === modelData.id
                                    text: "Selected host"
                                    color: "#8AFFC1"
                                    font.pixelSize: 14
                                    font.bold: true
                                }
                            }
                        }
                    }
                }

                ColumnLayout {
                    id: libraryGameList
                    objectName: "library-game-list"
                    Layout.preferredWidth: sampleCardWidth
                    spacing: deckPanelSpacing

                    Label {
                        text: "Polaris library preview"
                        color: "#E9ECFF"
                        font.pixelSize: 24
                        font.bold: true
                    }

                    Label {
                        Layout.preferredWidth: sampleTextWidth
                        text: novaLibraryFixtureSource + (novaLibraryReadOnly ? " · read-only · Preview snapshot ready" : " · Snapshot unavailable in this preview shell — no backend request will be made")
                        color: "#A8B0D8"
                        font.pixelSize: 13
                        wrapMode: Text.WordWrap
                    }

                    Rectangle {
                        id: emptyGameState
                        objectName: "game-empty-state"
                        visible: novaLibraryGames.length === 0
                        Layout.preferredWidth: sampleCardWidth
                        Layout.preferredHeight: visible ? 116 : 0
                        radius: 18
                        color: activeFocus ? "#202B55" : "#151D39"
                        border.color: activeFocus ? "#B8C2FF" : "#39466F"
                        border.width: activeFocus ? 5 : 2
                        focus: visible
                        activeFocusOnTab: visible
                        KeyNavigation.left: novaLibraryHosts.length > 0 ? hostRepeater.itemAt(0) : emptyHostState
                        KeyNavigation.right: hostDetailPanel
                        Keys.onLeftPressed: focusSelectedLibraryItem()
                        Keys.onRightPressed: hostDetailPanel.forceActiveFocus()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 16
                            spacing: 5

                            Label {
                                text: "No games in read-only snapshot"
                                color: "#E9ECFF"
                                font.pixelSize: 20
                                font.bold: true
                            }

                            Label {
                                Layout.preferredWidth: sampleTextWidth
                                text: "Snapshot unavailable in this preview shell — no backend request will be made."
                                color: "#A8B0D8"
                                font.pixelSize: 14
                                wrapMode: Text.WordWrap
                            }
                        }
                    }

                    Repeater {
                        id: libraryGameRepeater
                        model: novaLibraryGames

                        delegate: Rectangle {
                            required property int index
                            required property var modelData

                            objectName: modelData.id
                            Layout.preferredWidth: sampleCardWidth
                            Layout.preferredHeight: 88
                            radius: 18
                            color: selectedGameForPreview.id === modelData.id ? "#202B55" : "#151D39"
                            border.color: activeFocus ? "#B8C2FF" : selectedGameForPreview.id === modelData.id ? "#8AFFC1" : "#7C73FF"
                            border.width: activeFocus ? 5 : selectedGameForPreview.id === modelData.id ? 4 : 2
                            focus: modelData.initialFocus
                            activeFocusOnTab: true
                            KeyNavigation.right: hostDetailPanel
                            onActiveFocusChanged: if (activeFocus) selectGameForPreview(modelData)
                            Keys.onRightPressed: {
                                selectGameForPreview(modelData)
                                hostDetailPanel.forceActiveFocus()
                            }
                            Keys.onReturnPressed: selectGameForPreview(modelData)
                            Keys.onEnterPressed: selectGameForPreview(modelData)
                            Keys.onSpacePressed: selectGameForPreview(modelData)
                            Keys.onDownPressed: {
                                const next = libraryGameRepeater.itemAt((index + 1) % libraryGameRepeater.count)
                                if (next !== null) {
                                    next.forceActiveFocus()
                                }
                            }
                            Keys.onUpPressed: {
                                const previous = libraryGameRepeater.itemAt((index + libraryGameRepeater.count - 1) % libraryGameRepeater.count)
                                if (previous !== null) {
                                    previous.forceActiveFocus()
                                }
                            }
                            Keys.onLeftPressed: focusSelectedLibraryItem()

                            ColumnLayout {
                                anchors.fill: parent
                                anchors.margins: 16
                                spacing: 4

                                Label {
                                    text: modelData.title
                                    color: "#E9ECFF"
                                    font.pixelSize: 20
                                    font.bold: true
                                }

                                Label {
                                    text: modelData.sourceRuntimeLabel + " · " + modelData.installedLabel
                                    color: "#B8C2F0"
                                    font.pixelSize: 13
                                }

                                Label {
                                    text: modelData.launchModeLabel
                                    color: "#A8B0D8"
                                    font.pixelSize: 14
                                }

                                Label {
                                    visible: selectedGameForPreview.id === modelData.id
                                    text: "Selected game"
                                    color: "#8AFFC1"
                                    font.pixelSize: 13
                                    font.bold: true
                                }
                            }
                        }
                    }
                }

                ColumnLayout {
                    Layout.preferredWidth: detailColumnWidth
                    spacing: deckPanelSpacing

                    Rectangle {
                        id: hostDetailPanel
                        objectName: "host-detail-panel"
                        Layout.preferredWidth: detailColumnWidth
                        Layout.preferredHeight: detailPanelHeight
                        radius: 22
                        color: activeFocus ? "#202B55" : "#151D39"
                        border.color: activeFocus ? "#B8C2FF" : "#39466F"
                        border.width: activeFocus ? 5 : 2
                        focus: true
                        activeFocusOnTab: true
                        KeyNavigation.left: hostRepeater.itemAt(0) !== null ? hostRepeater.itemAt(0) : emptyHostState
                        KeyNavigation.up: copyPreviewButton
                        KeyNavigation.down: launchCtaPlaceholder
                        Keys.onLeftPressed: focusSelectedLibraryItem()
                        Keys.onUpPressed: copyPreviewButton.forceActiveFocus()
                        Keys.onDownPressed: launchCtaPlaceholder.forceActiveFocus()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 20
                            spacing: 8

                            Label {
                                text: "Selected host"
                                color: "#7C88B8"
                                font.pixelSize: 16
                            }

                            Label {
                                text: selectedHostForPreview.displayName
                                color: "#E9ECFF"
                                font.pixelSize: 30
                                font.bold: true
                            }

                            Label {
                                text: selectedHostForPreview.statusLabel
                                color: "#B8C2F0"
                                font.pixelSize: 19
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedHostForPreview.subtitle
                                color: "#A8B0D8"
                                font.pixelSize: 16
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Selected game: " + selectedGameForPreview.title
                                color: "#8AFFC1"
                                font.pixelSize: 14
                                wrapMode: Text.WordWrap
                            }
                        }
                    }

                    Rectangle {
                        id: launchCtaPlaceholder
                        objectName: novaHostLaunchCta.id
                        Layout.preferredWidth: detailColumnWidth
                        Layout.preferredHeight: launchPreviewHeight
                        radius: 20
                        color: activeFocus ? "#2A2948" : "#181D34"
                        border.color: activeFocus ? "#B8C2FF" : "#39466F"
                        border.width: activeFocus ? 5 : 2
                        opacity: novaHostLaunchCta.enabled ? 1.0 : 0.72
                        focus: false
                        activeFocusOnTab: true
                        KeyNavigation.up: hostDetailPanel
                        KeyNavigation.down: copyPreviewButton
                        Keys.onUpPressed: hostDetailPanel.forceActiveFocus()
                        Keys.onDownPressed: copyPreviewButton.forceActiveFocus()
                        Keys.onReturnPressed: activateLaunchPreviewCopyFromController()
                        Keys.onEnterPressed: activateLaunchPreviewCopyFromController()
                        Keys.onSpacePressed: activateLaunchPreviewCopyFromController()
                        Keys.onLeftPressed: focusSelectedLibraryItem()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 18
                            spacing: 5

                            Label {
                                text: novaHostLaunchCta.label
                                color: "#E9ECFF"
                                font.pixelSize: 20
                                font.bold: true
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaHostLaunchCta.helpText
                                color: "#B8C2F0"
                                font.pixelSize: 14
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaHostLaunchCta.previewStateLabel
                                color: "#FFDDA8"
                                font.pixelSize: 14
                                font.bold: true
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Safe preview: no game, stream, or network launch starts from this screen."
                                color: "#FFDDA8"
                                font.pixelSize: 13
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaLaunchIntentBoundary.reason
                                color: "#A8B0D8"
                                font.pixelSize: 13
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedLaunchPublicCopy
                                color: "#C9F0D4"
                                font.pixelSize: 13
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedStreamLifecycleCopy
                                color: "#A8B0D8"
                                font.pixelSize: 13
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedLaunchPreviewText
                                color: "#A8B0D8"
                                font.pixelSize: 14
                                font.family: "monospace"
                                wrapMode: Text.WrapAnywhere
                            }

                            Button {
                                id: copyPreviewButton
                                objectName: launchPreviewCopyAction.id
                                text: activeFocus ? "A · " + launchPreviewCopyAction.label : launchPreviewCopyAction.label
                                enabled: launchPreviewCopyAction.enabled
                                focusPolicy: Qt.StrongFocus
                                activeFocusOnTab: true
                                KeyNavigation.up: launchCtaPlaceholder
                                KeyNavigation.down: hostDetailPanel
                                Keys.onUpPressed: launchCtaPlaceholder.forceActiveFocus()
                                Keys.onDownPressed: hostDetailPanel.forceActiveFocus()
                                Keys.onLeftPressed: focusSelectedLibraryItem()
                                Keys.onReturnPressed: activateLaunchPreviewCopyFromController()
                                Keys.onEnterPressed: activateLaunchPreviewCopyFromController()
                                Keys.onSpacePressed: activateLaunchPreviewCopyFromController()
                                onClicked: activateLaunchPreviewCopyFromController()
                            }

                            Label {
                                id: copyStatusLabel
                                Layout.preferredWidth: detailTextWidth
                                text: launchPreviewCopyAction.idleStatusLabel + " Press A on Copy to verify. A Copy preview saves this safe plan locally for inspection."
                                color: "#FFDDA8"
                                font.pixelSize: 14
                                wrapMode: Text.WordWrap
                            }
                        }
                    }
                }
            }

            Item { Layout.fillHeight: true }

            Label {
                text: novaDeckFullscreenPreferred
                    ? "D-pad Navigate · A Copy preview · 1280×800 Deck-first"
                    : "Deck default: 1280×800 · windowed test mode"
                color: "#7C88B8"
                font.pixelSize: 18
            }
        }
    }
}

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
    readonly property int detailPanelHeight: 132
    readonly property int launchPreviewHeight: 424
    readonly property int hostTextWidth: hostColumnWidth - 40
    readonly property int sampleTextWidth: sampleCardWidth - 48
    readonly property int detailTextWidth: detailColumnWidth - 48
    readonly property color focusRingColor: "#8AFFC1"
    readonly property color focusGlowColor: "#243D57"
    property int previewCopyActivationCount: 0
    property var selectedHostForPreview: novaSelectedHostDetail
    property var selectedGameForPreview: novaSelectedGameCard
    property string selectedLaunchPreviewText: novaSelectedLaunchPreviewText
    property var launchPreviewCopyAction: novaLaunchPreviewCopyAction
    property var launchIntentPreview: novaLaunchIntentPreview
    property var moonlightHandoffPreflight: novaMoonlightHandoffPreflight
    property string selectedLaunchPublicCopy: launchIntentPreview.publicCopy
    property string selectedStreamLifecycleCopy: launchIntentPreview.streamLifecycleCopy
    property string selectedMoonlightHandoffCopy: moonlightHandoffPreflight.publicPreviewCopy
    property string selectedMoonlightHandoffArgvPreview: moonlightHandoffPreflight.argvPreview
    property string selectedMoonlightHandoffFocusCopy: moonlightHandoffPreflight.focusFallbackCopy
    property string selectedMoonlightHandoffConfidence: moonlightHandoffPreflight.focusConfidence
    readonly property var selectedMoonlightReadinessChecks: moonlightHandoffPreflight.readinessChecks ? moonlightHandoffPreflight.readinessChecks : []

    function readinessStatusColor(status) {
        if (status === "passed") {
            return "#8AFFC1"
        }
        if (status === "blocked") {
            return "#FFDDA8"
        }
        return "#B8C2F0"
    }

    function readinessStatusCopy(status) {
        if (status === "passed") {
            return "Ready"
        }
        if (status === "blocked") {
            return "Blocked"
        }
        return "Review"
    }

    function readinessShortLabel(id, label) {
        if (id === "safe-snapshot") {
            return "Snap"
        }
        if (id === "app-snapshot") {
            return "App"
        }
        if (id === "typed-argv") {
            return "Argv"
        }
        if (id === "focus-return") {
            return "Focus"
        }
        return label
    }

    function selectedHostSubtitle() {
        return "Selected host only — not discovered from the network."
    }

    function previewComponent(value) {
        return encodeURIComponent(value === undefined || value === null ? "" : String(value))
    }

    function moonlightHandoffRuntimeGatesClosed() {
        return moonlightHandoffPreflight.safeToRender
            && !moonlightHandoffPreflight.executable
            && !moonlightHandoffPreflight.allowsNetwork
            && !moonlightHandoffPreflight.allowsProcessExecution
            && !moonlightHandoffPreflight.allowsMoonlight
            && !moonlightHandoffPreflight.allowsHostMutation
    }

    function refreshMoonlightHandoffPreflightBinding(hostName, gameTitle) {
        moonlightHandoffPreflight = novaMoonlightHandoffPreflightBridge.resolve(
            hostName,
            gameTitle,
            novaLibraryReadOnly,
            novaLibraryGames.length > 0)
        const canRenderMoonlightHandoff = moonlightHandoffRuntimeGatesClosed()
        selectedMoonlightHandoffCopy = canRenderMoonlightHandoff
            ? moonlightHandoffPreflight.publicPreviewCopy
            : "Moonlight handoff preview blocked until safe public copy is available. Nothing will launch yet."
        selectedMoonlightHandoffArgvPreview = canRenderMoonlightHandoff
            ? moonlightHandoffPreflight.argvPreview
            : "Typed argv plan unavailable until the preflight is safe to render."
        selectedMoonlightHandoffFocusCopy = canRenderMoonlightHandoff
            ? moonlightHandoffPreflight.focusFallbackCopy
            : "Return behavior withheld until the preflight is safe to render."
        selectedMoonlightHandoffConfidence = canRenderMoonlightHandoff
            ? moonlightHandoffPreflight.focusConfidence
            : "blocked_static"
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
        refreshMoonlightHandoffPreflightBinding(hostName, gameTitle)
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

    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.NoButton
        hoverEnabled: true
        cursorShape: Qt.BlankCursor
        z: 1000
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
                        color: activeFocus ? focusGlowColor : "#151D39"
                        border.color: activeFocus ? focusRingColor : "#39466F"
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
                            border.color: activeFocus ? focusRingColor : selectedHostForPreview.id === modelData.id ? "#8AFFC1" : "#7C73FF"
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
                        color: activeFocus ? focusGlowColor : "#151D39"
                        border.color: activeFocus ? focusRingColor : "#39466F"
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
                            border.color: activeFocus ? focusRingColor : selectedGameForPreview.id === modelData.id ? "#8AFFC1" : "#7C73FF"
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
                        color: activeFocus ? focusGlowColor : "#151D39"
                        border.color: activeFocus ? focusRingColor : "#39466F"
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
                            anchors.margins: 14
                            spacing: 4

                            Label {
                                text: "Selected host"
                                color: "#7C88B8"
                                font.pixelSize: 13
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedHostForPreview.displayName
                                color: "#E9ECFF"
                                font.pixelSize: 24
                                font.bold: true
                                maximumLineCount: 1
                                elide: Text.ElideRight
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedHostForPreview.statusLabel
                                color: "#B8C2F0"
                                font.pixelSize: 14
                                maximumLineCount: 1
                                elide: Text.ElideRight
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedHostForPreview.subtitle
                                color: "#A8B0D8"
                                font.pixelSize: 12
                                maximumLineCount: 1
                                elide: Text.ElideRight
                            }
                        }
                    }

                    Rectangle {
                        id: launchCtaPlaceholder
                        objectName: novaHostLaunchCta.id
                        Layout.preferredWidth: detailColumnWidth
                        Layout.preferredHeight: launchPreviewHeight
                        radius: 20
                        color: activeFocus ? focusGlowColor : "#181D34"
                        border.color: activeFocus ? focusRingColor : "#39466F"
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
                            anchors.margins: 16
                            spacing: 8

                            RowLayout {
                                Layout.preferredWidth: detailTextWidth
                                spacing: 10

                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2

                                    Label {
                                        text: novaHostLaunchCta.label
                                        color: "#E9ECFF"
                                        font.pixelSize: 19
                                        font.bold: true
                                    }

                                    Label {
                                        Layout.preferredWidth: detailTextWidth - 148
                                        text: novaHostLaunchCta.helpText
                                        color: "#B8C2F0"
                                        font.pixelSize: 12
                                        wrapMode: Text.WordWrap
                                    }
                                }

                                Rectangle {
                                    Layout.preferredWidth: 138
                                    Layout.preferredHeight: 30
                                    radius: 15
                                    color: "#2A2539"
                                    border.color: "#FFDDA8"
                                    border.width: 1

                                    Label {
                                        anchors.centerIn: parent
                                        text: novaHostLaunchCta.previewStateLabel.replace(" — not executable", "")
                                        color: "#FFDDA8"
                                        font.pixelSize: 10
                                        font.bold: true
                                        elide: Text.ElideRight
                                    }
                                }
                            }

                            Rectangle {
                                id: launchTargetSummaryCard
                                objectName: "launch-target-summary-card"
                                Layout.preferredWidth: detailTextWidth
                                Layout.preferredHeight: 70
                                radius: 14
                                color: "#10172B"
                                border.color: "#2E3B66"
                                border.width: 1

                                ColumnLayout {
                                    anchors.fill: parent
                                    anchors.margins: 10
                                    spacing: 3

                                    Label {
                                        text: "Review path"
                                        color: "#7C88B8"
                                        font.pixelSize: 11
                                        font.bold: true
                                    }

                                    Label {
                                        objectName: "launch-target-title"
                                        Layout.preferredWidth: detailTextWidth - 20
                                        text: selectedGameForPreview.title + "  →  " + selectedHostForPreview.displayName
                                        color: "#C9F0D4"
                                        font.pixelSize: 13
                                        font.bold: true
                                        maximumLineCount: 1
                                        elide: Text.ElideRight
                                    }

                                    Label {
                                        Layout.preferredWidth: detailTextWidth - 20
                                        text: "Safe preview only · no game or stream starts"
                                        color: "#FFDDA8"
                                        font.pixelSize: 11
                                        maximumLineCount: 1
                                        elide: Text.ElideRight
                                    }
                                }
                            }

                            Rectangle {
                                id: moonlightHandoffPanel
                                objectName: "moonlight-handoff-panel"
                                Layout.preferredWidth: detailTextWidth
                                Layout.preferredHeight: 202
                                radius: 16
                                color: "#101A30"
                                border.color: "#7C73FF"
                                border.width: 2

                                ColumnLayout {
                                    anchors.fill: parent
                                    anchors.margins: 12
                                    spacing: 5

                                    RowLayout {
                                        objectName: "moonlight-handoff-title-row"
                                        Layout.preferredWidth: detailTextWidth - 24
                                        spacing: 8

                                        Label {
                                            Layout.fillWidth: true
                                            text: "Moonlight handoff preview"
                                            color: "#E9ECFF"
                                            font.pixelSize: 14
                                            font.bold: true
                                            elide: Text.ElideRight
                                        }

                                        Rectangle {
                                            Layout.preferredWidth: 132
                                            Layout.preferredHeight: 24
                                            radius: 12
                                            color: "#1E2846"
                                            border.color: "#8AFFC1"
                                            border.width: 1

                                            Label {
                                                anchors.centerIn: parent
                                                text: "Nothing will launch yet"
                                                color: "#8AFFC1"
                                                font.pixelSize: 10
                                                font.bold: true
                                            }
                                        }
                                    }

                                    Label {
                                        Layout.preferredWidth: detailTextWidth - 24
                                        text: selectedMoonlightHandoffCopy
                                        color: "#C9F0D4"
                                        font.pixelSize: 11
                                        wrapMode: Text.WordWrap
                                        maximumLineCount: 2
                                        elide: Text.ElideRight
                                    }

                                    RowLayout {
                                        objectName: "moonlight-safety-chip-row"
                                        Layout.preferredWidth: detailTextWidth - 24
                                        spacing: 6

                                        Rectangle {
                                            Layout.preferredWidth: 86
                                            Layout.preferredHeight: 24
                                            radius: 12
                                            color: "#192842"

                                            Label {
                                                anchors.centerIn: parent
                                                text: "No launch"
                                                color: "#E9ECFF"
                                                font.pixelSize: 10
                                                font.bold: true
                                            }
                                        }

                                        Rectangle {
                                            id: moonlightRuntimeGateChip
                                            objectName: "moonlight-runtime-gate-chip"
                                            Layout.preferredWidth: 134
                                            Layout.preferredHeight: 24
                                            radius: 12
                                            color: moonlightHandoffRuntimeGatesClosed() ? "#173326" : "#3A2224"

                                            Label {
                                                anchors.centerIn: parent
                                                text: moonlightHandoffRuntimeGatesClosed() ? "No network/process" : "Blocked"
                                                color: moonlightHandoffRuntimeGatesClosed() ? "#8AFFC1" : "#FFDDA8"
                                                font.pixelSize: 10
                                                font.bold: true
                                            }
                                        }

                                        Rectangle {
                                            id: moonlightFocusChip
                                            objectName: "moonlight-focus-chip"
                                            Layout.fillWidth: true
                                            Layout.preferredHeight: 24
                                            radius: 12
                                            color: "#151D39"

                                            Label {
                                                anchors.centerIn: parent
                                                text: "Focus: unproven_static"
                                                color: "#B8C2F0"
                                                font.pixelSize: 10
                                                font.bold: true
                                            }
                                        }
                                    }

                                    RowLayout {
                                        objectName: "moonlight-readiness-row"
                                        Layout.preferredWidth: detailTextWidth - 24
                                        spacing: 5

                                        Label {
                                            Layout.preferredWidth: 48
                                            text: "Checks"
                                            color: "#7C88B8"
                                            font.pixelSize: 9
                                            font.bold: true
                                            maximumLineCount: 2
                                            elide: Text.ElideRight
                                        }

                                        Repeater {
                                            model: selectedMoonlightReadinessChecks

                                            Rectangle {
                                                objectName: "moonlight-readiness-chip"
                                                Layout.preferredWidth: 72
                                                Layout.preferredHeight: 22
                                                radius: 11
                                                color: modelData.status === "blocked" ? "#3A2224" : "#151D39"
                                                border.color: readinessStatusColor(modelData.status)
                                                border.width: 1

                                                Label {
                                                    anchors.centerIn: parent
                                                    text: readinessShortLabel(modelData.id, modelData.label) + " " + readinessStatusCopy(modelData.status)
                                                    color: readinessStatusColor(modelData.status)
                                                    font.pixelSize: 8
                                                    font.bold: true
                                                    maximumLineCount: 1
                                                    elide: Text.ElideRight
                                                }
                                            }
                                        }
                                    }

                                    RowLayout {
                                        objectName: "moonlight-plan-row"
                                        Layout.preferredWidth: detailTextWidth - 24
                                        spacing: 8

                                        Label {
                                            Layout.fillWidth: true
                                            text: moonlightHandoffRuntimeGatesClosed() ? "Typed argv plan" : "Review blocked"
                                            color: "#B8C2F0"
                                            font.pixelSize: 10
                                            font.bold: true
                                            maximumLineCount: 1
                                            elide: Text.ElideRight
                                        }

                                        Label {
                                            Layout.preferredWidth: 210
                                            text: moonlightHandoffRuntimeGatesClosed() ? "redacted argv · local preview only" : selectedMoonlightHandoffArgvPreview
                                            color: "#B8C2F0"
                                            font.pixelSize: 10
                                            horizontalAlignment: Text.AlignRight
                                            maximumLineCount: 1
                                            elide: Text.ElideRight
                                        }
                                    }

                                    Label {
                                        objectName: "moonlight-runtime-gates-line"
                                        Layout.preferredWidth: detailTextWidth - 24
                                        text: moonlightHandoffRuntimeGatesClosed()
                                            ? "Runtime locked: network · process · Moonlight · host off"
                                            : "Runtime gate failed — review blocked"
                                        color: "#FFDDA8"
                                        font.pixelSize: 10
                                        font.bold: true
                                        maximumLineCount: 1
                                        elide: Text.ElideRight
                                    }
                                }
                            }

                            RowLayout {
                                objectName: "copy-preview-action-row"
                                Layout.preferredWidth: detailTextWidth
                                spacing: 10

                                Button {
                                    id: copyPreviewButton
                                    objectName: launchPreviewCopyAction.id
                                    Layout.preferredWidth: 184
                                    Layout.preferredHeight: 30
                                    text: launchPreviewCopyAction.label
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
                                    Layout.fillWidth: true
                                    text: "Copy locally — no launch"
                                    color: "#8AFFC1"
                                    font.pixelSize: 12
                                    font.bold: true
                                    wrapMode: Text.WordWrap
                                }
                            }

                            Label {
                                id: copyStatusLabel
                                Layout.preferredWidth: detailTextWidth
                                Layout.preferredHeight: visible ? 14 : 0
                                text: ""
                                visible: text.length > 0
                                color: "#FFDDA8"
                                font.pixelSize: 9
                                wrapMode: Text.NoWrap
                                maximumLineCount: 1
                                elide: Text.ElideRight
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

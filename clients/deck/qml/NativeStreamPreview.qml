import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Nova.Deck.Stream 0.1

Popup {
    id: nativePreview
    objectName: "native-stream-preview"
    parent: Overlay.overlay
    width: parent.width
    height: parent.height
    padding: 0
    modal: true
    focus: true
    closePolicy: Popup.NoAutoClose
    property bool autoStartRequested: false
    property bool autoStartAttempted: false
    property int autoStartTicks: 0
    Timer {
        interval: 100; repeat: true; running: nativePreview.opened && nativePreview.autoStartRequested && !nativePreview.attempted
        onTriggered: {
            if (++nativePreview.autoStartTicks > 200) { nativePreview.autoStartRequested = false; playSetup.error = "Review these settings before starting from Steam."; return }
            if (nativePreviewAction.enabled) { nativePreview.autoStartRequested = false; nativePreview.autoStartAttempted = true; nativePreviewAction.clicked() }
        }
    }
    property bool attempted: false
    property bool externalVideo: false
    property var presentationBridge: null
    required property var session
    property var inputHub: null
    readonly property var players: inputHub ? inputHub.players : []
    required property var settingsProvider
    property var hostSettingsController: null
    property var gameTools: null
    property var launchPolicy: ({ known: false, hostDefault: "", allowed: [] })
    property var streamCapabilities: ({})
    property var displayPlanner: ({})
    property var displayCapabilities: ({})
    property string hostId: ""
    property string gameId: ""
    property string hostName: "Your PC"
    property string gameTitle: "Selected game"
    property string destinationId: "desktop"
    property string destinationName: "Desktop"
    property bool destinationPlayable: true
    property string returnLabel: "Back to details"
    property string reviewedHostId: ""
    property string reviewedGameId: ""
    property string reviewedHostName: ""
    property string reviewedGameTitle: ""
    property string reviewedDestinationId: "desktop"
    property string reviewedDestinationName: "Desktop"
    property bool reviewValid: false
    property string resumeError: ""
    readonly property bool reviewCurrent: hostId === reviewedHostId && gameId === reviewedGameId && destinationId === reviewedDestinationId
    readonly property real unit: Math.max(0.85, Math.min(1.15, width / 1280))
    readonly property var nativeSessionState: session.state
    readonly property bool resumeAvailable: attempted && !nativeSessionState.sleeping && !nativeSessionState.busy && nativeSessionState.canResume === true && reviewCurrent
    readonly property bool reconnectAvailable: attempted && !nativeSessionState.sleeping && !nativeSessionState.busy && nativeSessionState.canReconnect === true && reviewCurrent
    readonly property bool recoveryAvailable: resumeAvailable || reconnectAvailable
    readonly property bool nativePlaying: !nativeSessionState.sleeping && nativeSessionState.phase === "active"

    function leave() {
        if (videoScaling.opened) { videoScaling.close(); return }
        if (polarisSync.opened) { polarisSync.back(); return }
        if (doctor.opened) { doctor.close(); return }
        if (liveBitrate.opened) { liveBitrate.close(); return }
        if (hudSettings.opened) { hudSettings.close(); return }
        if (streamAppearance.opened) { streamAppearance.close(); return }
        if (endConfirmation.opened) { endConfirmation.close(); return }
        if (nativePlaying) {
            if (session.controlsVisible) session.resumeInput()
            else session.showControls()
            return
        }
        if (!attempted && playSetup.closeChoice()) return
        if (nativeSessionState.busy) session.stop()
        else close()
    }
    function requestEnd() {
        if (nativePlaying && session.controlsVisible) endConfirmation.open()
    }
    function setupState() { return Object.assign(playSetup.state(), { autoStartAttempted: autoStartAttempted, hostId: reviewedHostId, gameId: reviewedGameId, valid: reviewValid, playEnabled: nativePreviewAction.enabled }) }
    function focusReview() {
        if (!reviewValid) playSetup.focusBack()
        else if (nativePreviewAction.enabled) nativePreviewAction.forceActiveFocus()
        else playSetup.focusSettings()
    }
    onReviewCurrentChanged: {
        if (opened && !attempted && !reviewCurrent) {
            reviewValid = false
            playSetup.closeChoice()
            playSetup.error = "The library selection changed. Go back and review the game again."
            Qt.callLater(playSetup.focusBack)
        }
    }
    onDestinationPlayableChanged: {
        if (opened && !attempted && !destinationPlayable) {
            reviewValid = false
            playSetup.closeChoice()
            playSetup.error = "This destination is no longer ready. Return to the library and check it again."
            Qt.callLater(playSetup.focusBack)
        }
    }

    Connections {
        target: presentationBridge
        function onChanged() {
            if (!nativePreview.opened) return
            Qt.callLater(function() {
                if (!nativePreview.opened) return
                if (presentationBridge.error.length > 0) {
                    if (nativePreview.attempted) nativePreviewAction.forceActiveFocus()
                    else playSetup.focusBack()
                } else if (!nativePreview.attempted && presentationBridge.ready) nativePreview.focusReview()
            })
        }
    }
    Connections {
        target: session
        function onStateChanged() {
            resumeError = "";
            if (!nativePlaying) { endConfirmation.close(); hudSettings.close(); liveBitrate.close(); doctor.close(); polarisSync.close(); videoScaling.close() }
            if (nativePreview.opened && !nativePlaying) Qt.callLater(function() {
                if (nativePreview.opened) nativePreviewAction.forceActiveFocus()
            })
        }
        function onControlsChanged() {
            if (!session.controlsVisible) { liveBitrate.close(); doctor.close(); polarisSync.close(); videoScaling.close() }
            if (!nativePreview.opened) return
            Qt.callLater(function() {
                if (!nativePreview.opened || endConfirmation.opened || liveBitrate.opened || doctor.opened || polarisSync.opened || videoScaling.opened) return
                if (session.controlsVisible) nativePreviewAction.forceActiveFocus()
                else nativeStreamContent.forceActiveFocus()
            })
        }
    }

    onAboutToShow: {
        if (presentationBridge) presentationBridge.attach(nativePreview)
        attempted = false
        autoStartTicks = 0
        resumeError = ""
        reviewedHostId = hostId
        reviewedGameId = gameId
        reviewedHostName = hostName
        reviewedGameTitle = gameTitle
        reviewedDestinationId = destinationId
        reviewedDestinationName = destinationName
        reviewValid = destinationPlayable
        playSetup.prepare()
        focusReview()
    }
    onOpened: focusReview()
    onClosed: {
        videoScaling.close()
        polarisSync.close()
        if (gameTools) gameTools.close()
        playSetup.closeHostDefaults()
        if (presentationBridge) presentationBridge.detach()
    }
    Overlay.modal: Rectangle { color: "transparent" }
    background: Rectangle { color: externalVideo && attempted && nativePlaying ? "transparent" : NovaTheme.window }

    component SessionAction: Button {
        id: action
        property string caption: ""
        property bool destructive: false
        property real textScale: nativePreview.unit
        Accessible.description: caption
        contentItem: Item {
            Column {
                anchors.centerIn: parent
                width: parent.width
                spacing: 4
                Text {
                    width: parent.width
                    text: action.text; textFormat: Text.PlainText
                    color: action.activeFocus ? NovaTheme.window : "white"
                    font.pixelSize: 20 * action.textScale * NovaTheme.fontScale; font.bold: true
                    horizontalAlignment: Text.AlignHCenter
                }
                Text {
                    width: parent.width
                    visible: action.caption.length > 0
                    text: action.caption; textFormat: Text.PlainText
                    wrapMode: Text.WordWrap
                    color: action.activeFocus ? NovaTheme.raised : NovaTheme.secondary
                    font.pixelSize: 13 * action.textScale * NovaTheme.fontScale
                    horizontalAlignment: Text.AlignHCenter
                }
            }
        }
        background: Rectangle {
            radius: 12
            color: action.activeFocus ? NovaTheme.focus : action.destructive ? "#422B3A" : NovaTheme.raised
            border.color: action.activeFocus ? NovaTheme.focus : action.destructive ? NovaTheme.danger : NovaTheme.divider
            border.width: action.activeFocus ? 5 : 1
        }
    }

    contentItem: Item {
        id: nativeStreamContent
        focus: true
        Keys.onEscapePressed: leave()
        DeckVaapiPreviewSurface {
            objectName: "nova-native-stream-surface"
            anchors.fill: parent
            videoScaleMode: nativePreview.settingsProvider.videoScaleMode
            visible: nativeSessionState.busy && !nativePreview.externalVideo
        }
        NovaHud {
            id: streamHud
            visible: nativePreview.attempted && nativePlaying && !session.controlsVisible && NovaHudPreferences.enabled
            readings: session.hud || ({})
            unit: nativePreview.unit
            onCommandCenterRequested: session.showControls()
        }
        Rectangle {
            visible: nativePreview.attempted && nativePlaying && !session.controlsVisible
                && (nativeSessionState.audioCopy || "").length > 0
            anchors.top: parent.top
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.topMargin: 24 * nativePreview.unit
            width: Math.min(parent.width - 48 * nativePreview.unit, 760 * nativePreview.unit)
            height: audioNotice.implicitHeight + 28 * nativePreview.unit
            radius: 12 * nativePreview.unit
            color: NovaTheme.alpha(NovaTheme.window, 0.902)
            Label {
                id: audioNotice
                objectName: "native-audio-notice"
                anchors.centerIn: parent
                width: parent.width - 32 * nativePreview.unit
                text: nativeSessionState.audioCopy || ""
                textFormat: Text.PlainText
                wrapMode: Text.WordWrap
                color: NovaTheme.warning
                font.pixelSize: 20 * nativePreview.unit * NovaTheme.fontScale
            }
        }
        PlaySetup {
            id: playSetup
            hostSettingsController: nativePreview.hostSettingsController
            gameTools: nativePreview.gameTools
            anchors.fill: parent
            visible: !nativePreview.attempted
            settingsProvider: nativePreview.settingsProvider
            launchPolicy: nativePreview.launchPolicy
            streamCapabilities: nativePreview.streamCapabilities
            displayPlanner: nativePreview.displayPlanner
            displayCapabilities: nativePreview.displayCapabilities
            hostId: nativePreview.reviewedHostId
            gameId: nativePreview.reviewedGameId
            hostName: nativePreview.reviewedHostName
            gameTitle: nativePreview.reviewedGameTitle
            destinationId: nativePreview.reviewedDestinationId
            destinationName: nativePreview.reviewedDestinationName
            editable: nativePreview.reviewValid
            returnLabel: nativePreview.returnLabel
            onChoiceOpened: nativePreview.autoStartRequested = false
            onFocusPlayRequested: nativePreview.focusReview()
            onBackRequested: nativePreview.close()
            onHostDefaultsMayHaveChanged: {
                nativePreview.reviewValid = false
                playSetup.error = "Host defaults may have changed. Return to the library and review this game again."
            }
            onNovaDefaultsChanged: {
                nativePreview.reviewValid = false
                playSetup.error = "Nova's stream defaults changed. Go back and review this game again."
            }
            onLaunchModeAllowedChanged: if (nativePreview.opened && !nativePreview.attempted && !launchModeAllowed)
                Qt.callLater(focusSettings)
            onPlanChanged: if (nativePreview.opened && !nativePreview.attempted && !plan.playable)
                Qt.callLater(focusSettings)
        }
        Label {
            objectName: "native-presentation-error"
            visible: presentationBridge && presentationBridge.error.length > 0
            anchors.left: parent.left; anchors.right: parent.right
            anchors.bottom: parent.bottom; anchors.margins: 24 * nativePreview.unit
            anchors.bottomMargin: 104 * nativePreview.unit
            text: presentationBridge ? presentationBridge.error : ""
            color: NovaTheme.warning
            font.pixelSize: 18 * NovaTheme.fontScale
            wrapMode: Text.WordWrap
        }
        Rectangle {
            visible: nativePreview.attempted && !nativePlaying
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            height: Math.max(150, statusContent.implicitHeight + 48)
            color: NovaTheme.alpha(NovaTheme.window, 0.902)
            Column {
                id: statusContent
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.margins: 24
                spacing: 12
                Label {
                    text: reviewedGameTitle
                    textFormat: Text.PlainText
                    width: parent.width
                    elide: Text.ElideRight
                    color: NovaTheme.text
                    font.pixelSize: 26 * NovaTheme.fontScale
                    font.bold: true
                }
                Label {
                    width: parent.width
                    text: nativeSessionState.sleeping ? "Stream paused for sleep. Resume after waking your Deck."
                        : (nativeSessionState.automaticReconnect ? "Reconnecting (" + nativeSessionState.reconnectAttempt + " of 4). " : "")
                        + (resumeError || nativeSessionState.copy) + (nativePlaying ? " " + session.controllerHint : "")
                        + (nativePlaying && nativeSessionState.audioCopy ? " " + nativeSessionState.audioCopy : "")
                    textFormat: Text.PlainText
                    color: NovaTheme.secondary
                    font.pixelSize: 18 * NovaTheme.fontScale
                    wrapMode: Text.WordWrap
                }
            }
        }
        Rectangle {
            id: commandCenter
            objectName: "native-command-center"
            visible: nativePlaying && session.controlsVisible
            anchors.left: parent.left
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            width: Math.min(820 * nativePreview.unit, parent.width * 0.72)
            color: NovaTheme.alpha(NovaTheme.panel, NovaTheme.highContrast ? 1 : 0.94)
            border.color: NovaTheme.divider
            Label {
                anchors.left: parent.left; anchors.top: parent.top
                anchors.margins: 24 * nativePreview.unit
                text: "Command Center"
                color: NovaTheme.text
                font.pixelSize: 28 * nativePreview.unit * NovaTheme.fontScale
                font.bold: true
            }
            Label {
                anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                anchors.leftMargin: 24 * nativePreview.unit; anchors.rightMargin: 24 * nativePreview.unit
                anchors.topMargin: 66 * nativePreview.unit
                text: reviewedGameTitle + " · " + reviewedHostName
                textFormat: Text.PlainText
                color: NovaTheme.secondary
                font.pixelSize: 18 * nativePreview.unit * NovaTheme.fontScale
                elide: Text.ElideRight
            }
            Item {
                id: commandHeader
                anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                anchors.margins: 24 * nativePreview.unit
                anchors.topMargin: 108 * nativePreview.unit
                height: 90 * nativePreview.unit
                readonly property real buttonWidth: (width - (nativeDisconnectAction.visible ? 24 : 12) * nativePreview.unit)
                    / (nativeDisconnectAction.visible ? 3 : 2)
            }
            NovaScrollColumn {
                anchors.left: parent.left; anchors.right: parent.right
                anchors.top: commandHeader.bottom; anchors.bottom: parent.bottom
                anchors.margins: 24 * nativePreview.unit
                spacing: 16 * nativePreview.unit
                Label {
                    Layout.fillWidth: true
                    text: nativeSessionState.copy + (nativeSessionState.audioCopy ? "\n" + nativeSessionState.audioCopy : "")
                    textFormat: Text.PlainText
                    wrapMode: Text.WordWrap
                    color: NovaTheme.secondary
                    font.pixelSize: 18 * nativePreview.unit * NovaTheme.fontScale
                }
                Rectangle { Layout.fillWidth: true; height: 1; color: NovaTheme.divider }
                Label {
                    objectName: "native-desktop-input-help"
                    Layout.fillWidth: true
                    text: "Keyboard: Ctrl + Alt + Shift + M opens Command Center. Escape goes to your game.\nMouse: direct pointer control for desktop apps and menus. Relative mouse aiming is not available yet."
                    textFormat: Text.PlainText
                    wrapMode: Text.WordWrap
                    color: NovaTheme.secondary
                    font.pixelSize: 16 * nativePreview.unit * NovaTheme.fontScale
                }
                Label {
                    text: "Players"; color: NovaTheme.text
                    font.pixelSize: 24 * nativePreview.unit * NovaTheme.fontScale; font.bold: true
                }
                Label {
                    Layout.fillWidth: true
                    visible: nativePreview.players.length === 0
                    text: "Connect a controller to join the game."
                    color: NovaTheme.secondary
                    font.pixelSize: 18 * nativePreview.unit * NovaTheme.fontScale
                    wrapMode: Text.WordWrap
                }
                Repeater {
                    model: nativePreview.players
                    Label {
                        required property var modelData
                        Layout.fillWidth: true
                        text: modelData.waiting ? modelData.name + " · Press a button to join"
                            : "P" + (modelData.player + 1) + "  " + modelData.name
                        textFormat: Text.PlainText
                        color: modelData.waiting ? NovaTheme.secondary : NovaTheme.text
                        font.pixelSize: 18 * nativePreview.unit * NovaTheme.fontScale
                        wrapMode: Text.WordWrap
                    }
                }
                NovaButton {
                    id: reassignPlayers
                    objectName: "native-players-reassign"
                    Layout.fillWidth: true
                    unit: nativePreview.unit
                    text: "Reassign players"
                    enabled: nativePreview.players.some(player => !player.waiting)
                    onClicked: if (inputHub && inputHub.reassignPlayers()) reassignNotice.restart()
                    Keys.onUpPressed: nativeEndAction.forceActiveFocus()
                    Keys.onDownPressed: inGameAppearance.forceActiveFocus()
                }
                Label {
                    Layout.fillWidth: true
                    text: "Choose Reassign, then press a button on each controller in the order you want to play."
                    color: NovaTheme.secondary
                    font.pixelSize: 16 * nativePreview.unit * NovaTheme.fontScale
                    wrapMode: Text.WordWrap
                }
                NovaButton {
                    id: inGameAppearance
                    objectName: "native-appearance"
                    Layout.fillWidth: true
                    unit: nativePreview.unit
                    text: "Appearance"
                    onClicked: streamAppearance.open()
                    Keys.onUpPressed: reassignPlayers.enabled ? reassignPlayers.forceActiveFocus() : nativeEndAction.forceActiveFocus()
                    Keys.onDownPressed: inGameScale.forceActiveFocus()
                }
                NovaButton {
                    id: inGameScale; objectName: "native-video-scale"
                    Layout.fillWidth: true; unit: nativePreview.unit
                    text: "Video scaling · " + (nativePreview.settingsProvider.videoScaleMode === "fill" ? "Fill" : nativePreview.settingsProvider.videoScaleMode === "stretch" ? "Stretch" : "Fit")
                    onClicked: videoScaling.open()
                    Keys.onUpPressed: inGameAppearance.forceActiveFocus()
                    Keys.onDownPressed: inGameHud.forceActiveFocus()
                }
                NovaButton {
                    id: inGameHud
                    objectName: "native-hud-settings"
                    Layout.fillWidth: true
                    unit: nativePreview.unit
                    text: "NovaHUD · " + (NovaHudPreferences.enabled ? NovaHudPreferences.title : "Off")
                    onClicked: hudSettings.open()
                    Keys.onUpPressed: inGameScale.forceActiveFocus()
                    Keys.onDownPressed: liveTuningAction.enabled ? liveTuningAction.forceActiveFocus() : doctorAction.forceActiveFocus()
                }
                NovaButton {
                    id: liveTuningAction
                    objectName: "native-live-tuning"
                    Layout.fillWidth: true
                    unit: nativePreview.unit
                    readonly property var status: session && session.hud ? session.hud : ({})
                    enabled: nativePlaying && (status.canTune === true || status.tuningBusy === true || status.hostRefreshing === true)
                    onEnabledChanged: if (!enabled && activeFocus && nativePlaying) inGameHud.forceActiveFocus()
                    onActiveFocusChanged: if (!activeFocus && !enabled && nativePlaying && session.controlsVisible)
                        Qt.callLater(() => inGameHud.forceActiveFocus())
                    text: "Live Tuning · " + (status.hostRefreshing ? "Refreshing…" : status.tuningBusy ? "Saving…" : status.tuningKnown ? status.tuningEnabled ? "On" : "Off" : "Unknown")
                    Accessible.checkable: true
                    Accessible.checked: status.tuningKnown === true && status.tuningEnabled === true
                    Accessible.description: status.tuningCopy || "Live Tuning is unavailable for this stream."
                    contentItem: Column {
                        spacing: 6 * nativePreview.unit
                        Text {
                            width: parent.width
                            text: liveTuningAction.text
                            textFormat: Text.PlainText
                            font: liveTuningAction.font
                            color: liveTuningAction.activeFocus ? NovaTheme.focusText : NovaTheme.text
                            wrapMode: Text.WordWrap
                        }
                        Text {
                            objectName: "native-live-tuning-copy"
                            width: parent.width
                            text: (liveTuningAction.status.tuningKnown && liveTuningAction.status.tuningLabel
                                ? liveTuningAction.status.tuningLabel + ". " : "")
                                + (liveTuningAction.status.tuningCopy || "Live Tuning is unavailable for this stream.")
                            textFormat: Text.PlainText
                            wrapMode: Text.WordWrap
                            color: liveTuningAction.activeFocus ? NovaTheme.focusText : NovaTheme.secondary
                            font.pixelSize: 15 * nativePreview.unit * NovaTheme.fontScale
                        }
                    }
                    onClicked: if (status.canTune && !status.tuningBusy) session.setLiveTuningEnabled(!status.tuningEnabled)
                    Keys.onUpPressed: inGameHud.forceActiveFocus()
                    Keys.onDownPressed: liveBitrateAction.enabled ? liveBitrateAction.forceActiveFocus() : doctorAction.forceActiveFocus()
                }
                NovaButton {
                    id: liveBitrateAction
                    objectName: "native-live-bitrate"
                    Layout.fillWidth: true; unit: nativePreview.unit
                    enabled: nativePlaying && (liveTuningAction.status.canSetBitrate === true || liveTuningAction.status.bitrateBusy === true || liveTuningAction.status.hostRefreshing === true)
                    text: "Live bitrate · " + (liveTuningAction.status.hostRefreshing ? "Refreshing…" : liveTuningAction.status.appliedBitrateKbps > 0 ? liveBitrate.format(liveTuningAction.status.appliedBitrateKbps) : "Unavailable")
                    onClicked: if (!liveTuningAction.status.hostRefreshing) liveBitrate.open()
                    onEnabledChanged: if (!enabled && activeFocus && nativePlaying) inGameHud.forceActiveFocus()
                    onActiveFocusChanged: if (!activeFocus && !enabled && nativePlaying && session.controlsVisible)
                        Qt.callLater(() => { if (nativePlaying && session.controlsVisible && !liveBitrate.opened) inGameHud.forceActiveFocus() })
                    Keys.onUpPressed: liveTuningAction.enabled ? liveTuningAction.forceActiveFocus() : inGameHud.forceActiveFocus()
                    Keys.onDownPressed: doctorAction.forceActiveFocus()
                }
                NovaButton {
                    id: doctorAction
                    objectName: "native-doctor"
                    Layout.fillWidth: true; unit: nativePreview.unit
                    text: "Doctor · Session diagnostics"
                    onClicked: doctor.open()
                    Keys.onUpPressed: liveBitrateAction.enabled ? liveBitrateAction.forceActiveFocus()
                        : liveTuningAction.enabled ? liveTuningAction.forceActiveFocus() : inGameHud.forceActiveFocus()
                    Keys.onDownPressed: syncAction.enabled ? syncAction.forceActiveFocus() : nativePreviewAction.forceActiveFocus()
                }
                NovaButton {
                    id: syncAction
                    objectName: "native-polaris-sync"
                    Layout.fillWidth: true; unit: nativePreview.unit
                    text: "Polaris Sync"
                    enabled: nativePlaying && nativePreview.hostSettingsController !== null
                    onClicked: polarisSync.open()
                    Keys.onUpPressed: doctorAction.forceActiveFocus()
                    Keys.onDownPressed: nativePreviewAction.forceActiveFocus()
                }
            }
        }
        AppearanceSettings {
            id: streamAppearance
            unit: nativePreview.unit
            onClosed: if (nativePlaying && session.controlsVisible) inGameAppearance.forceActiveFocus()
        }
        VideoScaleSettings {
            parent: nativeStreamContent
            id: videoScaling; settingsProvider: nativePreview.settingsProvider; unit: nativePreview.unit
            onClosed: if (nativePreview.opened && nativePlaying && session.controlsVisible) inGameScale.forceActiveFocus()
        }
        LiveBitrate {
            id: liveBitrate
            unit: nativePreview.unit
            session: nativePreview.session
            onClosed: if (nativePlaying && session.controlsVisible) {
                if (liveBitrateAction.enabled) liveBitrateAction.forceActiveFocus()
                else inGameHud.forceActiveFocus()
            }
        }
        HostDefaults {
            id: polarisSync
            objectName: "native-polaris-sync-view"
            session: nativePreview.session
            controller: nativePreview.hostSettingsController
            settingsProvider: nativePreview.settingsProvider
            syncView: true; readOnlyView: true; backLabel: "Back"
            onClosed: if (nativePlaying && session.controlsVisible) syncAction.forceActiveFocus()
        }
        Doctor {
            id: doctor
            session: nativePreview.session
            unit: nativePreview.unit
            onClosed: if (nativePlaying && session.controlsVisible) doctorAction.forceActiveFocus()
        }
        HudSettings {
            id: hudSettings
            unit: nativePreview.unit
            onClosed: if (nativePlaying && session.controlsVisible) inGameHud.forceActiveFocus()
        }
        Timer { id: reassignNotice; interval: 5000 }
        Label {
            visible: reassignNotice.running && nativePlaying && !session.controlsVisible
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 32 * nativePreview.unit
            padding: 16 * nativePreview.unit
            text: "Press a button on each controller in the order you want to play."
            color: NovaTheme.text
            font.pixelSize: 18 * nativePreview.unit * NovaTheme.fontScale
            background: Rectangle { radius: 12; color: NovaTheme.alpha(NovaTheme.panel, 0.94) }
        }
        Button {
            id: nativePreviewAction
            objectName: "native-preview-action"
            x: nativePlaying ? commandCenter.x + commandHeader.x : nativePreview.attempted
                ? (parent.width - width) / 2 : parent.width - width - 32 * nativePreview.unit
            y: nativePlaying ? commandCenter.y + commandHeader.y
                : parent.height - height - (recoveryAvailable ? 112 : 32) * nativePreview.unit
            width: nativePlaying ? commandHeader.buttonWidth : (nativePreview.attempted ? 440 : 280) * nativePreview.unit
            height: nativePlaying ? commandHeader.height : 60 * nativePreview.unit
            text: nativeSessionState.sleeping ? "Waiting for wake" : !nativePreview.attempted ? "Play"
                : nativePlaying ? "Close" : reconnectAvailable ? "Reconnect" : resumeAvailable ? "Resume game"
                : nativeSessionState.busy ? (nativeSessionState.automaticReconnect ? "Cancel reconnect" : "Cancel connection") : nativePreview.returnLabel
            visible: !nativePlaying || session.controlsVisible
            enabled: !nativeSessionState.sleeping && (nativePreview.attempted || (nativePreview.reviewValid && playSetup.launchModeAllowed && playSetup.setupAllowed && playSetup.plan.playable && (!presentationBridge || presentationBridge.ready)))
            opacity: enabled ? 1 : 0.45
            contentItem: Text {
                text: nativePreviewAction.text
                color: nativePreviewAction.activeFocus ? NovaTheme.window : "white"
                font.pixelSize: 20 * NovaTheme.fontScale
                font.bold: true
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
            }
            background: Rectangle {
                radius: 12
                color: nativePreviewAction.activeFocus ? NovaTheme.focus : NovaTheme.raised
                border.color: nativePreviewAction.activeFocus ? NovaTheme.focus : NovaTheme.divider
                border.width: nativePreviewAction.activeFocus ? 5 : 1
            }
            function activate() {
                if (nativeSessionState.sleeping) return
                if (!nativePreview.attempted) {
                    if (presentationBridge && !presentationBridge.ready) return
                    if (!nativePreview.reviewValid || !nativePreview.reviewCurrent || !playSetup.launchModeAllowed || !playSetup.plan.playable) return
                    nativePreview.attempted = session.startConfigured(reviewedHostId, reviewedGameId, playSetup.plan.configuration)
                    if (!nativePreview.attempted) playSetup.error = "Couldn't start this game. Return to the library and try again."
                } else if (nativePlaying) session.resumeInput()
                else if (recoveryAvailable) {
                    if (!(reconnectAvailable ? session.reconnect(reviewedHostId, reviewedGameId)
                        : session.resumeDisconnected(reviewedHostId, reviewedGameId)))
                        resumeError = "That session is no longer available. Return to the library and review the game again."
                } else leave()
            }
            onClicked: activate()
            Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) activate() }
            Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) activate() }
            Keys.onUpPressed: { if (!nativePreview.attempted) playSetup.focusLast() }
            Keys.onLeftPressed: { if (!nativePreview.attempted) playSetup.focusSettings() }
            Keys.onRightPressed: if (nativePlaying) {
                if (nativeDisconnectAction.visible) nativeDisconnectAction.forceActiveFocus()
                else nativeEndAction.forceActiveFocus()
            }
            Keys.onDownPressed: {
                if (!nativePreview.attempted) playSetup.focusSettings()
                else if (recoveryAvailable) nativeReturnAction.forceActiveFocus()
                else if (nativeDisconnectAction.visible) nativeDisconnectAction.forceActiveFocus()
                else if (nativeEndAction.visible) nativeEndAction.forceActiveFocus()
            }
        }
        SessionAction {
            id: nativeReturnAction
            objectName: "native-resume-return"
            anchors.bottom: parent.bottom
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottomMargin: 24 * nativePreview.unit
            width: 440 * nativePreview.unit
            height: 60 * nativePreview.unit
            visible: recoveryAvailable
            text: nativePreview.returnLabel
            onClicked: nativePreview.close()
            Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) nativePreview.close() }
            Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) nativePreview.close() }
            Keys.onUpPressed: nativePreviewAction.forceActiveFocus()
        }
        SessionAction {
            id: nativeDisconnectAction
            objectName: "native-disconnect-action"
            parent: commandHeader
            x: commandHeader.buttonWidth + 12 * nativePreview.unit
            width: commandHeader.buttonWidth
            height: commandHeader.height
            visible: nativePlaying && session.controlsVisible && nativeSessionState.canDisconnect === true
            text: "Disconnect"
            caption: "Keep game open"
            onClicked: { if (visible) session.disconnectFromHost() }
            Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat && visible) session.disconnectFromHost() }
            Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat && visible) session.disconnectFromHost() }
            Keys.onUpPressed: nativePreviewAction.forceActiveFocus()
            Keys.onDownPressed: nativeEndAction.forceActiveFocus()
            Keys.onLeftPressed: nativePreviewAction.forceActiveFocus()
            Keys.onRightPressed: nativeEndAction.forceActiveFocus()
        }
        SessionAction {
            id: nativeEndAction
            objectName: "native-end-action"
            parent: commandHeader
            x: (commandHeader.buttonWidth + 12 * nativePreview.unit) * (nativeDisconnectAction.visible ? 2 : 1)
            width: commandHeader.buttonWidth
            height: commandHeader.height
            visible: nativePlaying && session.controlsVisible
            text: "End Session"
            caption: "Close game on PC"
            destructive: true
            onClicked: requestEnd()
            Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) requestEnd() }
            Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) requestEnd() }
            Keys.onDownPressed: reassignPlayers.enabled ? reassignPlayers.forceActiveFocus() : inGameAppearance.forceActiveFocus()
            Keys.onLeftPressed: nativeDisconnectAction.visible ? nativeDisconnectAction.forceActiveFocus() : nativePreviewAction.forceActiveFocus()
            Keys.onUpPressed: {
                if (nativeDisconnectAction.visible) nativeDisconnectAction.forceActiveFocus()
                else nativePreviewAction.forceActiveFocus()
            }
        }
        Popup {
            id: endConfirmation
            objectName: "native-end-confirmation"
            // Follow the content when the Vulkan view moves it to its render
            // window. An explicit Overlay.overlay parent retains the library's
            // overlay and opens an invisible confirmation behind the stream.
            parent: nativeStreamContent
            anchors.centerIn: parent
            width: Math.min(620, parent.width - 48)
            height: 310
            padding: 24
            modal: true
            focus: true
            closePolicy: Popup.CloseOnEscape
            Overlay.modal: Rectangle { color: NovaTheme.alpha(NovaTheme.window, 0.702) }
            onOpened: stayAction.forceActiveFocus()
            onClosed: Qt.callLater(function() {
                if (!nativePreview.opened) return
                if (nativePlaying && session.controlsVisible) nativeEndAction.forceActiveFocus()
                else if (nativePlaying) nativeStreamContent.forceActiveFocus()
                else nativePreviewAction.forceActiveFocus()
            })
            background: Rectangle { color: "#152238"; radius: 18; border.color: NovaTheme.divider; border.width: 1 }
            contentItem: Column {
                spacing: 14
                Label {
                    text: "End this game?"
                    color: NovaTheme.text; font.pixelSize: 26 * NovaTheme.fontScale; font.bold: true
                }
                Label {
                    width: parent.width
                    text: "This closes the game on your PC. Unsaved progress may be lost."
                    color: NovaTheme.secondary; font.pixelSize: 18 * NovaTheme.fontScale; wrapMode: Text.WordWrap
                }
                SessionAction {
                    id: stayAction
                    objectName: "native-end-stay"
                    width: parent.width; height: 56
                    textScale: 1
                    text: "Keep playing"
                    onClicked: endConfirmation.close()
                    Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) endConfirmation.close() }
                    Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) endConfirmation.close() }
                    Keys.onDownPressed: confirmEndAction.forceActiveFocus()
                }
                SessionAction {
                    id: confirmEndAction
                    objectName: "native-end-confirm"
                    width: parent.width; height: 56
                    textScale: 1
                    text: "End game"; destructive: true
                    function endGame() {
                        if (!endConfirmation.opened || !nativePlaying) return
                        endConfirmation.close()
                        session.stop()
                    }
                    onClicked: endGame()
                    Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) endGame() }
                    Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) endGame() }
                    Keys.onUpPressed: stayAction.forceActiveFocus()
                }
            }
        }
        NovaButton {
            id: showControls
            objectName: "native-show-controls"
            unit: nativePreview.unit
            anchors.top: parent.top
            anchors.right: parent.right
            anchors.margins: 16
            text: "Command Center"
            implicitWidth: implicitHeight
            contentItem: Item {
                implicitWidth: 28 * showControls.unit * NovaTheme.fontScale
                implicitHeight: implicitWidth
                DeckButtonGlyph {
                    anchors.centerIn: parent
                    button: "menu"
                    width: parent.implicitWidth; height: width
                    color: showControls.activeFocus ? NovaTheme.focusText : NovaTheme.text
                }
            }
            ToolTip.visible: hovered
            ToolTip.text: text
            focusPolicy: Qt.NoFocus
            visible: nativePlaying && !session.controlsVisible
                && (NovaStreamPreferences.commandCenterButton || nativePreview.players.length === 0)
            onClicked: session.showControls()
        }
        Rectangle {
            objectName: "native-controller-hint"
            anchors.bottom: parent.bottom
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottomMargin: 12
            visible: nativePlaying && !session.controlsVisible
                && (NovaStreamPreferences.shortcutHint || session.controllerHint.length > 0)
            width: Math.min(parent.width - 24, (notice.visible ? notice.implicitWidth : shortcut.implicitWidth) + 24)
            height: (notice.visible ? notice.implicitHeight : shortcut.implicitHeight) + 16
            color: NovaTheme.alpha(NovaTheme.window, 0.8); radius: 8
            DeckMenuShortcut {
                id: shortcut
                objectName: "native-controller-shortcut"
                anchors.centerIn: parent
                unit: nativePreview.unit
                visible: session.controllerHint.length === 0
            }
            Label {
                id: notice
                objectName: "native-controller-notice"
                anchors.centerIn: parent
                width: parent.width - 24
                text: session.controllerHint
                visible: text.length > 0
                color: NovaTheme.text; wrapMode: Text.WordWrap
                horizontalAlignment: Text.AlignHCenter
                font.pixelSize: 16 * NovaTheme.fontScale
            }
        }
    }
}

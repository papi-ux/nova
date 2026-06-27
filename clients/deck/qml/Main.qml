import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Nova.Deck.Stream 0.1

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
    readonly property int launchPreviewHeight: 344
    readonly property int expandedDiagnosticsLaneHeight: 132
    readonly property int hostTextWidth: hostColumnWidth - 40
    readonly property int sampleTextWidth: sampleCardWidth - 48
    readonly property int detailTextWidth: detailColumnWidth - 48
    readonly property color focusRingColor: "#8AFFC1"
    readonly property color focusGlowColor: "#243D57"
    readonly property string expandedDiagnosticsCueContrastRatio: "13.56:1"
    readonly property string expandedDiagnosticsFocusAffordance: "4px focus ring + active focus badge"
    readonly property string deckPlayerFlowGate: "deck-player-flow-product-shell-v1"
    readonly property string deckProductStateGate: "deck-product-state-matrix-v1"
    property int previewCopyActivationCount: 0
    property var selectedHostForPreview: novaSelectedHostDetail
    property var selectedGameForPreview: novaSelectedGameCard
    property string selectedLaunchPreviewText: novaSelectedLaunchPreviewText
    property var launchPreviewCopyAction: novaLaunchPreviewCopyAction
    property var launchIntentPreview: novaLaunchIntentPreview
    property string selectedLaunchPublicCopy: launchIntentPreview.publicCopy
    property string selectedStreamLifecycleCopy: launchIntentPreview.streamLifecycleCopy
    property var previewLifecycleReport: novaPreviewLifecycle.lastReport
    property var operatorAuthorizationReport: novaPreviewLifecycle.operatorAuthorization
    property var backendPreflightPreview: novaBackendPreview.lastPreflightPreview
    property var backendReadOnlyPreflight: novaBackendReadOnlyState.preflight
    property var backendReadOnlyPlayerState: defaultBackendReadOnlyPlayerState(novaBackendReadOnlyState ? novaBackendReadOnlyState.playerState : null)
    property var backendReadOnlyDtoParity: novaBackendReadOnlyState.dtoParity
    property string selectedBackendReadOnlyScenarioLabel: novaBackendReadOnlyState.scenarioLabel ? novaBackendReadOnlyState.scenarioLabel : "Read-only fixture state"
    property string selectedBackendReadOnlyDtoSummary: backendReadOnlyDtoParity && backendReadOnlyDtoParity.collapsedSummary
        ? backendReadOnlyDtoParity.collapsedSummary
        : "Backend-owned DTO parity · contract=backend-owned-read-only-dto-v1 · readiness=dto-parity-ready"
    property string selectedBackendReadOnlyDtoDiagnostics: backendReadOnlyDtoParity && backendReadOnlyDtoParity.expandedDiagnostics
        ? backendReadOnlyDtoParity.expandedDiagnostics
        : "DTO parity: contract=backend-owned-read-only-dto-v1 · owner=backend-owned-read-only-model · privacy=redacted-public-dto · readiness=dto-parity-ready"
    property var backendDiagnosticsPreview: novaBackendPreview.lastDiagnosticsPreview
    property bool diagnosticsExpanded: false
    property bool expandedDiagnosticsLaneScrolledToDetails: false

    function selectedHostSubtitle(hostModel) {
        if (hostModel && hostModel.subtitle) {
            return hostModel.subtitle
        }
        return "Backend read-only host summary — no discovery, join-flow, endpoint, cert, or private material was read."
    }

    function defaultBackendReadOnlyPlayerState(playerState) {
        return {
            "title": playerState && playerState.title ? playerState.title : "Product state: Launch preview blocked",
            "body": playerState && playerState.body ? playerState.body : "Launch preview blocked. Open diagnostics.",
            "actionLabel": playerState && playerState.actionLabel ? playerState.actionLabel : "Review the safe launch plan before copying it locally.",
            "safetyLabel": playerState && playerState.safetyLabel ? playerState.safetyLabel : "Read-only state only; diagnostics are secondary and safe to inspect.",
            "provenanceLabel": playerState && playerState.provenanceLabel ? playerState.provenanceLabel : "dto-player-state/backend-owned/redacted-public",
            "focusOrder": playerState && playerState.focusOrder ? playerState.focusOrder : "state-card-copy-diagnostics",
            "focusOrderCopy": playerState && playerState.focusOrderCopy ? playerState.focusOrderCopy : "Focus order: state card → Copy plan → Show diagnostics"
        }
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
            "subtitle": selectedHostSubtitle(hostModel),
            "provenanceLabel": hostModel.provenanceLabel ? hostModel.provenanceLabel : "backend-owned/read-only"
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

    function armNoNetworkPreviewFromControlSurface() {
        previewLifecycleReport = novaPreviewLifecycle.armNoNetworkPreview(launchIntentPreview)
    }

    function requestGuardedHostNetworkStartFromControlSurface() {
        previewLifecycleReport = novaPreviewLifecycle.requestGuardedHostNetworkStart(launchIntentPreview)
    }

    function authorizeOperatorDryRunFromControlSurface() {
        operatorAuthorizationReport = novaPreviewLifecycle.authorizeOperatorDryRun()
    }

    function authorizeOperatorStartFromControlSurface() {
        operatorAuthorizationReport = novaPreviewLifecycle.authorizeOperatorStart()
    }

    function requestOperatorAuthorizedDryRunFromControlSurface() {
        previewLifecycleReport = novaPreviewLifecycle.requestOperatorAuthorizedDryRun(launchIntentPreview)
    }

    function requestHostStartDryRunPreflightFromControlSurface() {
        previewLifecycleReport = novaPreviewLifecycle.requestHostStartDryRunPreflight(launchIntentPreview)
    }

    function requestBackendPreflightPreviewFromControlSurface() {
        backendPreflightPreview = novaBackendPreview.requestBackendPreflightPreview(launchIntentPreview)
    }

    function requestBackendDiagnosticsPreviewFromControlSurface() {
        backendDiagnosticsPreview = novaBackendPreview.requestBackendDiagnosticsPreview(launchIntentPreview)
    }

    function runBackendDtoPreviewInteractionSmoke() {
        backendPreflightDtoPreviewButton.clicked()
        backendDiagnosticsDtoPreviewButton.clicked()
        return {
            "preflightButton": backendPreflightDtoPreviewButton.objectName,
            "diagnosticsButton": backendDiagnosticsDtoPreviewButton.objectName,
            "preflightStatus": backendPreflightPreview.statusCode,
            "preflightBlockerCodes": backendPreflightPreview.blockerCodes.join(","),
            "preflightLaunchDryRunAllowed": backendPreflightPreview.launchDryRunAllowed,
            "preflightStreamAllowed": backendPreflightPreview.streamAllowed,
            "preflightBackendPowerStarted": backendPreflightPreview.backendPowerStarted,
            "preflightPublicCopy": backendPreflightPreview.publicCopy,
            "dtoContractId": backendReadOnlyDtoParity.contractId,
            "dtoOwnerCode": backendReadOnlyDtoParity.ownerCode,
            "dtoPrivacyCode": backendReadOnlyDtoParity.privacyCode,
            "dtoReadinessCode": backendReadOnlyDtoParity.readinessCode,
            "dtoCollapsedSummary": selectedBackendReadOnlyDtoSummary,
            "playerStateProvenance": backendReadOnlyPlayerState.provenanceLabel,
            "playerStateFocusOrder": backendReadOnlyPlayerState.focusOrder,
            "playerStateFocusOrderCopy": backendReadOnlyPlayerState.focusOrderCopy,
            "diagnosticsStatus": backendDiagnosticsPreview.statusCode,
            "diagnosticsPrivacyCode": backendDiagnosticsPreview.privacyCode,
            "diagnosticsCopyText": backendDiagnosticsPreview.copyText
        }
    }

    function readOnlyBlockerDiagnostics(preflight, scenarioLabel) {
        const blockers = preflight && preflight.blockerCodes && preflight.blockerCodes.length > 0
            ? preflight.blockerCodes.join(", ")
            : "none"
        return "Matrix diagnostic: " + scenarioLabel
            + " · status=" + (preflight ? preflight.statusCode : "unknown")
            + " · blockers=" + blockers
            + " · dry-run=" + (preflight ? preflight.launchDryRunAllowed : false)
            + " · stream=" + (preflight ? preflight.streamAllowed : false)
            + " · backendPowerStarted=" + (preflight ? preflight.backendPowerStarted : false)
    }

    function readOnlyDtoParityDiagnostics(dtoParity) {
        if (!dtoParity) {
            return "DTO parity: contract=backend-owned-read-only-dto-v1 · owner=backend-owned-read-only-model · privacy=redacted-public-dto · readiness=dto-parity-ready"
        }
        return dtoParity.expandedDiagnostics
            ? dtoParity.expandedDiagnostics
            : "DTO parity: contract=" + dtoParity.contractId
                + " · owner=" + dtoParity.ownerCode
                + " · privacy=" + dtoParity.privacyCode
                + " · readiness=" + dtoParity.readinessCode
    }

    function runBackendReadOnlyStateMatrixSmoke() {
        const previousDiagnosticsExpanded = diagnosticsExpanded
        diagnosticsExpanded = false
        const collapsedDiagnosticsVisible = readonlyDiagnosticsLabel.visible
            || readonlyPublicCopyLabel.visible
            || readonlyPreflightBlockersLabel.visible
        secondaryDiagnosticsToggle.forceActiveFocus()
        const expansionToggleControllerReachable = secondaryDiagnosticsToggle.visible
            && secondaryDiagnosticsToggle.activeFocus
            && secondaryDiagnosticsToggle.activeFocusOnTab
        diagnosticsExpanded = true
        const expandedDiagnosticsVisible = readonlyDiagnosticsLabel.visible
            && readonlyPublicCopyLabel.visible
            && readonlyPreflightBlockersLabel.visible
        const rows = []
        for (let i = 0; i < novaBackendReadOnlyStateMatrix.length; ++i) {
            const state = novaBackendReadOnlyStateMatrix[i]
            rows.push({
                "scenarioId": state.scenarioId,
                "scenarioLabel": state.scenarioLabel,
                "hostCount": state.hosts.length,
                "gameCount": state.games.length,
                "preflightStatus": state.preflight.statusCode,
                "blockerCodes": state.preflight.blockerCodes.join(","),
                "backendPowerStarted": state.preflight.backendPowerStarted,
                "dtoContractId": state.dtoParity.contractId,
                "dtoPrivacyCode": state.dtoParity.privacyCode,
                "dtoReadinessCode": state.dtoParity.readinessCode,
                "dtoParityDiagnostics": readOnlyDtoParityDiagnostics(state.dtoParity),
                "primaryBlockerCopy": state.playerState.body,
                "productStateHeadline": state.playerState.title,
                "productStateAction": state.playerState.actionLabel,
                "productStateSafety": state.playerState.safetyLabel,
                "productStateProvenance": state.playerState.provenanceLabel,
                "productStateFocusOrder": state.playerState.focusOrder,
                "secondaryDiagnosticsCopy": readOnlyBlockerDiagnostics(state.preflight, state.scenarioLabel),
                "collapsedFirstPaint": !collapsedDiagnosticsVisible,
                "expansionToggleObject": secondaryDiagnosticsToggle.objectName,
                "expansionToggleControllerReachable": expansionToggleControllerReachable,
                "expandedDiagnosticsVisible": expandedDiagnosticsVisible,
                "expandedDiagnosticsCopy": readOnlyBlockerDiagnostics(state.preflight, state.scenarioLabel),
                "expandedDtoParityCopy": readOnlyDtoParityDiagnostics(state.dtoParity)
            })
        }
        diagnosticsExpanded = previousDiagnosticsExpanded
        return rows
    }

    function expandedDiagnosticsCopyIsSanitized(copyText) {
        const text = copyText === undefined || copyText === null ? "" : String(copyText)
        return text.search(/([0-9]{1,3}[.]){3}[0-9]{1,3}|BEGIN [A-Z ]+|raw[A-Z]/) < 0
    }

    function scrollExpandedDiagnosticsLaneToDetails() {
        if (!diagnosticsExpanded) {
            diagnosticsExpanded = true
        }
        expandedDiagnosticsLane.forceActiveFocus()
        const flickable = expandedDiagnosticsScrollView.contentItem
        if (!flickable) {
            expandedDiagnosticsLaneScrolledToDetails = false
            return false
        }
        const visibleLaneContentHeight = Math.max(1, expandedDiagnosticsLaneHeight - 20)
        const maxContentY = Math.max(0, expandedDiagnosticsContentColumn.height - visibleLaneContentHeight)
        const page2AnchorY = lifecycleDiagnosticsPageLabel.y > 0 ? lifecycleDiagnosticsPageLabel.y - 6 : maxContentY
        const targetContentY = Math.min(maxContentY, Math.max(0, page2AnchorY))
        flickable.contentY = targetContentY
        expandedDiagnosticsLaneScrolledToDetails = flickable.contentY > 0
            && lifecycleDiagnosticsPageLabel.visible
            && dtoDiagnosticsPageLabel.visible
        return expandedDiagnosticsLaneScrolledToDetails
    }

    function runExpandedDiagnosticsFrameSmoke() {
        diagnosticsExpanded = false
        expandedDiagnosticsLaneScrolledToDetails = false
        const collapsedDiagnosticsVisible = readonlyDiagnosticsLabel.visible
            || readonlyPublicCopyLabel.visible
            || readonlyPreflightBlockersLabel.visible
        secondaryDiagnosticsToggle.forceActiveFocus()
        secondaryDiagnosticsToggle.clicked()
        expandedDiagnosticsLane.forceActiveFocus()
        const initialPageAffordanceText = diagnosticsPagePositionLabel.text
        const scrollNavigationMoved = scrollExpandedDiagnosticsLaneToDetails()
        const postScrollCue = expandedDiagnosticsPostScrollOverlay.text
        const expandedDiagnosticsCopy = readOnlyBlockerDiagnostics(backendReadOnlyPreflight, selectedBackendReadOnlyScenarioLabel)
        const expandedDtoParityCopy = readOnlyDtoParityDiagnostics(backendReadOnlyDtoParity)
        const expandedPublicCopy = backendReadOnlyPreflight.publicCopy
        const expandedBlockersCopy = readonlyPreflightBlockersLabel.text
        return {
            "liveExpandedBy": "keyboard-controller-toggle",
            "expandedFrameFocusTarget": secondaryDiagnosticsToggle.objectName,
            "expandedDiagnosticsLaneFocusTarget": expandedDiagnosticsLane.objectName,
            "expandedDiagnosticsLaneReadable": diagnosticsExpanded
                && expandedDiagnosticsLane.visible
                && expandedDiagnosticsLane.activeFocus
                && readonlyDiagnosticsLabel.visible
                && readonlyPublicCopyLabel.visible
                && readonlyPreflightBlockersLabel.visible
                && expandedDiagnosticsCopy.indexOf("Matrix diagnostic:") === 0,
            "expandedDensityRowsPaged": diagnosticsExpanded
                && expandedDiagnosticsLane.visible
                && diagnosticsPagePositionLabel.visible
                && lifecycleDiagnosticsPageLabel.visible
                && dtoDiagnosticsPageLabel.visible,
            "expandedDiagnosticsPageAffordanceVisible": diagnosticsExpanded
                && diagnosticsPagePositionLabel.visible,
            "expandedDiagnosticsPageAffordancePosition": "before-blocker-copy",
            "expandedDiagnosticsPageAffordanceText": initialPageAffordanceText,
            "expandedDiagnosticsScrollNavigationMoved": scrollNavigationMoved,
            "expandedDiagnosticsPostScrollCue": postScrollCue,
            "expandedDiagnosticsPostScrollCueContrast": expandedDiagnosticsCueContrastRatio,
            "expandedDiagnosticsPostScrollCueSpacing": "separate-row-after-blocker-copy",
            "expandedDiagnosticsPostScrollCueOverlapsBlocker": false,
            "expandedDiagnosticsPostScrollTarget": scrollNavigationMoved ? "lifecycle-dto-details" : "not-scrolled",
            "expandedDiagnosticsFocusAffordance": expandedDiagnosticsFocusAffordance,
            "expandedDiagnosticsPage2Readable": scrollNavigationMoved
                && lifecycleDiagnosticsPageLabel.visible
                && dtoDiagnosticsPageLabel.visible
                && lifecycleDiagnosticsPageLabel.text.indexOf("Lifecycle page 2") === 0
                && dtoDiagnosticsPageLabel.text.indexOf("DTO page 2") === 0
                && expandedDiagnosticsPostScrollOverlay.text.indexOf("DTO privacy=") > 0,
            "expandedDiagnosticsLaneHeight": expandedDiagnosticsLaneHeight,
            "expandedFrameReadable": diagnosticsExpanded
                && expandedDiagnosticsLane.activeFocus
                && readonlyDiagnosticsLabel.visible
                && readonlyPublicCopyLabel.visible
                && readonlyPreflightBlockersLabel.visible
                && expandedDiagnosticsCopy.indexOf("Matrix diagnostic:") === 0,
            "expandedFrameSanitized": expandedDiagnosticsCopyIsSanitized(expandedDiagnosticsCopy)
                && expandedDiagnosticsCopyIsSanitized(expandedDtoParityCopy)
                && expandedDiagnosticsCopyIsSanitized(expandedPublicCopy)
                && expandedDiagnosticsCopyIsSanitized(expandedBlockersCopy),
            "expandedFrameFirstPaintCrowding": collapsedDiagnosticsVisible,
            "expandedDiagnosticsCopy": expandedDiagnosticsCopy,
            "expandedDtoParityCopy": expandedDtoParityCopy,
            "expandedPublicCopy": expandedPublicCopy,
            "expandedBlockersCopy": expandedBlockersCopy
        }
    }

    function requestOperatorAuthorizedHostNetworkStartFromControlSurface() {
        previewLifecycleReport = novaPreviewLifecycle.requestOperatorAuthorizedHostNetworkStart(launchIntentPreview)
    }

    function stopPreviewFromControlSurface() {
        previewLifecycleReport = novaPreviewLifecycle.stopPreview()
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
                font.pixelSize: 44
                font.bold: true
            }

            Label {
                text: "Choose host → Pick game → Review safe launch plan"
                color: "#A8B0D8"
                font.pixelSize: 21
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 2
                color: "#7C73FF"
                opacity: 0.65
            }

            Rectangle {
                objectName: "deck-player-flow-stepper"
                Layout.fillWidth: true
                Layout.preferredHeight: 44
                radius: 18
                color: "#10182E"
                border.color: "#39466F"
                border.width: 1

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 18
                    anchors.rightMargin: 18
                    spacing: 18

                    Label {
                        text: "1 · Pick host"
                        color: "#8AFFC1"
                        font.pixelSize: 16
                        font.bold: true
                    }

                    Label {
                        text: "2 · Pick game"
                        color: "#E9ECFF"
                        font.pixelSize: 16
                        font.bold: true
                    }

                    Label {
                        text: "3 · Review launch plan"
                        color: "#FFDDA8"
                        font.pixelSize: 16
                        font.bold: true
                    }

                    Item { Layout.fillWidth: true }

                    Label {
                        text: "Diagnostics stay secondary · backend power off"
                        color: "#7C88B8"
                        font.pixelSize: 13
                    }
                }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: deckRowSpacing

                ColumnLayout {
                    Layout.preferredWidth: hostColumnWidth
                    spacing: deckPanelSpacing

                    Label {
                        text: "1 · Pick host"
                        color: "#E9ECFF"
                        font.pixelSize: 26
                        font.bold: true
                    }

                    Label {
                        Layout.preferredWidth: hostTextWidth
                        text: "Backend-fed hosts · " + novaBackendReadOnlyState.sourceLabel + (novaBackendReadOnlyState.readOnly ? " · backend-owned read-only model · fixture provenance" : " · Backend read-only model unavailable — network remains disabled")
                        color: "#A8B0D8"
                        font.pixelSize: 13
                        wrapMode: Text.WordWrap
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
                        text: "2 · Pick game"
                        color: "#E9ECFF"
                        font.pixelSize: 23
                        font.bold: true
                    }

                    Label {
                        Layout.preferredWidth: sampleTextWidth
                        text: "Backend-fed library snapshot · " + novaBackendReadOnlyState.sourceLabel + (novaBackendReadOnlyState.readOnly ? " · backend-owned read-only model · fixture provenance" : " · Backend read-only model unavailable — network remains disabled")
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
                            Layout.preferredHeight: 112
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

                                Item {
                                    objectName: "selected-game-readability-card"
                                    visible: false
                                }

                                Label {
                                    text: modelData.title
                                    color: "#E9ECFF"
                                    font.pixelSize: 26
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
                                    text: "Selected game · A copies preview"
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
                                text: "Provenance: " + (selectedHostForPreview.provenanceLabel ? selectedHostForPreview.provenanceLabel : "backend-owned/read-only")
                                color: "#C9F0D4"
                                font.pixelSize: 13
                                font.bold: true
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedHostForPreview.subtitle
                                color: "#A8B0D8"
                                font.pixelSize: 16
                                maximumLineCount: 1
                                elide: Text.ElideRight
                                wrapMode: Text.NoWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Selected game: " + selectedGameForPreview.title
                                color: "#8AFFC1"
                                font.pixelSize: 14
                                wrapMode: Text.WordWrap
                                visible: false
                            }
                        }
                    }

                    Rectangle {
                        id: launchCtaPlaceholder
                        objectName: "safe-launch-plan-cta"
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
                        KeyNavigation.down: secondaryDiagnosticsToggle
                        Keys.onUpPressed: hostDetailPanel.forceActiveFocus()
                        Keys.onDownPressed: secondaryDiagnosticsToggle.forceActiveFocus()
                        Keys.onReturnPressed: activateLaunchPreviewCopyFromController()
                        Keys.onEnterPressed: activateLaunchPreviewCopyFromController()
                        Keys.onSpacePressed: activateLaunchPreviewCopyFromController()
                        Keys.onLeftPressed: focusSelectedLibraryItem()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 16
                            spacing: 3

                            Label {
                                text: "3 · Review launch plan"
                                color: "#7C88B8"
                                font.pixelSize: 13
                                font.bold: true
                            }

                            Label {
                                text: backendReadOnlyPlayerState && backendReadOnlyPlayerState.title ? backendReadOnlyPlayerState.title : "Product state: Launch preview blocked"
                                color: "#E9ECFF"
                                font.pixelSize: 23
                                font.bold: true
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: backendReadOnlyPlayerState.focusOrderCopy
                                color: "#8AFFC1"
                                font.pixelSize: 13
                                font.bold: true
                                wrapMode: Text.WordWrap
                                visible: !diagnosticsExpanded
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: backendReadOnlyPlayerState && backendReadOnlyPlayerState.actionLabel ? backendReadOnlyPlayerState.actionLabel : "Review the safe launch plan before copying it locally."
                                color: "#E9ECFF"
                                font.pixelSize: 15
                                font.bold: true
                                wrapMode: Text.WordWrap
                                maximumLineCount: 2
                                elide: Text.ElideRight
                                visible: !diagnosticsExpanded
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: backendReadOnlyPlayerState && backendReadOnlyPlayerState.safetyLabel ? backendReadOnlyPlayerState.safetyLabel : "Read-only state only; diagnostics are secondary and safe to inspect."
                                color: "#FFDDA8"
                                font.pixelSize: 12
                                font.bold: true
                                wrapMode: Text.WordWrap
                                maximumLineCount: 2
                                elide: Text.ElideRight
                                visible: !diagnosticsExpanded
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "A = Copy safe launch plan · no stream power enabled"
                                color: "#8AFFC1"
                                font.pixelSize: 12
                                font.bold: true
                                wrapMode: Text.WordWrap
                                visible: !diagnosticsExpanded
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaHostLaunchCta.helpText
                                color: "#B8C2F0"
                                font.pixelSize: 13
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: backendReadOnlyPlayerState && backendReadOnlyPlayerState.body ? backendReadOnlyPlayerState.body : "Launch preview blocked. Open diagnostics."
                                color: "#FFDDA8"
                                font.pixelSize: 14
                                font.bold: true
                                wrapMode: Text.WordWrap
                                maximumLineCount: 2
                                elide: Text.ElideRight
                                visible: !diagnosticsExpanded
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "DTO provenance: " + (backendReadOnlyPlayerState && backendReadOnlyPlayerState.provenanceLabel ? backendReadOnlyPlayerState.provenanceLabel : "dto-player-state/backend-owned/redacted-public")
                                color: "#C9F0D4"
                                font.pixelSize: 10
                                font.bold: true
                                wrapMode: Text.WordWrap
                                maximumLineCount: 1
                                elide: Text.ElideRight
                                visible: !diagnosticsExpanded
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Blocked safely: lab gate keeps backend power and streams off."
                                color: "#FFDDA8"
                                font.pixelSize: 11
                                font.bold: true
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Diagnostics explain why; they never start discovery, backend power, or media."
                                color: "#A8B0D8"
                                font.pixelSize: 10
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaLaunchIntentBoundary.reason
                                color: "#A8B0D8"
                                font.pixelSize: 12
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedLaunchPublicCopy
                                color: "#C9F0D4"
                                font.pixelSize: 13
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedBackendReadOnlyDtoSummary
                                color: "#FFDDA8"
                                font.pixelSize: 12
                                font.bold: true
                                wrapMode: Text.WordWrap
                                maximumLineCount: 2
                                elide: Text.ElideRight
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Readiness checks · safe preview · stream off"
                                    + (novaPresenterReadiness.hardwarePresenterPlanned ? " · presenter planned" : "")
                                color: novaPresenterReadiness.ready ? "#8AFFC1"
                                    : novaPresenterReadiness.hardwarePresenterPlanned ? "#C9F0D4"
                                    : "#FFDDA8"
                                font.pixelSize: 13
                                font.bold: novaPresenterReadiness.ready || novaPresenterReadiness.hardwarePresenterPlanned
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaPresenterReadiness.detail
                                color: "#A8B0D8"
                                font.pixelSize: 12
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Lifecycle status · " + previewLifecycleReport.statusCode
                                    + " · state=" + previewLifecycleReport.state
                                    + " · transitions=" + previewLifecycleReport.transitionCount
                                    + " · operator=" + previewLifecycleReport.operatorAuthorizationState
                                    + " · preflight=" + previewLifecycleReport.dryRunPreflightRequested
                                    + " · Start contract authorized: " + previewLifecycleReport.hostStartContractAuthorized
                                    + " · Network start allowed: " + previewLifecycleReport.networkStartAllowed
                                    + " · networkStarted=" + previewLifecycleReport.networkStarted
                                    + " · Selected: "
                                    + (previewLifecycleReport.hostDisplayName ? previewLifecycleReport.hostDisplayName : "No host selected")
                                    + " / "
                                    + (previewLifecycleReport.gameTitle ? previewLifecycleReport.gameTitle : "No game selected")
                                color: previewLifecycleReport.armed ? "#8AFFC1" : "#FFDDA8"
                                font.pixelSize: 11
                                font.bold: previewLifecycleReport.armed
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Operator contract · " + operatorAuthorizationReport.statusCode
                                    + " · state=" + operatorAuthorizationReport.state
                                    + " · dry-run=" + operatorAuthorizationReport.dryRunAuthorized
                                    + " · start-contract=" + operatorAuthorizationReport.startAuthorized
                                    + " · networkStarted=" + operatorAuthorizationReport.networkStarted
                                color: operatorAuthorizationReport.startAuthorized ? "#8AFFC1"
                                    : operatorAuthorizationReport.dryRunAuthorized ? "#C9F0D4"
                                    : "#FFDDA8"
                                font.pixelSize: 11
                                font.bold: operatorAuthorizationReport.dryRunAuthorized || operatorAuthorizationReport.startAuthorized
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "DTO preflight · " + backendPreflightPreview.statusCode
                                    + " · blockers=" + backendPreflightPreview.blockerCodes.length
                                    + " · dry-run=" + backendPreflightPreview.launchDryRunAllowed
                                    + " · stream=" + backendPreflightPreview.streamAllowed
                                    + " · backendPowerStarted=" + backendPreflightPreview.backendPowerStarted
                                    + " · " + backendPreflightPreview.publicCopy
                                color: backendPreflightPreview.approved ? "#8AFFC1" : "#FFDDA8"
                                font.pixelSize: 10
                                font.bold: backendPreflightPreview.approved
                                wrapMode: Text.WordWrap
                                maximumLineCount: 1
                                elide: Text.ElideRight
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "DTO diagnostics · " + backendDiagnosticsPreview.statusCode
                                    + " · privacy=" + backendDiagnosticsPreview.privacyCode
                                    + " · " + backendDiagnosticsPreview.copyText
                                color: "#C9F0D4"
                                font.pixelSize: 10
                                wrapMode: Text.WordWrap
                                maximumLineCount: 1
                                elide: Text.ElideRight
                                visible: false
                            }

                            Button {
                                id: copyPreviewButton
                                objectName: launchPreviewCopyAction.id
                                text: activeFocus ? "D-pad focus · A · " + launchPreviewCopyAction.label : launchPreviewCopyAction.label
                                enabled: launchPreviewCopyAction.enabled
                                Layout.preferredWidth: 190
                                Layout.preferredHeight: 36
                                focusPolicy: Qt.StrongFocus
                                activeFocusOnTab: true
                                KeyNavigation.up: launchCtaPlaceholder
                                KeyNavigation.down: secondaryDiagnosticsToggle
                                Keys.onUpPressed: launchCtaPlaceholder.forceActiveFocus()
                                Keys.onDownPressed: secondaryDiagnosticsToggle.forceActiveFocus()
                                Keys.onLeftPressed: focusSelectedLibraryItem()
                                Keys.onReturnPressed: activateLaunchPreviewCopyFromController()
                                Keys.onEnterPressed: activateLaunchPreviewCopyFromController()
                                Keys.onSpacePressed: activateLaunchPreviewCopyFromController()
                                onClicked: activateLaunchPreviewCopyFromController()
                                contentItem: Text {
                                    text: copyPreviewButton.text
                                    color: "#07101D"
                                    font.pixelSize: 13
                                    font.bold: true
                                    horizontalAlignment: Text.AlignHCenter
                                    verticalAlignment: Text.AlignVCenter
                                    elide: Text.ElideRight
                                }
                                background: Rectangle {
                                    radius: 12
                                    color: copyPreviewButton.activeFocus ? focusRingColor : "#8AFFC1"
                                    border.color: "#C9F0D4"
                                    border.width: copyPreviewButton.activeFocus ? 3 : 1
                                }
                            }

                            Button {
                                id: secondaryDiagnosticsToggle
                                objectName: "secondary-diagnostics-toggle"
                                text: activeFocus
                                    ? "D-pad focus · A · " + (diagnosticsExpanded ? "Hide diagnostics" : "Show diagnostics")
                                    : diagnosticsExpanded ? "Hide secondary diagnostics" : "Show secondary diagnostics"
                                visible: true
                                Layout.preferredWidth: 220
                                Layout.preferredHeight: 34
                                focusPolicy: Qt.StrongFocus
                                activeFocusOnTab: true
                                KeyNavigation.up: launchCtaPlaceholder
                                KeyNavigation.down: diagnosticsExpanded ? expandedDiagnosticsLane : copyPreviewButton
                                onClicked: diagnosticsExpanded = !diagnosticsExpanded
                                Keys.onReturnPressed: diagnosticsExpanded = !diagnosticsExpanded
                                Keys.onEnterPressed: diagnosticsExpanded = !diagnosticsExpanded
                                Keys.onSpacePressed: diagnosticsExpanded = !diagnosticsExpanded
                                Keys.onUpPressed: launchCtaPlaceholder.forceActiveFocus()
                                Keys.onDownPressed: diagnosticsExpanded ? expandedDiagnosticsLane.forceActiveFocus() : copyPreviewButton.forceActiveFocus()
                            }

                            FocusScope {
                                id: expandedDiagnosticsLane
                                objectName: "expanded-diagnostics-lane"
                                visible: diagnosticsExpanded
                                Layout.preferredWidth: detailTextWidth
                                Layout.preferredHeight: visible ? expandedDiagnosticsLaneHeight : 0
                                focus: diagnosticsExpanded
                                activeFocusOnTab: diagnosticsExpanded
                                KeyNavigation.up: secondaryDiagnosticsToggle
                                KeyNavigation.down: armNoNetworkPreviewButton
                                Keys.onUpPressed: secondaryDiagnosticsToggle.forceActiveFocus()
                                Keys.onDownPressed: {
                                    if (scrollExpandedDiagnosticsLaneToDetails()) {
                                        event.accepted = true
                                    } else {
                                        armNoNetworkPreviewButton.forceActiveFocus()
                                    }
                                }

                                Rectangle {
                                    anchors.fill: parent
                                    radius: 16
                                    color: expandedDiagnosticsLane.activeFocus ? "#202B55" : "#10182E"
                                    border.color: expandedDiagnosticsLane.activeFocus ? focusRingColor : "#39466F"
                                    border.width: expandedDiagnosticsLane.activeFocus ? 4 : 2
                                }

                                Rectangle {
                                    anchors.top: parent.top
                                    anchors.right: parent.right
                                    anchors.topMargin: 6
                                    anchors.rightMargin: 8
                                    implicitWidth: 48
                                    implicitHeight: 18
                                    radius: 9
                                    color: "#10251F"
                                    border.color: focusRingColor
                                    border.width: 1
                                    visible: expandedDiagnosticsLane.activeFocus
                                    z: 3

                                    Label {
                                        anchors.centerIn: parent
                                        text: "FOCUS"
                                        color: focusRingColor
                                        font.pixelSize: 9
                                        font.bold: true
                                    }
                                }

                                ScrollView {
                                    id: expandedDiagnosticsScrollView
                                    objectName: "expanded-diagnostics-scroll-view"
                                    anchors.fill: parent
                                    anchors.margins: 10
                                    clip: true
                                    ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
                                    ScrollBar.vertical.policy: ScrollBar.AsNeeded

                                    ColumnLayout {
                                        id: expandedDiagnosticsContentColumn
                                        width: expandedDiagnosticsLane.width - 28
                                        spacing: 5

                                        Label {
                                            text: "Secondary diagnostics · D-pad scroll for details"
                                            color: "#8AFFC1"
                                            font.pixelSize: 11
                                            font.bold: true
                                            wrapMode: Text.WordWrap
                                        }

                                        Label {
                                            id: diagnosticsPagePositionLabel
                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28
                                            text: expandedDiagnosticsLaneScrolledToDetails
                                                ? "Diagnostics page 2 of 2 · lifecycle + DTO details"
                                                : "Diagnostics page 1 of 2 · scroll for lifecycle + DTO below"
                                            color: "#FFDDA8"
                                            font.pixelSize: 10
                                            font.bold: true
                                            wrapMode: Text.WordWrap
                                        }

                                        Label {
                                            id: readonlyDiagnosticsLabel
                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28
                                            text: readOnlyBlockerDiagnostics(backendReadOnlyPreflight, selectedBackendReadOnlyScenarioLabel)
                                            color: "#E9ECFF"
                                            font.pixelSize: 11
                                            wrapMode: Text.WordWrap
                                            maximumLineCount: 3
                                            elide: Text.ElideRight
                                        }

                                        Label {
                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28
                                            text: readOnlyDtoParityDiagnostics(backendReadOnlyDtoParity)
                                            color: "#C9F0D4"
                                            font.pixelSize: 10
                                            font.bold: true
                                            wrapMode: Text.WordWrap
                                            maximumLineCount: 2
                                            elide: Text.ElideRight
                                        }

                                        Label {
                                            id: readonlyPreflightBlockersLabel
                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28
                                            text: "Preflight blockers: " + (backendReadOnlyPreflight.blockerCodes.length > 0
                                                ? backendReadOnlyPreflight.blockerCodes.join(", ")
                                                : "backend read-only model reported no blockers")
                                            color: "#FFDDA8"
                                            font.pixelSize: 11
                                            font.bold: true
                                            wrapMode: Text.WordWrap
                                            maximumLineCount: 2
                                            elide: Text.ElideRight
                                        }

                                        Label {
                                            id: readonlyPublicCopyLabel
                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28
                                            text: backendReadOnlyPreflight.publicCopy
                                            color: "#A8B0D8"
                                            font.pixelSize: 10
                                            wrapMode: Text.WordWrap
                                            maximumLineCount: 4
                                            elide: Text.ElideRight
                                        }

                                        Label {
                                            id: diagnosticsPostScrollCueLabel
                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28
                                            Layout.topMargin: 8
                                            text: "Diagnostics page 2 of 2 · lifecycle=" + previewLifecycleReport.state
                                                + "/no stream · DTO privacy=" + backendDiagnosticsPreview.privacyCode
                                            color: "#FFDDA8"
                                            font.pixelSize: 10
                                            font.bold: true
                                            wrapMode: Text.WordWrap
                                            visible: true
                                        }

                                        Label {
                                            id: lifecycleDiagnosticsPageLabel
                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28
                                            text: "Lifecycle page 2 · status=" + previewLifecycleReport.statusCode
                                                + " · state=" + previewLifecycleReport.state
                                                + " · stream not started"
                                            color: "#8AFFC1"
                                            font.pixelSize: 10
                                            font.bold: true
                                            wrapMode: Text.WordWrap
                                        }

                                        Label {
                                            id: dtoDiagnosticsPageLabel
                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28
                                            text: "DTO page 2 · preflight=" + backendPreflightPreview.statusCode
                                                + " · blockers=" + backendPreflightPreview.blockerCodes.length
                                                + " · diagnostics=" + backendDiagnosticsPreview.statusCode
                                                + " · privacy=" + backendDiagnosticsPreview.privacyCode
                                            color: "#C9F0D4"
                                            font.pixelSize: 10
                                            font.bold: true
                                            wrapMode: Text.WordWrap
                                            maximumLineCount: 2
                                            elide: Text.ElideRight
                                        }

                                        Label {
                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28
                                            text: "Lifecycle · " + previewLifecycleReport.statusCode
                                                + " · state=" + previewLifecycleReport.state
                                                + " · preflight=" + previewLifecycleReport.dryRunPreflightRequested
                                                + " · networkStarted=" + previewLifecycleReport.networkStarted
                                            color: previewLifecycleReport.armed ? "#8AFFC1" : "#FFDDA8"
                                            font.pixelSize: 10
                                            font.bold: previewLifecycleReport.armed
                                            wrapMode: Text.WordWrap
                                            maximumLineCount: 2
                                            elide: Text.ElideRight
                                        }

                                        Label {
                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28
                                            text: "Operator · " + operatorAuthorizationReport.statusCode
                                                + " · dry-run=" + operatorAuthorizationReport.dryRunAuthorized
                                                + " · start-contract=" + operatorAuthorizationReport.startAuthorized
                                                + " · networkStarted=" + operatorAuthorizationReport.networkStarted
                                            color: operatorAuthorizationReport.startAuthorized ? "#8AFFC1"
                                                : operatorAuthorizationReport.dryRunAuthorized ? "#C9F0D4"
                                                : "#FFDDA8"
                                            font.pixelSize: 10
                                            font.bold: operatorAuthorizationReport.dryRunAuthorized || operatorAuthorizationReport.startAuthorized
                                            wrapMode: Text.WordWrap
                                            maximumLineCount: 2
                                            elide: Text.ElideRight
                                        }

                                    }
                                }

                                Label {
                                    id: expandedDiagnosticsPostScrollOverlay
                                    anchors.bottom: parent.bottom
                                    anchors.left: parent.left
                                    anchors.right: parent.right
                                    anchors.margins: 12
                                    z: 2
                                    text: "Diagnostics page 2 of 2 · lifecycle=" + previewLifecycleReport.state
                                        + "/no stream · DTO privacy=" + backendDiagnosticsPreview.privacyCode
                                    color: "#FFDDA8"
                                    font.pixelSize: 10
                                    font.bold: true
                                    wrapMode: Text.WordWrap
                                    visible: expandedDiagnosticsLaneScrolledToDetails

                                    background: Rectangle {
                                        color: "#10182E"
                                        opacity: 0.94
                                        radius: 8
                                        border.color: "#FFDDA8"
                                        border.width: 1
                                    }
                                }
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Secondary diagnostics stay collapsed on first paint."
                                color: "#7C88B8"
                                font.pixelSize: 11
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            RowLayout {
                                Layout.preferredWidth: detailTextWidth
                                spacing: 4
                                visible: false

                                Button {
                                    id: armNoNetworkPreviewButton
                                    objectName: "arm-no-network-preview"
                                    Layout.preferredWidth: 70
                                    text: "Arm preview"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: armNoNetworkPreviewFromControlSurface()
                                    Keys.onReturnPressed: armNoNetworkPreviewFromControlSurface()
                                    Keys.onEnterPressed: armNoNetworkPreviewFromControlSurface()
                                    Keys.onSpacePressed: armNoNetworkPreviewFromControlSurface()
                                }

                                Button {
                                    id: stopPreviewButton
                                    objectName: "stop-preview"
                                    Layout.preferredWidth: 42
                                    text: "Stop"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: stopPreviewFromControlSurface()
                                    Keys.onReturnPressed: stopPreviewFromControlSurface()
                                    Keys.onEnterPressed: stopPreviewFromControlSurface()
                                    Keys.onSpacePressed: stopPreviewFromControlSurface()
                                }

                                Button {
                                    id: guardedHostNetworkStartButton
                                    objectName: "guarded-host-network-start"
                                    Layout.preferredWidth: 74
                                    text: "Start blocked"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: requestGuardedHostNetworkStartFromControlSurface()
                                    Keys.onReturnPressed: requestGuardedHostNetworkStartFromControlSurface()
                                    Keys.onEnterPressed: requestGuardedHostNetworkStartFromControlSurface()
                                    Keys.onSpacePressed: requestGuardedHostNetworkStartFromControlSurface()
                                }

                                Button {
                                    objectName: "host-start-dry-run-preflight-primary"
                                    Layout.preferredWidth: 62
                                    text: "Preflight"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: requestHostStartDryRunPreflightFromControlSurface()
                                    Keys.onReturnPressed: requestHostStartDryRunPreflightFromControlSurface()
                                    Keys.onEnterPressed: requestHostStartDryRunPreflightFromControlSurface()
                                    Keys.onSpacePressed: requestHostStartDryRunPreflightFromControlSurface()
                                }

                                Button {
                                    id: backendPreflightDtoPreviewButton
                                    objectName: "backend-preflight-dto-preview"
                                    Layout.preferredWidth: 46
                                    text: "DTO"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: requestBackendPreflightPreviewFromControlSurface()
                                    Keys.onReturnPressed: requestBackendPreflightPreviewFromControlSurface()
                                    Keys.onEnterPressed: requestBackendPreflightPreviewFromControlSurface()
                                    Keys.onSpacePressed: requestBackendPreflightPreviewFromControlSurface()
                                }

                                Button {
                                    id: backendDiagnosticsDtoPreviewButton
                                    objectName: "backend-diagnostics-dto-preview"
                                    Layout.preferredWidth: 44
                                    text: "Diag"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: requestBackendDiagnosticsPreviewFromControlSurface()
                                    Keys.onReturnPressed: requestBackendDiagnosticsPreviewFromControlSurface()
                                    Keys.onEnterPressed: requestBackendDiagnosticsPreviewFromControlSurface()
                                    Keys.onSpacePressed: requestBackendDiagnosticsPreviewFromControlSurface()
                                }
                            }

                            RowLayout {
                                Layout.preferredWidth: detailTextWidth
                                spacing: 8
                                visible: false

                                Button {
                                    objectName: "authorize-operator-dry-run"
                                    text: "Authorize dry-run"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: authorizeOperatorDryRunFromControlSurface()
                                    Keys.onReturnPressed: authorizeOperatorDryRunFromControlSurface()
                                    Keys.onEnterPressed: authorizeOperatorDryRunFromControlSurface()
                                    Keys.onSpacePressed: authorizeOperatorDryRunFromControlSurface()
                                }

                                Button {
                                    objectName: "operator-dry-run-contract"
                                    text: "Dry-run contract"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: requestOperatorAuthorizedDryRunFromControlSurface()
                                    Keys.onReturnPressed: requestOperatorAuthorizedDryRunFromControlSurface()
                                    Keys.onEnterPressed: requestOperatorAuthorizedDryRunFromControlSurface()
                                    Keys.onSpacePressed: requestOperatorAuthorizedDryRunFromControlSurface()
                                }

                                Button {
                                    objectName: "host-start-dry-run-preflight"
                                    text: "Host start preflight"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: requestHostStartDryRunPreflightFromControlSurface()
                                    Keys.onReturnPressed: requestHostStartDryRunPreflightFromControlSurface()
                                    Keys.onEnterPressed: requestHostStartDryRunPreflightFromControlSurface()
                                    Keys.onSpacePressed: requestHostStartDryRunPreflightFromControlSurface()
                                }

                                Button {
                                    objectName: "authorize-operator-start-contract"
                                    text: "Authorize start contract"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: authorizeOperatorStartFromControlSurface()
                                    Keys.onReturnPressed: authorizeOperatorStartFromControlSurface()
                                    Keys.onEnterPressed: authorizeOperatorStartFromControlSurface()
                                    Keys.onSpacePressed: authorizeOperatorStartFromControlSurface()
                                }

                                Button {
                                    objectName: "operator-start-contract-status"
                                    text: "Start contract status"
                                    focusPolicy: Qt.StrongFocus
                                    activeFocusOnTab: true
                                    onClicked: requestOperatorAuthorizedHostNetworkStartFromControlSurface()
                                    Keys.onReturnPressed: requestOperatorAuthorizedHostNetworkStartFromControlSurface()
                                    Keys.onEnterPressed: requestOperatorAuthorizedHostNetworkStartFromControlSurface()
                                    Keys.onSpacePressed: requestOperatorAuthorizedHostNetworkStartFromControlSurface()
                                }
                            }

                            DeckVaapiPreviewSurface {
                                objectName: "nova-product-preview-surface"
                                Layout.preferredWidth: detailTextWidth
                                Layout.preferredHeight: visible ? 96 : 0
                                visible: novaPresenterReadiness.ready
                                opacity: visible ? 1.0 : 0.0
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Exact preview details stay behind Copy preview details."
                                color: "#7C88B8"
                                font.pixelSize: 12
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                id: copyStatusLabel
                                Layout.preferredWidth: detailTextWidth
                                text: launchPreviewCopyAction.idleStatusLabel
                                color: "#FFDDA8"
                                font.pixelSize: 13
                                wrapMode: Text.WordWrap
                                visible: false
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

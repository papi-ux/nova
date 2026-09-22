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
    color: NovaTheme.window

    readonly property int deckSafeMargin: 32
    readonly property int deckShellSpacing: 16
    readonly property int deckPanelSpacing: 12
    readonly property int deckRowSpacing: 16
    readonly property int hostColumnWidth: 336
    readonly property int sampleCardWidth: 760
    readonly property int detailColumnWidth: 424
    readonly property int hostCardHeight: 112
    readonly property int detailPanelHeight: 184
    readonly property int launchPreviewHeight: 344
    readonly property int expandedDiagnosticsLaneHeight: 132
    readonly property int hostTextWidth: hostColumnWidth - 40
    readonly property int sampleTextWidth: sampleCardWidth - 48
    readonly property int detailTextWidth: detailColumnWidth - 48
    readonly property color focusRingColor: NovaTheme.focus
    readonly property color focusGlowColor: "#284971"
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
    property bool managePcsRequested: false
    readonly property bool libraryBusy: novaStandalone && novaLibraryRefresh.state.busy
    readonly property bool libraryBlocking: libraryBusy && !novaLibraryRefresh.state.automatic
    property string refreshSelectionId: ""
    property bool closeAfterLibraryRefresh: false
    property var pendingLibraryView: ({})
    readonly property bool automaticRefreshPaused: hostPicker.opened || nativePreview.opened || diagnosticsExpanded
        || managePcsRequested || closeAfterLibraryRefresh || closeAfterNativeStop
        || (novaStandalone ? androidLibrary.interactionPaused : !libraryHasFocus())
    onAutomaticRefreshPausedChanged: if (novaStandalone) novaLibraryRefresh.setInteractionPaused(automaticRefreshPaused)
    onActiveChanged: if (novaStandalone) { novaLibraryRefresh.setWindowActive(active); novaHostPower.setWindowActive(active); novaHostSettings.setWindowActive(active) }
    Component.onCompleted: {
        if (novaStandalone) {
            novaLibraryRefresh.setInteractionPaused(automaticRefreshPaused)
            novaLibraryRefresh.setWindowActive(active)
            novaHostPower.setWindowActive(active)
            novaHostSettings.setWindowActive(active)
        }
    }

    function libraryHasFocus() {
        if (novaStandalone) return androidLibrary.browsing
        let item = activeFocusItem
        while (item) {
            if (item === libraryGameList) return true
            item = item.parent
        }
        return false
    }
    property bool expandedDiagnosticsLaneScrolledToDetails: false

    // Bound content height to the screen while each column scrolls independently.
    component ScrollableColumn: ScrollView {
        id: columnView
        default property alias columnData: contentColumn.data
        Layout.fillHeight: true
        Layout.minimumHeight: 0
        contentWidth: width
        clip: true
        ScrollBar.horizontal.policy: ScrollBar.AlwaysOff

        ColumnLayout {
            id: contentColumn
            width: columnView.availableWidth
            spacing: columnView.spacing
        }
    }

    function keepFocusedItemVisible() {
        if (novaStandalone && !hostPicker.opened) return
        const item = activeFocusItem
        if (!item) return
        for (const viewport of [hostViewport, libraryGameList, detailViewport]) {
            let ancestor = item.parent
            while (ancestor && ancestor !== viewport) ancestor = ancestor.parent
            if (ancestor !== viewport) continue
            const flickable = viewport.contentItem
            const top = item.mapToItem(flickable.contentItem, 0, 0).y
            const bottom = top + item.height
            let target = flickable.contentY
            if (top < target) target = top
            else if (bottom > target + flickable.height) target = bottom - flickable.height
            flickable.contentY = Math.max(0, Math.min(target,
                Math.max(0, flickable.contentHeight - flickable.height)))
        }
    }

    onActiveFocusItemChanged: Qt.callLater(keepFocusedItemVisible)

    function focusedControlName() {
        const item = activeFocusItem
        if (!item) return "Library"
        if (item.modelData) return item.modelData.title || item.modelData.displayName || item.modelData.label || item.text || "Library"
        if (item === hostDetailPanel) return "Review selected game"
        if (item === launchCtaPlaceholder || item === handoffActionButton) return "Launch action"
        if (item === secondaryDiagnosticsToggle) return "Diagnostics"
        if (item === copyPreviewButton) return "Copy preview details"
        return item.text || "Library"
    }

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
        if (novaStandalone) {
            if (libraryBusy || nativeSessionState.busy) return
            refreshSelectionId = selectedHostForPreview.id === hostModel.id ? selectedGameForPreview.id : ""
            if (novaLibraryRefresh.selectHost(hostModel.id)) hostPicker.close()
            return
        }
        selectedHostForPreview = {
            "id": hostModel.id,
            "displayName": hostModel.displayName,
            "statusLabel": hostModel.statusLabel,
            "subtitle": selectedHostSubtitle(hostModel),
            "provenanceLabel": hostModel.provenanceLabel ? hostModel.provenanceLabel : "backend-owned/read-only"
        }
        refreshLaunchPreviewBinding()
    }

    function refreshLibrary() {
        if (!novaStandalone || libraryBusy || nativeSessionState.busy) return
        refreshSelectionId = selectedGameForPreview.id || ""
        novaLibraryRefresh.refresh()
    }

    function prepareLibrarySnapshot(automatic) {
        if (novaStandalone) { androidLibrary.prepare(automatic); return }
        pendingLibraryView = { automatic: automatic,
            game: automatic ? selectedGameForPreview.id : refreshSelectionId,
            focus: activeFocusItem ? activeFocusItem.objectName : "",
            index: novaLibraryGames.findIndex(game => game.id === selectedGameForPreview.id),
            scroll: libraryGameList.contentItem.contentY }
    }

    function applyLibrarySnapshot() {
        if (novaStandalone) {
            selectedHostForPreview = novaSelectedHostDetail
            androidLibrary.apply()
            return
        }
        const saved = pendingLibraryView
        selectedHostForPreview = novaSelectedHostDetail
        selectedGameForPreview = novaSelectedGameCard
        if (saved.automatic && novaLibraryGames.length > 0)
            selectGameForPreview(novaLibraryGames[Math.max(0, Math.min(saved.index, novaLibraryGames.length - 1))])
        for (const game of novaLibraryGames) {
            if (game.id === saved.game) {
                selectGameForPreview(game)
                break
            }
        }
        refreshSelectionId = ""
        refreshLaunchPreviewBinding()
        Qt.callLater(function() {
            if (saved.automatic) {
                const view = libraryGameList.contentItem
                view.contentY = Math.max(0, Math.min(saved.scroll, view.contentHeight - view.height))
                if (hostPicker.opened) {
                    for (let i = 0; i < hostRepeater.count; ++i) {
                        const host = hostRepeater.itemAt(i)
                        if (host && host.objectName === saved.focus) { host.forceActiveFocus(); return }
                    }
                    focusSelectedHost()
                    return
                }
                for (const control of [managePcsButton, refreshLibraryButton, hostPickerButton,
                    secondaryDiagnosticsToggle, copyPreviewButton, handoffActionButton, nativePreviewButton]) {
                    if (control.objectName === saved.focus && control.visible && control.enabled) {
                        control.forceActiveFocus()
                        return
                    }
                }
            }
            focusSelectedGame()
        })
    }

    function focusedControlVisible() {
        if (!activeFocusItem) return false
        const point = activeFocusItem.mapToItem(contentItem, 0, 0)
        return point.x >= -1 && point.y >= -1 && point.x + activeFocusItem.width <= width + 1
            && point.y + activeFocusItem.height <= height + 1
    }
    function libraryInteractionState() {
        if (novaStandalone) return Object.assign(androidLibrary.state(), {
            width: width, height: height,
            host: selectedHostForPreview.id, games: novaLibraryGames.map(game => game.id),
            titles: novaLibraryGames.map(game => game.title), busy: libraryBusy,
            automatic: novaLibraryRefresh.state.automatic, failed: novaLibraryRefresh.state.failed,
            refreshCopy: novaLibraryRefresh.state.copy,
            focus: activeFocusItem ? activeFocusItem.objectName : "", focusVisible: focusedControlVisible(), pickerOpen: hostPicker.opened,
            nativePreviewOpen: nativePreview.opened, playSetup: nativePreview.setupState(), windowActive: active })
        return { host: selectedHostForPreview.id, game: selectedGameForPreview.id,
            title: selectedGameForPreview.title, games: novaLibraryGames.map(game => game.id),
            titles: novaLibraryGames.map(game => game.title), busy: libraryBusy, automatic: novaLibraryRefresh.state.automatic,
            failed: novaLibraryRefresh.state.failed, launchEnabled: handoffActionButton.enabled,
            focus: activeFocusItem ? activeFocusItem.objectName : "", focusVisible: focusedControlVisible(), pickerOpen: hostPicker.opened,
            nativePreviewOpen: nativePreview.opened, windowActive: active }
    }

    function selectGameForPreview(gameModel) {
        selectedGameForPreview = {
            "id": gameModel.id,
            "title": gameModel.title,
            "sourceRuntimeLabel": gameModel.sourceRuntimeLabel || "",
            "launchModeLabel": gameModel.launchModeLabel || "",
            "installedLabel": gameModel.installedLabel || "",
            "launchPolicy": gameModel.launchPolicy || { known: false, hostDefault: "", allowed: [] },
            "streamCapabilities": gameModel.streamCapabilities || {},
            "displayPlanner": gameModel.displayPlanner || {}
        }
        refreshLaunchPreviewBinding()
    }

    function focusLaunchAction() {
        if (handoffActionButton.visible && handoffActionButton.enabled) handoffActionButton.forceActiveFocus()
        else secondaryDiagnosticsToggle.forceActiveFocus()
    }

    function focusSelectedHost() {
        if (novaStandalone && (libraryBusy || nativeSessionState.busy)) return
        if (!hostPicker.opened) {
            hostPicker.open()
            return
        }
        for (let i = 0; i < hostRepeater.count; ++i) {
            const item = hostRepeater.itemAt(i)
            if (item && selectedHostForPreview && item.objectName === selectedHostForPreview.id) {
                item.forceActiveFocus()
                return
            }
        }
        const firstHost = hostRepeater.itemAt(0)
        if (firstHost) firstHost.forceActiveFocus()
        else emptyHostState.forceActiveFocus()
    }

    function focusSelectedGame() {
        if (hostPicker.opened) hostPicker.close()
        if (novaStandalone) { androidLibrary.focusGame(); return }
        for (let i = 0; i < libraryGameRepeater.count; ++i) {
            const item = libraryGameRepeater.itemAt(i)
            if (item && selectedGameForPreview && item.objectName === selectedGameForPreview.id) {
                item.forceActiveFocus()
                return
            }
        }
        const firstGame = libraryGameRepeater.itemAt(0)
        if (firstGame) firstGame.forceActiveFocus()
        else emptyGameState.forceActiveFocus()
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

    // Bound straight to the bridge property; its NOTIFY keeps this fresh.
    readonly property var handoffState: novaHandoff.state
    readonly property var nativeSessionState: novaNativeSession.state
    readonly property bool nativePlaying: !nativeSessionState.sleeping && nativeSessionState.phase === "active"
    property bool closeAfterNativeStop: false

    onClosing: (event) => {
        if (novaStandalone) novaLibraryRefresh.suspendAutomaticRefresh()
        if (libraryBusy) {
            event.accepted = false
            closeAfterLibraryRefresh = true
        }
        if (nativeSessionState.busy) {
            event.accepted = false
            closeAfterNativeStop = true
            novaNativeSession.closeSession()
        }
    }

    function leaveNativePreview() {
        nativePreview.leave()
    }

    function activateLaunchCardFromController() {
        if (novaStandalone) {
            if (libraryBusy || novaLibraryRefresh.state.failed || novaLibraryGames.length === 0) return
            nativePreview.open()
            return
        }
        if (handoffState.available) {
            novaHandoff.activate(
                selectedHostForPreview ? selectedHostForPreview.id : "",
                selectedGameForPreview ? selectedGameForPreview.id : "",
                selectedGameForPreview ? selectedGameForPreview.title : "")
            return
        }
        activateLaunchPreviewCopyFromController()
    }

    function cancelHandoffFromController() {
        if (handoffState.available && handoffState.armed) {
            novaHandoff.cancel()
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
        copyStatusLabel.color = didCopyPreview ? "#8AFFC1" : NovaTheme.warning
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
            GradientStop { position: 0.0; color: NovaTheme.window }
            GradientStop { position: 1.0; color: NovaTheme.window }
        }
    }

    Connections {
        target: novaGamepad
        function onPrimaryActionPressed(activationCount) {
            novaGamepad.activateFocusedItem()
        }
        function onSecondaryActionPressed(activationCount) {
            if (nativePreview.opened) {
                leaveNativePreview()
                return
            }
            if (novaStandalone) {
                if (hostPicker.opened) hostPicker.close()
                else androidLibrary.back()
                return
            }
            cancelHandoffFromController()
            if (hostPicker.opened) hostPicker.close()
            focusSelectedGame()
        }
    }

    Connections {
        target: novaNativeSession
        function onStateChanged() {
            if (root.closeAfterNativeStop && !root.nativeSessionState.busy) root.close()
        }
    }

    Connections {
        target: novaLibraryRefresh
        function onStateChanged() {
            if (root.closeAfterLibraryRefresh && !root.libraryBusy) root.close()
        }
    }

    NativeStreamPreview {
        presentationBridge: typeof novaVulkanPresentation !== "undefined" ? novaVulkanPresentation : null
        inputHub: novaGamepad
        id: nativePreview
        session: novaNativeSession
        settingsProvider: novaPlaySettings
        launchPolicy: selectedGameForPreview && selectedGameForPreview.launchPolicy
            ? selectedGameForPreview.launchPolicy : ({ known: false, hostDefault: "", allowed: [] })
        streamCapabilities: selectedGameForPreview.streamCapabilities || ({})
        displayPlanner: selectedGameForPreview.displayPlanner || ({})
        displayCapabilities: novaDisplayCapabilities.state
        hostId: selectedHostForPreview ? selectedHostForPreview.id : ""
        gameId: selectedGameForPreview ? selectedGameForPreview.id : ""
        hostName: selectedHostForPreview ? selectedHostForPreview.displayName : "Your PC"
        gameTitle: selectedGameForPreview ? selectedGameForPreview.title : "Selected game"
        destinationId: novaStandalone ? (novaLibraryRefresh.state.destinationId || "") : "desktop"
        destinationName: novaStandalone ? (novaLibraryRefresh.state.destinationName || "Destination unavailable") : "Desktop"
        destinationPlayable: !novaStandalone || novaLibraryRefresh.state.destinationPlayable !== false
        gameTools: novaStandalone ? novaGameTools : null
        hostSettingsController: novaStandalone ? novaHostSettings : null
        returnLabel: novaStandalone ? "Back to details" : "Back to library"
        onClosed: root.gameLinkStarted ? root.close() : novaStandalone ? androidLibrary.focusGame() : focusLaunchAction()
    }

    Popup {
        id: hostPicker
        objectName: "host-picker"
        anchors.centerIn: Overlay.overlay
        width: hostColumnWidth + 32
        height: Math.min(root.height - 120, Math.max(260, novaLibraryHosts.length * (hostCardHeight + 12) + 80))
        padding: 16
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        onOpened: focusSelectedHost()
        onClosed: Qt.callLater(focusSelectedGame)
        background: Rectangle {
            color: NovaTheme.window
            border.color: NovaTheme.divider
            radius: 20
        }
        contentItem: Item { id: hostPickerContent }
    }

    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.NoButton
        hoverEnabled: true
        cursorShape: Qt.BlankCursor
        z: 1000
    }

    property bool gameLinkPending: Object.keys(novaGameLink).length > 0
    property bool gameLinkStarted: false
    property string gameLinkError: ""
    property int gameLinkStep: 0
    Timer {
        interval: 200; repeat: true; running: root.gameLinkPending && novaStandalone
        onTriggered: {
            if (novaLibraryRefresh.state.busy || novaNativeSession.state.busy) return
            if (novaLibraryRefresh.state.failed) { root.gameLinkError = "Couldn't reach the saved PC. Refresh it to try again."; root.gameLinkPending = false; return }
            if (novaSelectedHostDetail.id !== novaGameLink.host) {
                if (root.gameLinkStep >= 1 || !novaLibraryRefresh.selectHost(novaGameLink.host)) {
                    root.gameLinkError = "The saved PC is unavailable. Select it from your PCs."; root.gameLinkPending = false
                } else root.gameLinkStep = 1
                return
            }
            if ((novaLibraryRefresh.state.destinationId || "desktop") !== novaGameLink.destination) {
                if (root.gameLinkStep >= 2 || !novaLibraryRefresh.selectDestination(novaGameLink.destination)) {
                    root.gameLinkError = "The saved destination is unavailable. Choose where to play."; root.gameLinkPending = false
                } else root.gameLinkStep = 2
                return
            }
            root.gameLinkPending = false
            root.gameLinkStarted = true
            nativePreview.autoStartRequested = true
            if (!androidLibrary.openGameLink(novaGameLink.game)) {
                root.gameLinkStarted = false; nativePreview.autoStartRequested = false
                root.gameLinkError = "The saved game is no longer in this library. Refresh the library or choose another game."
            }
        }
    }
    Label {
        anchors.top: parent.top; anchors.horizontalCenter: parent.horizontalCenter; z: 100
        visible: root.gameLinkError.length > 0
        width: parent.width - 48; padding: 16
        text: root.gameLinkError; textFormat: Text.PlainText; wrapMode: Text.WordWrap
        color: NovaTheme.text; background: Rectangle { color: NovaTheme.panel }
    }
    LibraryBrowser {
        gameTools: novaGameTools
        gameShortcuts: novaGameShortcuts
        hostPower: novaHostPower
        gamepad: novaGamepad
        id: androidLibrary
        anchors.fill: parent
        visible: novaStandalone
        enabled: novaStandalone
        games: novaLibraryGames
        host: novaSelectedHostDetail
        refreshState: novaLibraryRefresh.state
        libraryController: novaLibraryRefresh
        settingsProvider: novaPlaySettings
        hostSettingsController: novaStandalone ? novaHostSettings : null
        sessionBusy: novaNativeSession.state.busy
        onSelected: game => selectGameForPreview(game)
        onChooseHost: focusSelectedHost()
        onRefreshRequested: refreshLibrary()
        onManagePcs: { root.managePcsRequested = true; root.close() }
        onPlayRequested: game => { selectGameForPreview(game); nativePreview.open() }
    }

    FocusScope {
        id: libraryFocusScope
        anchors.fill: parent
        visible: !novaStandalone
        focus: !novaStandalone
        Component.onCompleted: Qt.callLater(function() {
            if (novaStandalone) return
            refreshLaunchPreviewBinding()
            focusSelectedGame()
        })

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: deckSafeMargin
            spacing: deckShellSpacing

            RowLayout {
                Layout.fillWidth: true
                Label {
                    text: novaDeckShellName
                    color: NovaTheme.text
                    font.pixelSize: 32
                    font.bold: true
                }
                Item { Layout.fillWidth: true }
                Button {
                    id: managePcsButton
                    objectName: "manage-pcs"
                    visible: novaStandalone
                    enabled: !novaNativeSession.state.busy && !libraryBusy
                    text: "Saved PCs"
                    Layout.preferredWidth: 170
                    Layout.preferredHeight: 52
                    function activate() {
                        if (!enabled || !visible) return
                        root.managePcsRequested = true
                        root.close()
                    }
                    onClicked: activate()
                    Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) activate() }
                    Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) activate() }
                    Keys.onRightPressed: refreshLibraryButton.forceActiveFocus()
                    Keys.onDownPressed: focusSelectedGame()
                    contentItem: Text {
                        text: managePcsButton.text; font.pixelSize: 18; font.bold: true
                        color: managePcsButton.activeFocus ? NovaTheme.window : NovaTheme.secondary
                        horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        radius: 12; color: managePcsButton.activeFocus ? NovaTheme.focus : NovaTheme.panel
                        border.color: managePcsButton.activeFocus ? NovaTheme.focus : NovaTheme.divider
                    }
                }
                Button {
                    id: refreshLibraryButton
                    objectName: "refresh-library"
                    visible: novaStandalone
                    enabled: !libraryBusy && !nativeSessionState.busy
                    text: libraryBusy && novaLibraryRefresh.state.automatic ? "Checking…" : "Refresh"
                    Layout.preferredWidth: 130
                    Layout.preferredHeight: 52
                    onClicked: refreshLibrary()
                    Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) refreshLibrary() }
                    Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) refreshLibrary() }
                    Keys.onLeftPressed: managePcsButton.forceActiveFocus()
                    Keys.onRightPressed: hostPickerButton.forceActiveFocus()
                    Keys.onDownPressed: focusSelectedGame()
                    contentItem: Text {
                        text: refreshLibraryButton.text; font.pixelSize: 18; font.bold: true
                        color: refreshLibraryButton.activeFocus ? NovaTheme.window : NovaTheme.secondary
                        horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        radius: 12; color: refreshLibraryButton.activeFocus ? NovaTheme.focus : NovaTheme.panel
                        border.color: refreshLibraryButton.activeFocus ? NovaTheme.focus : NovaTheme.divider
                    }
                }
                Button {
                    id: hostPickerButton
                    objectName: "change-host"
                    enabled: !novaStandalone || (!libraryBusy && !nativeSessionState.busy)
                    text: "Host: " + (selectedHostForPreview.displayName || "Choose host") + "  ▾"
                    Layout.preferredWidth: 424
                    Layout.preferredHeight: 52
                    onClicked: focusSelectedHost()
                    Keys.onReturnPressed: focusSelectedHost()
                    Keys.onEnterPressed: focusSelectedHost()
                    Keys.onDownPressed: focusSelectedGame()
                    Keys.onLeftPressed: { if (refreshLibraryButton.visible && refreshLibraryButton.enabled) refreshLibraryButton.forceActiveFocus() }
                    contentItem: Text {
                        text: hostPickerButton.text
                        textFormat: Text.PlainText
                        font.pixelSize: 17
                        font.bold: true
                        color: hostPickerButton.activeFocus ? NovaTheme.window : NovaTheme.secondary
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                        elide: Text.ElideRight
                    }
                    background: Rectangle {
                        color: hostPickerButton.activeFocus ? focusRingColor : NovaTheme.panel
                        border.color: hostPickerButton.activeFocus ? focusRingColor : NovaTheme.divider
                        radius: 12
                    }
                }
            }

            Label {
                text: "Choose host → Pick game → Review safe launch plan"
                visible: false
                color: NovaTheme.secondary
                font.pixelSize: 21
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 2
                color: NovaTheme.accent
                opacity: 0.65
            }

            Label {
                objectName: "library-refresh-status"
                Layout.fillWidth: true
                visible: novaStandalone && (libraryBlocking || novaLibraryRefresh.state.failed)
                text: closeAfterLibraryRefresh ? "Finishing the library check before closing…" : novaLibraryRefresh.state.copy
                textFormat: Text.PlainText
                color: novaLibraryRefresh.state.failed ? NovaTheme.warning : NovaTheme.secondary
                font.pixelSize: 17
                wrapMode: Text.WordWrap
            }

            Rectangle {
                objectName: "deck-player-flow-stepper"
                visible: false
                Layout.fillWidth: true
                Layout.preferredHeight: 44
                radius: 18
                color: NovaTheme.window
                border.color: NovaTheme.divider
                border.width: 1

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 18
                    anchors.rightMargin: 18
                    spacing: 18

                    Label {
                        text: "FOCUS"
                        color: NovaTheme.focus
                        font.pixelSize: 16
                        font.bold: true
                    }

                    Label {
                        text: focusedControlName()
                        textFormat: Text.PlainText
                        Layout.fillWidth: true
                        elide: Text.ElideRight
                        color: NovaTheme.text
                        font.pixelSize: 16
                        font.bold: true
                    }

                    Label {
                        text: "3 · Review launch plan"
                        visible: false
                        color: NovaTheme.warning
                        font.pixelSize: 16
                        font.bold: true
                    }

                    Item { Layout.fillWidth: true }

                    Label {
                        text: "White outline = active control"
                        color: NovaTheme.muted
                        font.pixelSize: 13
                    }
                }
            }

            RowLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumHeight: 0
                spacing: deckRowSpacing

                ScrollableColumn {
                    id: hostViewport
                    objectName: "host-viewport"
                    parent: hostPickerContent
                    anchors.fill: parent
                    Layout.preferredWidth: hostColumnWidth
                    spacing: deckPanelSpacing

                    Label {
                        text: novaStandalone ? "Choose a PC" : "1 · Pick host"
                        color: NovaTheme.text
                        font.pixelSize: 26
                        font.bold: true
                    }

                    Label {
                        Layout.preferredWidth: hostTextWidth
                        text: "Backend-fed hosts · " + novaBackendReadOnlyState.sourceLabel + (novaBackendReadOnlyState.readOnly ? " · backend-owned read-only model · " + novaBackendReadOnlyProvenance : " · Backend read-only model unavailable — network remains disabled")
                                visible: diagnosticsExpanded
                        color: NovaTheme.secondary
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
                        color: activeFocus ? focusGlowColor : NovaTheme.window
                        border.color: activeFocus ? focusRingColor : NovaTheme.divider
                        border.width: activeFocus ? 5 : 2
                        focus: visible
                        activeFocusOnTab: visible
                        KeyNavigation.right: hostDetailPanel
                        Keys.onRightPressed: focusSelectedGame()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 18
                            spacing: 5

                            Label {
                                text: "No demo hosts yet"
                                color: NovaTheme.text
                                font.pixelSize: 22
                                font.bold: true
                            }

                            Label {
                                text: "Empty host state is focusable and deterministic."
                                color: NovaTheme.secondary
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
                            color: activeFocus ? focusRingColor : NovaTheme.window
                            border.color: activeFocus ? focusRingColor : NovaTheme.divider
                            border.width: activeFocus ? 5 : 1
                            focus: modelData.initialFocus
                            activeFocusOnTab: true
                            KeyNavigation.right: hostDetailPanel
                            onActiveFocusChanged: if (activeFocus && !novaStandalone) selectHostForPreview(modelData)
                            TapHandler {
                                onTapped: {
                                    selectHostForPreview(modelData)
                                    focusSelectedGame()
                                }
                            }
                            Keys.onRightPressed: {
                                selectHostForPreview(modelData)
                                focusSelectedGame()
                            }
                            Keys.onReturnPressed: { selectHostForPreview(modelData); focusSelectedGame() }
                            Keys.onEnterPressed: { selectHostForPreview(modelData); focusSelectedGame() }
                            Keys.onSpacePressed: { selectHostForPreview(modelData); focusSelectedGame() }
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
                                    Layout.fillWidth: true
                                    text: modelData.displayName
                                    textFormat: Text.PlainText
                                    elide: Text.ElideRight
                                    color: parent.parent.activeFocus ? NovaTheme.window : NovaTheme.text
                                    font.pixelSize: 20
                                    font.bold: true
                                }

                                Label {
                                    Layout.fillWidth: true
                                    text: modelData.statusLabel
                                    elide: Text.ElideRight
                                    color: parent.parent.activeFocus ? NovaTheme.divider : NovaTheme.secondary
                                    font.pixelSize: 16
                                }

                                Label {
                                    visible: selectedHostForPreview.id === modelData.id
                                    text: parent.parent.activeFocus ? "A · Use this host" : "Selected host"
                                    color: parent.parent.activeFocus ? NovaTheme.divider : NovaTheme.muted
                                    font.pixelSize: 14
                                    font.bold: true
                                }
                            }
                        }
                    }
                }

                ScrollableColumn {
                    id: libraryGameList
                    objectName: "library-game-list"
                    enabled: !libraryBlocking
                    opacity: libraryBlocking ? 0.55 : 1
                    Layout.preferredWidth: sampleCardWidth
                    spacing: deckPanelSpacing

                    Label {
                        text: "Your games"
                        color: NovaTheme.text
                        font.pixelSize: 23
                        font.bold: true
                    }

                    Label {
                        Layout.preferredWidth: sampleTextWidth
                        text: "Backend-fed library snapshot · " + novaBackendReadOnlyState.sourceLabel + (novaBackendReadOnlyState.readOnly ? " · backend-owned read-only model · " + novaBackendReadOnlyProvenance : " · Backend read-only model unavailable — network remains disabled")
                                visible: diagnosticsExpanded
                        color: NovaTheme.secondary
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
                        color: activeFocus ? focusGlowColor : NovaTheme.window
                        border.color: activeFocus ? focusRingColor : NovaTheme.divider
                        border.width: activeFocus ? 5 : 2
                        focus: visible
                        activeFocusOnTab: visible
                        KeyNavigation.left: novaLibraryHosts.length > 0 ? hostRepeater.itemAt(0) : emptyHostState
                        KeyNavigation.right: hostDetailPanel
                        Keys.onLeftPressed: hostPickerButton.forceActiveFocus()
                        Keys.onRightPressed: focusLaunchAction()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 16
                            spacing: 5

                            Label {
                                text: novaStandalone ? "No games to show" : "No games in read-only snapshot"
                                color: NovaTheme.text
                                font.pixelSize: 20
                                font.bold: true
                            }

                            Label {
                                Layout.preferredWidth: sampleTextWidth
                                text: novaStandalone ? (novaLibraryRefresh.state.failed
                                    ? "Refresh after checking the PC, or choose another saved PC."
                                    : "Refresh the game list or choose another saved PC.")
                                    : "Snapshot unavailable in this preview shell — no backend request will be made."
                                color: NovaTheme.secondary
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
                            Layout.preferredHeight: 104
                            radius: 18
                            color: activeFocus ? focusRingColor : NovaTheme.window
                            border.color: activeFocus ? focusRingColor : NovaTheme.divider
                            border.width: activeFocus ? 5 : 1
                            focus: modelData.initialFocus
                            activeFocusOnTab: true
                            KeyNavigation.right: hostDetailPanel
                            onActiveFocusChanged: if (activeFocus) selectGameForPreview(modelData)
                            TapHandler {
                                onTapped: parent.forceActiveFocus()
                            }
                            Keys.onRightPressed: {
                                selectGameForPreview(modelData)
                                focusLaunchAction()
                            }
                            Keys.onReturnPressed: { selectGameForPreview(modelData); focusLaunchAction() }
                            Keys.onEnterPressed: { selectGameForPreview(modelData); focusLaunchAction() }
                            Keys.onSpacePressed: { selectGameForPreview(modelData); focusLaunchAction() }
                            Keys.onDownPressed: {
                                const next = libraryGameRepeater.itemAt(Math.min(index + 1, libraryGameRepeater.count - 1))
                                if (next !== null) {
                                    next.forceActiveFocus()
                                }
                            }
                            Keys.onUpPressed: {
                                if (index === 0) {
                                    hostPickerButton.forceActiveFocus()
                                    return
                                }
                                const previous = libraryGameRepeater.itemAt(index - 1)
                                if (previous !== null) {
                                    previous.forceActiveFocus()
                                }
                            }
                            Keys.onLeftPressed: hostPickerButton.forceActiveFocus()

                            ColumnLayout {
                                anchors.fill: parent
                                anchors.margins: 16
                                spacing: 4

                                Item {
                                    objectName: "selected-game-readability-card"
                                    visible: false
                                }

                                Label {
                                    Layout.fillWidth: true
                                    text: modelData.title
                                    textFormat: Text.PlainText
                                    elide: Text.ElideRight
                                    color: parent.parent.activeFocus ? NovaTheme.window : NovaTheme.text
                                    font.pixelSize: 26
                                    font.bold: true
                                }

                                Label {
                                    Layout.fillWidth: true
                                    text: modelData.installedLabel
                                    elide: Text.ElideRight
                                    color: parent.parent.activeFocus ? NovaTheme.divider : NovaTheme.secondary
                                    font.pixelSize: 13
                                }

                                Label {
                                    Layout.fillWidth: true
                                    text: modelData.launchModeLabel
                                    visible: diagnosticsExpanded
                                    elide: Text.ElideRight
                                    color: parent.parent.activeFocus ? NovaTheme.divider : NovaTheme.secondary
                                    font.pixelSize: 14
                                }

                                Label {
                                    visible: selectedGameForPreview.id === modelData.id
                                    text: parent.parent.activeFocus ? "A · Review and play" : "Selected game"
                                    color: parent.parent.activeFocus ? NovaTheme.divider : NovaTheme.muted
                                    font.pixelSize: 13
                                    font.bold: true
                                }
                            }
                        }
                    }
                }

                ScrollableColumn {
                    id: detailViewport
                    objectName: "detail-viewport"
                    Layout.preferredWidth: detailColumnWidth
                    spacing: deckPanelSpacing

                    Rectangle {
                        id: hostDetailPanel
                        objectName: "host-detail-panel"
                        Layout.preferredWidth: detailColumnWidth
                        Layout.preferredHeight: Math.max(detailPanelHeight, hostReviewContent.implicitHeight + 40)
                        radius: 22
                        color: activeFocus ? focusGlowColor : NovaTheme.window
                        border.color: activeFocus ? focusRingColor : NovaTheme.divider
                        border.width: activeFocus ? 5 : 2
                        focus: false
                        activeFocusOnTab: false
                        KeyNavigation.left: hostRepeater.itemAt(0) !== null ? hostRepeater.itemAt(0) : emptyHostState
                        KeyNavigation.up: copyPreviewButton
                        KeyNavigation.down: launchCtaPlaceholder
                        Keys.onLeftPressed: focusSelectedLibraryItem()
                        Keys.onUpPressed: copyPreviewButton.forceActiveFocus()
                        Keys.onDownPressed: focusLaunchAction()

                        ColumnLayout {
                            id: hostReviewContent
                            anchors.fill: parent
                            anchors.margins: 20
                            spacing: 8

                            Label {
                                text: "Selected game"
                                color: NovaTheme.muted
                                font.pixelSize: 16
                            }

                            Label {
                                Layout.fillWidth: true
                                text: selectedGameForPreview.title
                                textFormat: Text.PlainText
                                elide: Text.ElideRight
                                color: NovaTheme.text
                                font.pixelSize: 26
                                font.bold: true
                            }

                            Label {
                                Layout.fillWidth: true
                                text: selectedHostForPreview.displayName + " · " + selectedHostForPreview.statusLabel
                                wrapMode: Text.WordWrap
                                maximumLineCount: 3
                                elide: Text.ElideRight
                                color: NovaTheme.secondary
                                font.pixelSize: 15
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Provenance: " + (selectedHostForPreview.provenanceLabel ? selectedHostForPreview.provenanceLabel : "backend-owned/read-only")
                                visible: diagnosticsExpanded
                                color: "#C9F0D4"
                                font.pixelSize: 13
                                font.bold: true
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: selectedHostForPreview.subtitle
                                visible: diagnosticsExpanded
                                color: NovaTheme.secondary
                                font.pixelSize: 16
                                maximumLineCount: 1
                                elide: Text.ElideRight
                                wrapMode: Text.NoWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Selected game: " + selectedGameForPreview.title
                                textFormat: Text.PlainText
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
                        Layout.preferredHeight: Math.max(launchPreviewHeight, launchContent.implicitHeight + 32)
                        radius: 20
                        color: activeFocus ? focusGlowColor : NovaTheme.panel
                        border.color: activeFocus ? focusRingColor : NovaTheme.divider
                        border.width: activeFocus ? 5 : 2
                        opacity: 1.0
                        focus: false
                        activeFocusOnTab: false
                        KeyNavigation.up: hostDetailPanel
                        KeyNavigation.down: secondaryDiagnosticsToggle
                        Keys.onUpPressed: hostDetailPanel.forceActiveFocus()
                        Keys.onDownPressed: secondaryDiagnosticsToggle.forceActiveFocus()
                        Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) activateLaunchCardFromController() }
                        Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) activateLaunchCardFromController() }
                        Keys.onSpacePressed: (event) => { if (!event.isAutoRepeat) activateLaunchCardFromController() }
                        Keys.onEscapePressed: cancelHandoffFromController()
                        Keys.onBackPressed: cancelHandoffFromController()
                        Keys.onLeftPressed: focusSelectedLibraryItem()

                        ColumnLayout {
                            id: launchContent
                            anchors.fill: parent
                            anchors.margins: 16
                            spacing: 3

                            Label {
                                text: handoffState.available || novaStandalone ? "Open selected game" : "3 · Review launch plan"
                                color: NovaTheme.muted
                                font.pixelSize: 13
                                font.bold: true
                            }

                            Label {
                                text: backendReadOnlyPlayerState && backendReadOnlyPlayerState.title ? backendReadOnlyPlayerState.title : "Product state: Launch preview blocked"
                                visible: !handoffState.available && !novaStandalone
                                color: NovaTheme.text
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
                                visible: !handoffState.available && !novaStandalone && !diagnosticsExpanded
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: backendReadOnlyPlayerState && backendReadOnlyPlayerState.actionLabel ? backendReadOnlyPlayerState.actionLabel : "Review the safe launch plan before copying it locally."
                                color: NovaTheme.text
                                font.pixelSize: 15
                                font.bold: true
                                wrapMode: Text.WordWrap
                                maximumLineCount: 2
                                elide: Text.ElideRight
                                visible: !handoffState.available && !novaStandalone && !diagnosticsExpanded
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: backendReadOnlyPlayerState && backendReadOnlyPlayerState.safetyLabel ? backendReadOnlyPlayerState.safetyLabel : "Read-only state only; diagnostics are secondary and safe to inspect."
                                color: NovaTheme.warning
                                font.pixelSize: 12
                                font.bold: true
                                wrapMode: Text.WordWrap
                                maximumLineCount: 2
                                elide: Text.ElideRight
                                visible: !handoffState.available && !novaStandalone && !diagnosticsExpanded
                            }

                            Button {
                                id: handoffActionButton
                                objectName: "handoff-primary-action"
                                enabled: !novaStandalone || (!libraryBusy && !novaLibraryRefresh.state.failed && novaLibraryGames.length > 0)
                                Layout.preferredWidth: detailTextWidth
                                Layout.minimumHeight: 48
                                text: novaStandalone ? "Preview in Nova" : !handoffState.available ? "Copy safe launch plan"
                                    : handoffState.running ? "End current session"
                                    : handoffState.armed ? "Confirm launch in Moonlight" : "Play in Moonlight"
                                contentItem: Text {
                                    text: handoffActionButton.text
                                    color: !handoffActionButton.enabled ? NovaTheme.muted
                                        : handoffActionButton.activeFocus ? NovaTheme.window : NovaTheme.focus
                                    font.pixelSize: 18
                                    font.bold: true
                                    horizontalAlignment: Text.AlignHCenter
                                    verticalAlignment: Text.AlignVCenter
                                }
                                background: Rectangle {
                                    radius: 12
                                    color: !handoffActionButton.enabled ? NovaTheme.panel
                                        : handoffActionButton.activeFocus ? focusRingColor : NovaTheme.raised
                                    border.color: handoffActionButton.activeFocus ? focusRingColor : NovaTheme.divider
                                    border.width: handoffActionButton.activeFocus ? 5 : 1
                                }
                                visible: !diagnosticsExpanded
                                onClicked: activateLaunchCardFromController()
                                Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) activateLaunchCardFromController() }
                                Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) activateLaunchCardFromController() }
                                Keys.onLeftPressed: focusSelectedLibraryItem()
                                Keys.onUpPressed: focusSelectedGame()
                                Keys.onDownPressed: {
                                    if (nativePreviewButton.visible) nativePreviewButton.forceActiveFocus()
                                    else copyPreviewButton.forceActiveFocus()
                                }
                            }

                            Button {
                                id: nativePreviewButton
                                objectName: "native-preview-open"
                                Layout.preferredWidth: detailTextWidth
                                Layout.minimumHeight: 48
                                visible: novaNativeSession.enabled && !novaStandalone && !diagnosticsExpanded
                                enabled: !handoffState.running && !handoffState.armed
                                text: "Preview in Nova"
                                contentItem: Text {
                                    text: nativePreviewButton.text
                                    color: nativePreviewButton.activeFocus ? NovaTheme.window : "white"
                                    font.pixelSize: 18
                                    font.bold: true
                                    horizontalAlignment: Text.AlignHCenter
                                    verticalAlignment: Text.AlignVCenter
                                }
                                background: Rectangle {
                                    radius: 12
                                    color: nativePreviewButton.activeFocus ? NovaTheme.focus : NovaTheme.raised
                                    border.color: nativePreviewButton.activeFocus ? NovaTheme.focus : NovaTheme.divider
                                    border.width: nativePreviewButton.activeFocus ? 5 : 1
                                }
                                onClicked: nativePreview.open()
                                Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) nativePreview.open() }
                                Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) nativePreview.open() }
                                Keys.onUpPressed: handoffActionButton.forceActiveFocus()
                                Keys.onLeftPressed: focusSelectedGame()
                                Keys.onDownPressed: copyPreviewButton.forceActiveFocus()
                            }

                            Label {
                                objectName: "moonlight-handoff-status"
                                Layout.preferredWidth: detailTextWidth
                                text: handoffState.copy + (handoffState.sessionCopy ? " · " + handoffState.sessionCopy : "")
                                color: handoffState.running ? "#8AFFC1" : NovaTheme.warning
                                font.pixelSize: 13
                                font.bold: true
                                wrapMode: Text.WordWrap
                                maximumLineCount: 2
                                elide: Text.ElideRight
                                visible: !diagnosticsExpanded && handoffState.copy.length > 0
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaHostLaunchCta.helpText
                                color: NovaTheme.secondary
                                font.pixelSize: 13
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaStandalone ? "Review your game before starting the stream."
                                    : backendReadOnlyPlayerState && backendReadOnlyPlayerState.body ? backendReadOnlyPlayerState.body : "Launch preview blocked. Open diagnostics."
                                color: novaStandalone ? NovaTheme.secondary : NovaTheme.warning
                                font.pixelSize: 14
                                font.bold: true
                                wrapMode: Text.WordWrap
                                maximumLineCount: 2
                                elide: Text.ElideRight
                                visible: !handoffState.available && !diagnosticsExpanded
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
                                visible: !novaStandalone && !handoffState.available && !diagnosticsExpanded
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Blocked safely: lab gate keeps backend power and streams off."
                                color: NovaTheme.warning
                                font.pixelSize: 11
                                font.bold: true
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Diagnostics explain why; they never start discovery, backend power, or media."
                                color: NovaTheme.secondary
                                font.pixelSize: 10
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaLaunchIntentBoundary.reason
                                color: NovaTheme.secondary
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
                                color: NovaTheme.warning
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
                                    : NovaTheme.warning
                                font.pixelSize: 13
                                font.bold: novaPresenterReadiness.ready || novaPresenterReadiness.hardwarePresenterPlanned
                                wrapMode: Text.WordWrap
                                visible: false
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaPresenterReadiness.detail
                                color: NovaTheme.secondary
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
                                color: previewLifecycleReport.armed ? "#8AFFC1" : NovaTheme.warning
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
                                    : NovaTheme.warning
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
                                color: backendPreflightPreview.approved ? "#8AFFC1" : NovaTheme.warning
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
                                Layout.preferredWidth: detailTextWidth
                                Layout.preferredHeight: 48
                                focusPolicy: Qt.StrongFocus
                                activeFocusOnTab: true
                                KeyNavigation.up: launchCtaPlaceholder
                                KeyNavigation.down: secondaryDiagnosticsToggle
                                Keys.onUpPressed: focusLaunchAction()
                                Keys.onDownPressed: secondaryDiagnosticsToggle.forceActiveFocus()
                                Keys.onLeftPressed: focusSelectedLibraryItem()
                                Keys.onReturnPressed: activateLaunchPreviewCopyFromController()
                                Keys.onEnterPressed: activateLaunchPreviewCopyFromController()
                                Keys.onSpacePressed: activateLaunchPreviewCopyFromController()
                                onClicked: activateLaunchPreviewCopyFromController()
                                contentItem: Text {
                                    text: copyPreviewButton.text
                                    color: copyPreviewButton.activeFocus ? NovaTheme.window : NovaTheme.text
                                    font.pixelSize: 13
                                    font.bold: true
                                    horizontalAlignment: Text.AlignHCenter
                                    verticalAlignment: Text.AlignVCenter
                                    elide: Text.ElideRight
                                }
                                background: Rectangle {
                                    radius: 12
                                    color: copyPreviewButton.activeFocus ? focusRingColor : NovaTheme.panel
                                    border.color: copyPreviewButton.activeFocus ? focusRingColor : NovaTheme.divider
                                    border.width: copyPreviewButton.activeFocus ? 5 : 1
                                }
                            }

                            Button {
                                id: secondaryDiagnosticsToggle
                                contentItem: Text {
                                    text: secondaryDiagnosticsToggle.text
                                    color: secondaryDiagnosticsToggle.activeFocus ? NovaTheme.window : NovaTheme.text
                                    font.pixelSize: 14
                                    horizontalAlignment: Text.AlignHCenter
                                    verticalAlignment: Text.AlignVCenter
                                }
                                background: Rectangle {
                                    radius: 12
                                    color: secondaryDiagnosticsToggle.activeFocus ? focusRingColor : NovaTheme.panel
                                    border.color: secondaryDiagnosticsToggle.activeFocus ? focusRingColor : NovaTheme.divider
                                    border.width: secondaryDiagnosticsToggle.activeFocus ? 5 : 1
                                }
                                objectName: "secondary-diagnostics-toggle"
                                text: activeFocus
                                    ? "D-pad focus · A · " + (diagnosticsExpanded ? "Hide diagnostics" : "Show diagnostics")
                                    : diagnosticsExpanded ? "Hide diagnostics" : "Show diagnostics"
                                visible: true
                                Layout.preferredWidth: detailTextWidth
                                Layout.preferredHeight: 48
                                focusPolicy: Qt.StrongFocus
                                activeFocusOnTab: true
                                KeyNavigation.up: launchCtaPlaceholder
                                KeyNavigation.down: diagnosticsExpanded ? expandedDiagnosticsLane : copyPreviewButton
                                onClicked: diagnosticsExpanded = !diagnosticsExpanded
                                Keys.onReturnPressed: diagnosticsExpanded = !diagnosticsExpanded
                                Keys.onEnterPressed: diagnosticsExpanded = !diagnosticsExpanded
                                Keys.onSpacePressed: diagnosticsExpanded = !diagnosticsExpanded
                                Keys.onUpPressed: copyPreviewButton.forceActiveFocus()
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
                                color: NovaTheme.warning
                                font.pixelSize: 13
                                wrapMode: Text.WordWrap
                                visible: false
                            }
                        }
                    }
                }
            }

            Label {
                text: novaDeckFullscreenPreferred
                    ? "D-pad Navigate · A Review / Select · B Back · Touch to select"
                    : "Deck default: 1280×800 · windowed test mode"
                color: NovaTheme.muted
                font.pixelSize: 18
            }
        }
    }
}

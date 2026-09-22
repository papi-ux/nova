import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: host
    objectName: "host-defaults"
    property var controller: null
    property bool syncView: false
    property bool readOnlyView: false
    property string backLabel: "This Game"
    property var settingsProvider: null
    property var session: null
    readonly property bool sessionView: session !== null
    readonly property var live: sessionView && session.hud ? session.hud : ({})
    property var lastSyncVersion: 0
    property string focusedExtra: ""
    readonly property var status: controller ? controller.state : ({})
    readonly property var settings: status.settings || ({})
    readonly property var modes: settings.modes || []
    readonly property var profileRows: [
        { label: "Nova defaults · this device", value: profile(status.novaDisplay, status.novaBitrate) },
        { label: "Paired-device profile", value: profile(settings.desiredDisplay, settings.desiredBitrate) },
        { label: "Host-reported profile", value: profile(settings.effectiveDisplay, settings.effectiveBitrate) }
    ]
    readonly property var displayRows: [
        { label: "Desired display", value: settings.desiredLabel || "Not verified" },
        { label: "Effective display", value: settings.effectiveLabel || "Not verified" }
    ]
    readonly property var timeoutRows: [
        { label: "Resume timeout · PC-wide", value: settings.desiredResumeTimeout >= 0 ? timeoutLabel(settings.desiredResumeTimeout)
            + (settings.effectiveResumeTimeout !== settings.desiredResumeTimeout ? " requested · " + timeoutLabel(settings.effectiveResumeTimeout) + " effective" : "") : "Not supplied" }
    ]
    property string focusedMode: ""
    property string focusedProfile: ""
    property string focusedSync: ""
    property bool wasAutomatic: false
    readonly property var profileActions: status.profileActions || []
    readonly property real unit: Math.max(0.85, Math.min(1.15, width / 1280))
    parent: Overlay.overlay
    width: parent ? parent.width : 1280
    height: parent ? parent.height : 800
    padding: 32 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    background: Rectangle { color: NovaTheme.window }
    onOpened: {
        focusedMode = ""; focusedProfile = ""; focusedSync = ""; focusedExtra = ""; actions.contentY = 0; facts.contentY = 0
        if (controller) { if (syncView) controller.openSync(readOnlyView); else controller.open() }
        done.forceActiveFocus()
    }
    onClosed: { streamEditor.close(); timeoutPicker.close(); sessionBitrate.close(); if (controller) controller.close() }
    function state() {
        function center(item) {
            if (!item) return { x: 0, y: 0 }
            const p = item.mapToItem(null, item.width / 2, item.height / 2)
            return { x: Math.round(p.x), y: Math.round(p.y) }
        }
        return { opened: opened, readOnly: readOnlyView, syncView: syncView, status: status, controls: { refresh: center(refresh), back: center(done), syncOn: center(syncOn), syncOff: center(syncOff), editDefaults: center(editDefaults), resumeTimeout: center(resumeTimeout) },
            modes: modes.map((mode, index) => ({ id: mode.id, center: center(modeButtons.itemAt(index)) })),
            profileActions: profileActions.map((action, index) => ({ id: action.id, enabled: action.enabled, center: center(profileButtons.itemAt(index)) })) }
    }
    function back() {
        if (sessionBitrate.opened) sessionBitrate.close()
        else if (streamEditor.opened) streamEditor.back()
        else if (timeoutPicker.opened) timeoutPicker.close()
        else close()
    }
    function focusMode(index) {
        const item = modeButtons.itemAt(Math.max(0, Math.min(index, modes.length - 1)))
        if (item) item.forceActiveFocus(); else refresh.forceActiveFocus()
    }
    function focusProfile(index) {
        const item = profileButtons.itemAt(Math.max(0, Math.min(index, profileActions.length - 1)))
        if (item) item.forceActiveFocus(); else refresh.forceActiveFocus()
    }
    function focusSavedMode() {
        if (sessionView) { sessionActions.focusSaved(); return }
        if (readOnlyView) { facts.forceActiveFocus(); return }
        if (focusedExtra.length) { (focusedExtra === "defaults" ? editDefaults : resumeTimeout).forceActiveFocus(); return }
        if (focusedSync.length) (focusedSync === "on" ? syncOn : syncOff).forceActiveFocus()
        else if (focusedProfile.length) focusProfile(profileActions.findIndex(action => action.id === focusedProfile))
        else focusMode(Math.max(0, modes.findIndex(mode => mode.id === (focusedMode || settings.desiredMode))))
    }
    Connections {
        target: host.controller
        function onStateChanged() {
            // Rebuilding the host catalog must not strand focus on a destroyed row.
            if (!host.opened || host.readOnlyView || streamEditor.opened || timeoutPicker.opened) return
            const automatic = host.wasAutomatic || host.status.automaticCheck
            host.wasAutomatic = host.status.automaticCheck
            if (!host.modes.length) Qt.callLater(function() { actions.contentY = 0 })
            if (host.status.busy && !automatic) done.forceActiveFocus()
            else if (!host.modes.length) {
                host.focusedMode = ""; host.focusedProfile = ""; host.focusedSync = ""
                Qt.callLater(function() { actions.contentY = 0; refresh.forceActiveFocus() })
            }
            else if (automatic && (facts.activeFocus || done.activeFocus || refresh.activeFocus || syncOn.activeFocus || syncOff.activeFocus)) return
            else if (host.focusedExtra.length) Qt.callLater(host.focusSavedMode)
            else if (host.focusedSync.length) Qt.callLater(host.focusSavedMode)
            else if (host.focusedMode.length || host.focusedProfile.length) Qt.callLater(host.focusSavedMode)
        }
    }
    Connections {
        target: host.session
        function onHudChanged() {
            const version = host.live.syncVersion || 0
            if (host.lastSyncVersion === version) return
            host.lastSyncVersion = version
            if (host.opened && !host.live.syncBusy && host.controller && host.status.canRefresh) host.controller.refresh()
        }
    }
    LiveBitrate {
        id: sessionBitrate; objectName: "sync-live-bitrate-picker"; session: host.session; unit: host.unit
        onClosed: if (host.opened && host.sessionView) sessionActions.focusBitrate()
    }
    function timeoutLabel(seconds) {
        return seconds < 0 || seconds === undefined ? "Not supplied"
            : seconds > 0 && seconds % 60 === 0 ? (seconds / 60) + (seconds === 60 ? " minute" : " minutes") : seconds + " seconds"
    }
    StreamProfileEditor {
        id: streamEditor; unit: host.unit; defaultsScope: true
        settingsProvider: host.settingsProvider; hostController: host.controller
        hostId: host.status.hostId || ""; returnFocus: editDefaults
        onSaved: host.focusedExtra = "defaults"
    }
    Popup {
        id: timeoutPicker; objectName: "host-resume-timeout-picker"
        parent: Overlay.overlay; anchors.centerIn: parent
        width: Math.min(680 * host.unit, parent.width - 32)
        height: Math.min(implicitHeight, parent.height - 32)
        padding: 24 * host.unit; modal: true; focus: true; closePolicy: Popup.CloseOnEscape
        onOpened: timeoutChoices.itemAt(Math.max(0, [60,300,600,1800].indexOf(host.settings.desiredResumeTimeout))).forceActiveFocus()
        onClosed: if (host.opened) resumeTimeout.forceActiveFocus()
        background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
        contentItem: NovaScrollColumn {
            spacing: 14 * host.unit
            Copy { text: "Resume timeout"; font.bold: true; font.pixelSize: 28 * host.unit * NovaTheme.fontScale }
            Copy { text: "How long Polaris keeps a disconnected game available to resume. This PC-wide setting affects all paired devices. It does not end the current game." }
            Repeater {
                id: timeoutChoices; model: [60,300,600,1800]
                NovaButton {
                    required property int modelData
                    required property int index
                    objectName: "host-resume-timeout-" + modelData
                    Layout.fillWidth: true; unit: host.unit
                    text: host.timeoutLabel(modelData) + (modelData === host.settings.desiredResumeTimeout ? " · Selected" : "")
                    onClicked: if (modelData === host.settings.desiredResumeTimeout || host.controller.setResumeTimeout(modelData)) timeoutPicker.close()
                    Keys.onUpPressed: timeoutChoices.itemAt(Math.max(0,index-1)).forceActiveFocus()
                    Keys.onDownPressed: index < 3 ? timeoutChoices.itemAt(index+1).forceActiveFocus() : timeoutBack.forceActiveFocus()
                }
            }
            NovaButton {
                id: timeoutBack; objectName: "host-resume-timeout-back"
                Layout.fillWidth: true; unit: host.unit; text: "Cancel"; onClicked: timeoutPicker.close()
                Keys.onUpPressed: timeoutChoices.itemAt(3).forceActiveFocus()
            }
        }
    }
    component Copy: Label {
        Layout.fillWidth: true
        textFormat: Text.PlainText
        wrapMode: Text.WordWrap
        color: NovaTheme.text
        font.pixelSize: 17 * host.unit * NovaTheme.fontScale
    }
    contentItem: ColumnLayout {
        spacing: 16 * host.unit
        Copy { objectName: "host-settings-title"; text: host.syncView ? "Polaris Sync" : "Every Game"; font.pixelSize: 32 * host.unit * NovaTheme.fontScale; font.bold: true }
        Copy { text: host.status.hostName || "Selected PC"; color: NovaTheme.secondary }
        Copy {
            objectName: "host-settings-scope"
            text: host.sessionView ? "During play · Live controls apply now. Saved resolution and frame rate apply to the next stream."
                : host.readOnlyView ? "During play · Saved profiles only. Current bitrate is shown in Live Tuning."
                : host.syncView ? "Compare Nova's device defaults with this paired PC profile. Per-game overrides stay separate." : ""
            visible: text.length > 0; color: NovaTheme.secondary
        }
        RowLayout {
            Layout.fillHeight: true
            Layout.minimumHeight: 0
            spacing: 32 * host.unit
            NovaScrollColumn {
                id: facts
                objectName: "host-defaults-plan"
                Layout.fillWidth: true
                Layout.preferredWidth: 0.9
                Layout.fillHeight: true
                Layout.minimumHeight: 0
                spacing: 14 * host.unit
                activeFocusOnTab: true
                Keys.onUpPressed: { if (contentY > 0) contentY = Math.max(0, contentY - 64 * host.unit); else done.forceActiveFocus() }
                Keys.onDownPressed: { const end = Math.max(0, contentHeight - height); if (contentY < end - 1) contentY = Math.min(end, contentY + 64 * host.unit); else refresh.forceActiveFocus() }
                Keys.onRightPressed: { if (host.readOnlyView && !host.sessionView) refresh.forceActiveFocus(); else host.focusSavedMode() }
                Keys.onLeftPressed: done.forceActiveFocus()
                Rectangle { parent: facts; anchors.fill: parent; color: "transparent"; radius: 6; border.width: facts.activeFocus ? 2 : 0; border.color: NovaTheme.focus; z: 1 }
                Copy { text: host.readOnlyView ? "SAVED PROFILES" : "WHAT THE PC USES"; color: NovaTheme.secondary; font.pixelSize: 12 * host.unit * NovaTheme.fontScale }
                Copy { text: host.readOnlyView ? "Nova and Polaris" : "Defaults on Polaris"; font.pixelSize: 26 * host.unit * NovaTheme.fontScale; font.bold: true }
                Copy { visible: host.sessionView; text: "Encoder confirmed now"; color: NovaTheme.secondary; font.pixelSize: 13 * host.unit * NovaTheme.fontScale }
                Copy { objectName: "sync-encoder-applied"; visible: host.sessionView; text: host.live.hostFresh && host.live.appliedBitrateKbps > 0 ? host.live.appliedBitrateKbps / 1000 + " Mbps" : "Unavailable"; font.weight: Font.DemiBold }
                Copy { visible: !host.readOnlyView; text: "The default display affects future Desktop streams on this PC. Spaces use their own launch settings."; color: NovaTheme.secondary }
                Repeater {
                    model: host.readOnlyView ? host.profileRows.concat(host.displayRows, host.timeoutRows)
                        : host.displayRows.concat(host.profileRows, host.timeoutRows)
                    ColumnLayout {
                        required property var modelData
                        Layout.fillWidth: true
                        spacing: 4 * host.unit
                        Copy { text: modelData.label; color: NovaTheme.secondary; font.pixelSize: 13 * host.unit * NovaTheme.fontScale }
                        Copy { text: modelData.value; font.weight: Font.DemiBold }
                    }
                }
                Copy { visible: host.readOnlyView; text: "Keep in step: " + (host.status.keepInStep === "on" ? "Paused during play" : host.status.keepInStep === "paused" ? "Needs review" : "Off"); color: NovaTheme.secondary }
                Copy {
                    text: host.status.profileState || "Not verified"
                    color: NovaTheme.secondary
                }
                Copy {
                    text: host.settings.relaunchRequired || (host.settings.desiredMode && host.settings.desiredMode !== host.settings.effectiveMode)
                        ? "Desired and effective differ. The host has not applied the desired default yet."
                        : "These host defaults do not confirm this game's next stream. Review its choices before Play."
                    color: NovaTheme.secondary
                    font.pixelSize: 14 * host.unit * NovaTheme.fontScale
                }
            }
            SessionSyncActions {
                id: sessionActions
                visible: host.sessionView
                Layout.fillWidth: true; Layout.preferredWidth: 1.1
                Layout.fillHeight: true; Layout.minimumHeight: 0
                session: host.session; profileState: host.status; unit: host.unit
                onReadingFocus: facts.forceActiveFocus()
                onFooterFocus: done.forceActiveFocus()
                onBitrateRequested: sessionBitrate.open()
            }
            NovaScrollColumn {
                id: actions
                visible: !host.readOnlyView
                Layout.fillWidth: true
                Layout.preferredWidth: 1.1
                Layout.fillHeight: true
                Layout.minimumHeight: 0
                spacing: 10 * host.unit
                Copy { text: "DEFAULT DISPLAY"; color: NovaTheme.secondary; font.pixelSize: 12 * host.unit * NovaTheme.fontScale }
                Copy { text: "Choose a mode to save it as this PC's default. This affects future streams from other devices too."; color: NovaTheme.secondary; font.pixelSize: 15 * host.unit * NovaTheme.fontScale }
                Repeater {
                    id: modeButtons
                    model: host.modes
                    NovaButton {
                        required property var modelData
                        required property int index
                        objectName: "host-defaults-mode-" + modelData.id
                        Layout.fillWidth: true
                        unit: host.unit
                        text: modelData.label + (!modelData.available ? " · Unavailable" : modelData.id === host.settings.desiredMode ? " · Desired" : modelData.id === host.settings.effectiveMode ? " · Effective" : "")
                        Accessible.description: modelData.available ? "Save as this PC's default display." : modelData.reason || "Unavailable on this PC."
                        onActiveFocusChanged: if (activeFocus) { host.focusedExtra = ""; host.focusedMode = modelData.id; host.focusedProfile = ""; host.focusedSync = "" }
                        onClicked: if (host.status.canChange && modelData.available) host.controller.selectMode(modelData.id)
                        Keys.onUpPressed: index > 0 ? host.focusMode(index - 1) : done.forceActiveFocus()
                        Keys.onDownPressed: index + 1 < host.modes.length ? host.focusMode(index + 1) : editDefaults.forceActiveFocus()
                        Keys.onLeftPressed: facts.forceActiveFocus()
                    }
                }
                Copy {
                    readonly property var choice: host.modes.find(mode => mode.id === host.focusedMode)
                    text: choice ? (choice.available ? choice.reason : "Unavailable: " + (choice.reason || "This mode cannot be used on this PC.")) : "Refresh reads the host's current defaults."
                    color: choice && !choice.available ? NovaTheme.warning : NovaTheme.secondary
                    font.pixelSize: 15 * host.unit * NovaTheme.fontScale
                }
                NovaButton {
                    id: editDefaults; objectName: "host-edit-defaults"
                    Layout.fillWidth: true; unit: host.unit
                    text: "Edit Nova stream defaults"
                    opacity: host.status.canEditDefaults || activeFocus ? 1 : 0.55
                    onActiveFocusChanged: if (activeFocus) host.focusedExtra = "defaults"
                    onClicked: if (host.status.canEditDefaults && host.settingsProvider) streamEditor.open()
                    Keys.onUpPressed: host.focusMode(host.modes.length - 1)
                    Keys.onDownPressed: host.focusProfile(0)
                    Keys.onLeftPressed: facts.forceActiveFocus()
                }
                Copy { text: "PAIRED-DEVICE PROFILE"; color: NovaTheme.secondary; font.pixelSize: 12 * host.unit * NovaTheme.fontScale }
                Copy {
                    text: "Send or clear the profile for this Nova pairing on the selected PC. Use Polaris changes Nova's defaults on this device, across PCs. Per-game choices stay separate."
                    color: NovaTheme.secondary
                    font.pixelSize: 15 * host.unit * NovaTheme.fontScale
                }
                Repeater {
                    id: profileButtons
                    model: host.profileActions
                    NovaButton {
                        required property var modelData
                        required property int index
                        objectName: "host-profile-" + modelData.id
                        Layout.fillWidth: true
                        unit: host.unit
                        text: modelData.label + (modelData.id === "match" && host.status.profileState === "Matches Nova" ? " · Matched" : "")
                        opacity: modelData.enabled || activeFocus ? 1 : 0.55
                        Accessible.description: modelData.detail
                        onActiveFocusChanged: if (activeFocus) { host.focusedExtra = ""; host.focusedProfile = modelData.id; host.focusedMode = ""; host.focusedSync = "" }
                        onClicked: if (modelData.enabled) host.controller.profileAction(modelData.id)
                        Keys.onUpPressed: index > 0 ? host.focusProfile(index - 1) : editDefaults.forceActiveFocus()
                        Keys.onDownPressed: index + 1 < host.profileActions.length ? host.focusProfile(index + 1) : resumeTimeout.visible ? resumeTimeout.forceActiveFocus() : syncOff.forceActiveFocus()
                        Keys.onLeftPressed: facts.forceActiveFocus()
                    }
                }
                NovaButton {
                    id: resumeTimeout; objectName: "host-resume-timeout"
                    visible: !!host.settings.resumeTimeoutControl
                    Layout.fillWidth: true; unit: host.unit
                    text: "Resume timeout · " + host.timeoutLabel(host.settings.desiredResumeTimeout)
                    opacity: host.status.canChangeResumeTimeout || activeFocus ? 1 : 0.55
                    onActiveFocusChanged: if (activeFocus) host.focusedExtra = "timeout"
                    onClicked: if (host.status.canChangeResumeTimeout) timeoutPicker.open()
                    Keys.onUpPressed: host.focusProfile(host.profileActions.length - 1)
                    Keys.onDownPressed: syncOff.forceActiveFocus()
                    Keys.onLeftPressed: facts.forceActiveFocus()
                }
                Copy { text: "KEEP IN STEP · " + (host.status.keepInStep || "off").toUpperCase(); color: NovaTheme.secondary; font.pixelSize: 14 * host.unit * NovaTheme.fontScale }
                RowLayout {
                    Layout.fillWidth: true
                    NovaButton {
                        id: syncOff
                        objectName: "host-sync-off"
                        Layout.fillWidth: true
                        unit: host.unit
                        text: host.status.keepInStep === "off" ? "Off · Selected" : "Off"
                        opacity: host.status.canDisableSync || activeFocus ? 1 : 0.55
                        onActiveFocusChanged: if (activeFocus) { host.focusedExtra = ""; host.focusedSync = "off"; host.focusedProfile = ""; host.focusedMode = "" }
                        onClicked: if (host.status.canDisableSync) host.controller.setKeepInStep(false)
                        Keys.onRightPressed: syncOn.forceActiveFocus()
                        Keys.onLeftPressed: facts.forceActiveFocus()
                        Keys.onUpPressed: resumeTimeout.visible ? resumeTimeout.forceActiveFocus() : host.focusProfile(host.profileActions.length - 1)
                        Keys.onDownPressed: refresh.forceActiveFocus()
                    }
                    NovaButton {
                        id: syncOn
                        objectName: "host-sync-on"
                        Layout.fillWidth: true
                        unit: host.unit
                        text: host.status.keepInStep === "paused" ? "Resume" : host.status.keepInStep === "on" ? "On · Selected" : "On"
                        opacity: host.status.canEnableSync || activeFocus ? 1 : 0.55
                        onActiveFocusChanged: if (activeFocus) { host.focusedExtra = ""; host.focusedSync = "on"; host.focusedProfile = ""; host.focusedMode = "" }
                        onClicked: if (host.status.canEnableSync) host.controller.setKeepInStep(true)
                        Keys.onLeftPressed: syncOff.forceActiveFocus()
                        Keys.onUpPressed: resumeTimeout.visible ? resumeTimeout.forceActiveFocus() : host.focusProfile(host.profileActions.length - 1)
                        Keys.onDownPressed: refresh.forceActiveFocus()
                    }
                }
            }
        }
        Copy { objectName: "sync-result"; visible: host.sessionView && !!host.live.syncCopy; text: host.live.syncCopy || "" }
        Copy {
            readonly property var choice: host.profileActions.find(action => action.id === host.focusedProfile)
            visible: !!choice || host.focusedSync.length > 0
            text: host.focusedSync.length ? host.status.keepInStepCopy || "" : choice ? choice.detail : ""
            color: NovaTheme.secondary
            font.pixelSize: 14 * host.unit * NovaTheme.fontScale
        }
        Copy {
            objectName: "host-defaults-status"
            text: host.status.copy || "Checking host defaults…"
            color: host.status.phase === "ready" ? NovaTheme.secondary : NovaTheme.warning
            font.pixelSize: 16 * host.unit * NovaTheme.fontScale
        }
        RowLayout {
            Layout.fillWidth: true
            NovaButton {
                id: done
                objectName: "host-defaults-back"
                unit: host.unit
                text: host.backLabel
                Layout.preferredWidth: 240 * host.unit
                onClicked: host.close()
                Keys.onRightPressed: refresh.forceActiveFocus()
                Keys.onUpPressed: host.focusSavedMode()
            }
            Item { Layout.fillWidth: true }
            NovaButton {
                id: refresh
                objectName: "host-defaults-refresh"
                unit: host.unit
                text: host.status.busy ? "Checking…" : "Refresh"
                Layout.preferredWidth: 240 * host.unit
                onClicked: {
                    if (host.sessionView && host.live.canRefreshDiagnostics) host.session.refreshDiagnostics()
                    if (host.status.canRefresh) { host.focusedMode = ""; host.focusedProfile = ""; host.focusedSync = ""; host.focusedExtra = ""; host.controller.refresh() }
                }
                Keys.onLeftPressed: done.forceActiveFocus()
                Keys.onUpPressed: host.focusSavedMode()
            }
        }
    }
    function profile(display, bitrate) {
        if (display === undefined || bitrate === undefined) return "Not verified"
        const dimensions = display ? display.split("x") : []
        return (dimensions.length === 3 ? dimensions[0] + " × " + dimensions[1] + " · " + dimensions[2] + " fps" : "No size override")
            + " · " + (bitrate > 0 ? bitrate / 1000 + " Mbps" : "No bitrate override")
    }
}

import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: bitrate
    objectName: "live-bitrate-popup"
    required property var session
    property real unit: 1
    readonly property var status: session && session.hud ? session.hud : ({})
    readonly property string draftUnits: status.bitrateUnitsMode === "request" ? "Request budget" : status.bitrateUnitsMode === "video" ? "Video bitrate" : "Bitrate units unavailable"
    readonly property bool canWriteBitrate: status.hostFresh === true && status.canSetBitrate === true && !status.tuningBusy && !status.hostRefreshing
    property int draftKbps: 20000
    property string error: ""
    property bool bitratePage: true
    property string confirmationVersion: ""
    property bool confirmationEnabled: false
    readonly property bool canConfirmTuning: status.hostFresh === true && status.tuningKnown === true
        && status.canTune === true && !status.tuningBusy && !status.hostRefreshing
        && !!status.tuningReviewVersion && status.tuningReviewVersion === confirmationVersion
        && status.tuningEnabled === confirmationEnabled
    function openDetails(focusBitrate) { bitratePage = focusBitrate; open() }
    function back() { if (tuningConfirmation.opened) tuningConfirmation.close(); else close() }
    function metric(key, suffix) {
        const value = status[key]
        return status.fresh === true && value !== undefined && value !== "--" && value !== ""
            ? value + (suffix || "") : "Unavailable"
    }
    function hostRate(key) { return status.hostFresh === true ? format(status[key] || 0) : "Unavailable" }
    function focusBitrate() { if (decrease.enabled) decrease.forceActiveFocus(); else bitrateTab.forceActiveFocus() }
    function selectPage(isBitrate) {
        bitratePage = isBitrate
        scroll.contentY = 0
        if (isBitrate) focusBitrate(); else streamTab.forceActiveFocus()
    }
    function requestTuningConfirmation() {
        if (status.hostFresh !== true || !status.canTune || status.tuningBusy || !status.tuningKnown || !status.tuningReviewVersion) return
        confirmationVersion = status.tuningReviewVersion
        confirmationEnabled = status.tuningEnabled
        tuningConfirmation.open()
    }
    readonly property int minimumKbps: status.minimumBitrateKbps || 1000
    readonly property int maximumKbps: status.maximumBitrateKbps || 300000
    readonly property int suggestedKbps: status.bitrateUnitsMode === "request" ? Math.min(status.suggestedBitrateKbps || 0, maximumKbps, 300000) : 0
    function clamp(value) { return Math.max(minimumKbps, Math.min(maximumKbps, Math.round(value))) }
    function adjust(delta) { if (!status.tuningBusy) draftKbps = clamp(draftKbps + delta) }
    function format(kbps) { return kbps > 0 ? (kbps / 1000).toFixed(kbps % 1000 ? 1 : 0) + " Mbps" : "Unavailable" }
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(640 * unit, (parent ? parent.width : 1280) - 32)
    height: Math.min(720 * unit, (parent ? parent.height : 800) - 48)
    padding: 24 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    onAboutToShow: { error = ""; draftKbps = clamp(status.requestedBitrateKbps > 0 ? status.requestedBitrateKbps : status.appliedBitrateKbps > 0 ? status.appliedBitrateKbps : 20000) }
    onOpened: if (bitratePage) focusBitrate(); else streamTab.forceActiveFocus()
    onClosed: tuningConfirmation.close()
    background: Rectangle { color: NovaTheme.panel; radius: 12 * bitrate.unit; border.color: NovaTheme.divider }
    contentItem: NovaScrollColumn {
        id: scroll
        spacing: 14 * bitrate.unit
        Label { text: "Stream details"; color: NovaTheme.text; font.pixelSize: 28 * bitrate.unit * NovaTheme.fontScale; font.bold: true }
        RowLayout {
            Layout.fillWidth: true
            NovaButton {
                id: streamTab; objectName: "stream-details-overview"
                Layout.fillWidth: true; unit: bitrate.unit
                text: "Stream / Live Tuning"
                onClicked: bitrate.selectPage(false)
                Keys.onRightPressed: bitrateTab.forceActiveFocus()
                Keys.onDownPressed: if (bitrate.bitratePage) bitrate.focusBitrate(); else hostToggle.forceActiveFocus()
            }
            NovaButton {
                id: bitrateTab; objectName: "stream-details-bitrate"
                Layout.fillWidth: true; unit: bitrate.unit
                text: "Bitrate"
                onClicked: bitrate.selectPage(true)
                Keys.onLeftPressed: streamTab.forceActiveFocus()
                Keys.onDownPressed: if (bitrate.bitratePage && decrease.enabled) decrease.forceActiveFocus(); else if (!bitrate.bitratePage) hostToggle.forceActiveFocus(); else done.forceActiveFocus()
            }
        }
        ColumnLayout {
            visible: !bitrate.bitratePage; Layout.fillWidth: true; spacing: 14 * bitrate.unit
            Label {
                objectName: "stream-details-overview-copy"
                Layout.fillWidth: true; wrapMode: Text.WordWrap; textFormat: Text.PlainText
                text: "Resolution: " + bitrate.metric("resolution") + " · Codec: " + bitrate.metric("codec")
                    + "\nRequested FPS: " + bitrate.metric("requestedFps") + " · Incoming: " + bitrate.metric("incoming", " FPS")
                    + "\n" + (bitrate.status.hostFresh ? bitrate.status.hdrCopy || "HDR unavailable" : "HDR unavailable")
                    + "\nHealth: " + (bitrate.status.hostFresh && bitrate.status.fresh ? bitrate.status.healthLabel || "Unavailable" : "Unavailable · current feedback missing")
                color: NovaTheme.secondary; font.pixelSize: 17 * bitrate.unit * NovaTheme.fontScale
            }
            Label {
                Layout.fillWidth: true; wrapMode: Text.WordWrap; textFormat: Text.PlainText
                text: "Live Tuning · " + (bitrate.status.hostFresh ? bitrate.status.tuningStateCopy || "Unavailable" : "Unavailable")
                    + "\n" + (bitrate.status.hostFresh ? bitrate.status.tuningReasonCopy || "Current host feedback is unavailable." : "Current host feedback is unavailable.")
                color: NovaTheme.text; font.pixelSize: 18 * bitrate.unit * NovaTheme.fontScale
            }
            NovaButton {
                id: hostToggle; objectName: "stream-details-host-tuning"
                Layout.fillWidth: true; unit: bitrate.unit
                text: bitrate.status.tuningBusy ? "Saving host setting…" : "Host Live Tuning · " + (bitrate.status.hostFresh && bitrate.status.tuningKnown && bitrate.status.canTune ? bitrate.status.tuningEnabled ? "Set Off…" : "Set On…" : "Unavailable")
                Accessible.description: bitrate.status.tuningCopy || "Current host permission is unavailable."
                onClicked: bitrate.requestTuningConfirmation()
                Keys.onUpPressed: streamTab.forceActiveFocus()
                Keys.onDownPressed: done.forceActiveFocus()
            }
            Label {
                visible: bitrate.error.length > 0
                Layout.fillWidth: true; wrapMode: Text.WordWrap
                text: bitrate.error; color: NovaTheme.warning; font.pixelSize: 16 * bitrate.unit * NovaTheme.fontScale
            }
            Label {
                Layout.fillWidth: true; wrapMode: Text.WordWrap
                text: "Host setting, shared across clients and future streams. On also resumes automatic adjustment for this stream. Off holds the last confirmed video target. The saved host preference is not reported here. A request is not a confirmed change."
                    + (bitrate.status.canTune ? "" : "\n" + (bitrate.status.tuningCopy || "Current host permission is unavailable."))
                color: NovaTheme.secondary; font.pixelSize: 16 * bitrate.unit * NovaTheme.fontScale
            }
            Label { text: "Recent changes · this connection"; color: NovaTheme.text; font.pixelSize: 20 * bitrate.unit * NovaTheme.fontScale }
            Label {
                objectName: "stream-details-recent-empty"
                Layout.fillWidth: true; wrapMode: Text.WordWrap
                visible: !bitrate.status.hostFresh || !(bitrate.status.recentChanges || []).length
                text: !bitrate.status.hostFresh ? "Recent changes unavailable · current host feedback missing." : "No confirmed changes observed in this connection."
                color: NovaTheme.secondary; font.pixelSize: 16 * bitrate.unit * NovaTheme.fontScale
            }
            Repeater {
                model: bitrate.status.hostFresh ? bitrate.status.recentChanges || [] : []
                Label {
                    required property var modelData
                    Layout.fillWidth: true; wrapMode: Text.WordWrap; textFormat: Text.PlainText
                    text: modelData.actor + " · " + modelData.text + "\n" + modelData.reason
                    color: NovaTheme.secondary; font.pixelSize: 16 * bitrate.unit * NovaTheme.fontScale
                }
            }
            Label {
                Layout.fillWidth: true; wrapMode: Text.WordWrap
                text: "Undo unavailable here. Doctor keeps any supported fresh receipt and its existing Undo action."
                color: NovaTheme.secondary; font.pixelSize: 16 * bitrate.unit * NovaTheme.fontScale
            }
        }
        ColumnLayout {
            visible: bitrate.bitratePage; Layout.fillWidth: true; spacing: 14 * bitrate.unit
            Label {
                Layout.fillWidth: true
                text: "This stream · applies now after encoder confirmation. A fixed target pauses automatic adjustment for this stream and replaces pending Doctor bitrate changes. The saved host preference and Play Setup stay unchanged."
                color: NovaTheme.secondary; wrapMode: Text.WordWrap
                font.pixelSize: 16 * bitrate.unit * NovaTheme.fontScale
            }
            Label {
                objectName: "live-bitrate-applied"
                Layout.fillWidth: true
                text: "Selected request budget (host): " + bitrate.hostRate("selectedRequestBudgetKbps")
                    + "\nEffective request budget: " + bitrate.hostRate("effectiveRequestBudgetKbps")
                    + "\nRequest budget limit: " + bitrate.hostRate("requestLimitKbps")
                    + "\nRequested video target: " + bitrate.hostRate("requestedVideoKbps")
                    + "\nVideo target limit: " + bitrate.hostRate("videoLimitKbps")
                    + "\nEncoder applied video target: " + bitrate.hostRate("appliedBitrateKbps")
                    + (bitrate.status.bitrateRequestKbps > 0 ? "\nYour pending / last request: " + bitrate.format(bitrate.status.bitrateRequestKbps) + " (" + bitrate.draftUnits + ")" : "")
                color: NovaTheme.secondary; wrapMode: Text.WordWrap
                font.pixelSize: 18 * bitrate.unit * NovaTheme.fontScale
            }
            Label {
                objectName: "live-bitrate-pyrowave-guidance"
                visible: bitrate.status.codec === "PyroWave"
                Layout.fillWidth: true
                text: "PyroWave needs a high bitrate for fine detail. Compare picture sharpness and received video bitrate as you adjust it."
                color: NovaTheme.secondary; wrapMode: Text.WordWrap
                font.pixelSize: 16 * bitrate.unit * NovaTheme.fontScale
            }
            Label {
                objectName: "live-bitrate-received"
                Layout.fillWidth: true
                text: "Received video: " + (bitrate.status.fresh === true && typeof bitrate.status.receivedBitrateKbps === "number" && Number.isFinite(bitrate.status.receivedBitrateKbps) && bitrate.status.receivedBitrateKbps >= 0 ? (bitrate.status.receivedBitrateKbps / 1000).toFixed(1) + " Mbps" : "Unavailable")
                color: NovaTheme.secondary; font.pixelSize: 18 * bitrate.unit * NovaTheme.fontScale
                wrapMode: Text.WordWrap
            }
            NovaButton {
                id: suggested
                objectName: "live-bitrate-suggested"
                visible: bitrate.suggestedKbps > 0
                enabled: bitrate.canWriteBitrate
                Layout.fillWidth: true; unit: bitrate.unit
                text: "Use recommended · " + bitrate.format(bitrate.suggestedKbps)
                onClicked: bitrate.draftKbps = bitrate.clamp(bitrate.suggestedKbps)
                Keys.onDownPressed: decrease.forceActiveFocus()
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 12 * bitrate.unit
                NovaButton {
                    id: decrease
                    objectName: "live-bitrate-minus"
                    enabled: bitrate.canWriteBitrate
                    Layout.fillWidth: true; unit: bitrate.unit
                    text: "− 10 Mbps"
                    onClicked: bitrate.adjust(-10000)
                    Keys.onLeftPressed: bitrate.adjust(-10000)
                    Keys.onRightPressed: increase.forceActiveFocus()
                    Keys.onUpPressed: if (suggested.visible) suggested.forceActiveFocus(); else bitrateTab.forceActiveFocus()
                    Keys.onDownPressed: apply.forceActiveFocus()
                }
                Label {
                    objectName: "live-bitrate-draft"
                    Layout.preferredWidth: 140 * bitrate.unit
                    Accessible.name: bitrate.draftUnits + ": " + bitrate.format(bitrate.draftKbps)
                    text: bitrate.format(bitrate.draftKbps)
                    color: NovaTheme.text; font.bold: true
                    font.pixelSize: 22 * bitrate.unit * NovaTheme.fontScale
                    horizontalAlignment: Text.AlignHCenter
                }
                NovaButton {
                    id: increase
                    objectName: "live-bitrate-plus"
                    enabled: bitrate.canWriteBitrate
                    Layout.fillWidth: true; unit: bitrate.unit
                    text: "+ 10 Mbps"
                    onClicked: bitrate.adjust(10000)
                    Keys.onLeftPressed: decrease.forceActiveFocus()
                    Keys.onRightPressed: bitrate.adjust(10000)
                    Keys.onUpPressed: if (suggested.visible) suggested.forceActiveFocus(); else bitrateTab.forceActiveFocus()
                    Keys.onDownPressed: apply.forceActiveFocus()
                }
            }
            Slider {
                objectName: "live-bitrate-slider"
                Layout.fillWidth: true
                from: bitrate.minimumKbps; to: bitrate.maximumKbps; stepSize: 1000; snapMode: Slider.SnapAlways
                value: bitrate.draftKbps
                enabled: bitrate.canWriteBitrate
                activeFocusOnTab: false
                Accessible.name: bitrate.draftUnits + " in kilobits per second"
                onMoved: bitrate.draftKbps = bitrate.clamp(value)
                Keys.onDownPressed: apply.forceActiveFocus()
                Keys.onUpPressed: bitrate.focusBitrate()
            }
            NovaButton {
                id: apply
                objectName: "live-bitrate-apply"
                Layout.fillWidth: true; unit: bitrate.unit
                // Keep the current control focused while the backend is pending or
                // loses permission. The caption explains why another press is inert.
                text: bitrate.status.hostRefreshing ? "Refreshing…" : bitrate.status.tuningBusy ? bitrate.status.bitrateBusy ? "Applying…" : "Saving…"
                    : !bitrate.canWriteBitrate ? "Unavailable" : "Apply " + bitrate.format(bitrate.draftKbps)
                Accessible.description: bitrate.status.bitrateCopy || "Live bitrate is unavailable for this stream."
                contentItem: Column {
                    spacing: 6 * bitrate.unit
                    Text {
                        width: parent.width; text: apply.text; font: apply.font
                        color: apply.activeFocus ? NovaTheme.focusText : NovaTheme.text
                        wrapMode: Text.WordWrap; textFormat: Text.PlainText
                    }
                    Text {
                        objectName: "live-bitrate-status"
                        width: parent.width
                        text: bitrate.error || bitrate.status.bitrateCopy || "Live bitrate is unavailable for this stream."
                        color: apply.activeFocus ? NovaTheme.focusText : bitrate.error ? NovaTheme.warning : NovaTheme.secondary
                        wrapMode: Text.WordWrap; textFormat: Text.PlainText
                        font.pixelSize: 15 * bitrate.unit * NovaTheme.fontScale
                    }
                }
                onClicked: {
                    if (!bitrate.canWriteBitrate) return
                    bitrate.error = session.setFixedBitrate(bitrate.draftKbps) ? "" : "The session changed. Review the current state before trying again."
                }
                Keys.onUpPressed: bitrate.focusBitrate()
                Keys.onDownPressed: done.forceActiveFocus()
            }
        }
        NovaButton {
            id: done
            objectName: "live-bitrate-done"
            Layout.fillWidth: true; unit: bitrate.unit
            text: "Back to Command Center"
            onClicked: bitrate.close()
            Keys.onUpPressed: if (bitrate.bitratePage) apply.forceActiveFocus(); else hostToggle.forceActiveFocus()
        }
    }
    Popup {
        id: tuningConfirmation; objectName: "stream-details-tuning-confirmation"
        parent: bitrate.contentItem
        anchors.centerIn: parent
        width: Math.min(560 * bitrate.unit, bitrate.width - 32)
        height: Math.min(implicitHeight, bitrate.height - 32)
        padding: 20 * bitrate.unit
        modal: true; focus: true; closePolicy: Popup.CloseOnEscape
        onOpened: cancelTuning.forceActiveFocus()
        onClosed: if (bitrate.opened) hostToggle.forceActiveFocus()
        background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
        contentItem: NovaScrollColumn {
            spacing: 14 * bitrate.unit
            Label {
                Layout.fillWidth: true; wrapMode: Text.WordWrap
                text: "Change host Live Tuning?"
                color: NovaTheme.text; font.pixelSize: 24 * bitrate.unit * NovaTheme.fontScale
            }
            Label {
                objectName: "stream-details-tuning-confirmation-copy"
                Layout.fillWidth: true; wrapMode: Text.WordWrap
                text: bitrate.canConfirmTuning
                    ? "This sets the host preference shared across clients and future streams. " + (bitrate.confirmationEnabled ? "Off holds the last confirmed video target." : "On also resumes automatic adjustment for this stream.") + " The saved host preference is not reported by this stream view."
                    : "The host state or permission changed. Go back and review the current stream."
                color: NovaTheme.secondary; font.pixelSize: 17 * bitrate.unit * NovaTheme.fontScale
            }
            NovaButton {
                id: cancelTuning; objectName: "stream-details-tuning-cancel"
                Layout.fillWidth: true; unit: bitrate.unit; text: "Keep current setting"
                onClicked: tuningConfirmation.close()
                Keys.onDownPressed: confirmTuning.forceActiveFocus()
            }
            NovaButton {
                id: confirmTuning; objectName: "stream-details-tuning-confirm"
                Layout.fillWidth: true; unit: bitrate.unit
                text: bitrate.canConfirmTuning ? bitrate.confirmationEnabled ? "Turn Off for host" : "Turn On for host" : "Unavailable · review current state"
                Accessible.description: "Host setting. Requires current owner permission and fresh feedback."
                onClicked: {
                    if (!bitrate.canConfirmTuning) return
                    bitrate.error = bitrate.session.setLiveTuningEnabled(!bitrate.confirmationEnabled) ? "" : "The session changed. Review the current state before trying again."
                    tuningConfirmation.close()
                }
                Keys.onUpPressed: cancelTuning.forceActiveFocus()
            }
        }
    }
}

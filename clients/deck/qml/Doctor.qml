import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: doctor
    objectName: "doctor-popup"
    required property var session
    property real unit: 1
    readonly property var readings: session && session.hud ? session.hud : ({})
    readonly property var finding: readings.hostFresh && readings.doctor ? readings.doctor : ({})
    readonly property bool available: finding.available === true
    readonly property bool hasAction: !!readings.doctorProposal || !!readings.doctorHasReceipt || !!readings.doctorActionMessage
    property int page: 0
    property string error: ""
    property string reportMessage: ""
    property bool reportSaved: false
    anchors.centerIn: Overlay.overlay
    width: Math.min(920 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(720 * unit, (Overlay.overlay ? Overlay.overlay.height : 800) - 32)
    padding: 24 * unit
    modal: true; focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    function metric(key, suffix) {
        const value = readings[key]
        return value && value !== "--" ? value + (suffix || "") : "Unavailable"
    }
    function selectPage(index) { page = index; scroll.contentY = 0 }
    function focusTab() { tabs.itemAt(page).forceActiveFocus() }
    onAboutToShow: { selectPage(0); error = ""; reportMessage = ""; reportSaved = false }
    onOpened: focusTab()
    background: Rectangle { color: NovaTheme.panel; radius: 12 * doctor.unit; border.color: NovaTheme.divider }

    component Copy: Label {
        Layout.fillWidth: true
        textFormat: Text.PlainText; wrapMode: Text.WordWrap
        color: NovaTheme.text
        font.pixelSize: 17 * doctor.unit * NovaTheme.fontScale
    }
    component Heading: Copy { font.pixelSize: 23 * doctor.unit * NovaTheme.fontScale; font.bold: true }
    contentItem: ColumnLayout {
        spacing: 14 * doctor.unit
        RowLayout {
            Layout.fillWidth: true
            Heading { text: "Doctor"; font.pixelSize: 28 * doctor.unit * NovaTheme.fontScale }
            Label {
                objectName: "doctor-freshness"
                text: doctor.readings.diagnosticsRefreshing ? "Refreshing…" : doctor.readings.hostFresh ? "Live" : "Host unavailable"
                color: NovaTheme.secondary; font.pixelSize: 16 * doctor.unit * NovaTheme.fontScale
            }
        }
        RowLayout {
            Layout.fillWidth: true; spacing: 10 * doctor.unit
            Repeater {
                id: tabs
                model: ["Diagnosis", "Evidence", "Session", "Report"]
                NovaButton {
                    required property int index
                    required property string modelData
                    objectName: "doctor-tab-" + index
                    Layout.fillWidth: true; Layout.preferredWidth: 1
                    unit: doctor.unit
                    text: modelData
                    Accessible.description: doctor.page === index ? "Selected page" : "Open " + modelData
                    onClicked: doctor.selectPage(index)
                    Keys.onLeftPressed: tabs.itemAt(Math.max(0, index - 1)).forceActiveFocus()
                    Keys.onRightPressed: tabs.itemAt(Math.min(3, index + 1)).forceActiveFocus()
                    Keys.onDownPressed: reader.forceActiveFocus()
                    Keys.onUpPressed: back.forceActiveFocus()
                    Rectangle { anchors.bottom: parent.bottom; anchors.horizontalCenter: parent.horizontalCenter; width: parent.width * 0.45; height: 3; color: NovaTheme.accent; visible: doctor.page === index && !parent.activeFocus }
                }
            }
        }
        FocusScope {
            id: reader
            objectName: "doctor-reading-pane"
            Layout.fillWidth: true; Layout.fillHeight: true
            activeFocusOnTab: true
            Accessible.role: Accessible.Pane
            Accessible.name: "Doctor " + ["diagnosis", "evidence", "session readings", "support report"][doctor.page]
            Accessible.description: "Use Up and Down to scroll."
            Rectangle { anchors.fill: parent; color: "transparent"; radius: 8; border.color: reader.activeFocus ? NovaTheme.focus : NovaTheme.divider; border.width: reader.activeFocus ? 3 : 1 }
            Keys.onUpPressed: {
                if (scroll.contentY > 0) scroll.contentY = Math.max(0, scroll.contentY - 80 * doctor.unit)
                else doctor.focusTab()
            }
            Keys.onDownPressed: {
                const end = Math.max(0, scroll.contentHeight - scroll.height)
                if (scroll.contentY < end - 1) scroll.contentY = Math.min(end, scroll.contentY + 80 * doctor.unit)
                else if (actions.visible) fix.forceActiveFocus()
                else refresh.forceActiveFocus()
            }
            NovaScrollColumn {
                id: scroll
                objectName: "doctor-scroll"
                anchors.fill: parent; anchors.margins: 16 * doctor.unit
                spacing: 14 * doctor.unit
                ColumnLayout {
                    visible: doctor.page === 0
                    Layout.fillWidth: true; spacing: 14 * doctor.unit
                    Copy { text: doctor.available ? doctor.finding.group + " · Current diagnosis" : "Current diagnosis"; color: NovaTheme.secondary }
                    Heading {
                        objectName: "doctor-title"
                        text: doctor.readings.diagnosticsRefreshing ? "Reading the current session…" : doctor.available ? doctor.finding.title : "Doctor evidence is unavailable"
                    }
                    Copy {
                        visible: !doctor.available
                        text: doctor.readings.diagnosticsRefreshing ? "The current readings will appear here shortly."
                            : "Connect to a paired Polaris host for current Doctor findings. Session shows the readings available on this device."
                        color: NovaTheme.secondary
                    }
                    Copy { visible: doctor.available && !!doctor.finding.highlight; text: "Evidence: " + (doctor.finding.highlight || ""); color: NovaTheme.warning }
                    Copy { visible: doctor.available && !!doctor.finding.highlight; text: "Confidence: " + (doctor.finding.confidence || "unknown"); color: NovaTheme.secondary }
                    Heading { visible: doctor.available; text: "Try first" }
                    Copy { visible: doctor.available; text: doctor.finding.advice || "" }
                    Copy {
                        objectName: "doctor-proposal"
                        visible: doctor.available
                        text: doctor.readings.doctorProposal || "No verified live fix is currently offered. Review the guidance and refresh after more gameplay."
                        color: NovaTheme.secondary
                    }
                }
                ColumnLayout {
                    visible: doctor.page === 1
                    Layout.fillWidth: true; spacing: 14 * doctor.unit
                    Heading { text: "Measured evidence" }
                    Copy { visible: !doctor.available || !(doctor.finding.evidence || []).length; text: "No current supported evidence is available."; color: NovaTheme.secondary }
                    Repeater {
                        model: doctor.available ? doctor.finding.evidence || [] : []
                        ColumnLayout {
                            required property var modelData
                            Layout.fillWidth: true; spacing: 6 * doctor.unit
                            Copy { text: modelData.group + " · " + modelData.title; font.bold: true }
                            Copy { text: modelData.grade + (modelData.reading ? " · " + modelData.reading : ""); color: modelData.tone === "warning" ? NovaTheme.warning : NovaTheme.text }
                            Copy { text: modelData.detail; color: NovaTheme.secondary }
                            Copy { text: "Source: " + modelData.source; color: NovaTheme.secondary; font.pixelSize: 14 * doctor.unit * NovaTheme.fontScale }
                            Rectangle { Layout.fillWidth: true; height: 1; color: NovaTheme.divider }
                        }
                    }
                }
                ColumnLayout {
                    visible: doctor.page === 3
                    Layout.fillWidth: true; spacing: 14 * doctor.unit
                    Heading { text: "Save a support report" }
                    Copy { text: "Includes current HOST, NET and CLIENT readings, measured Doctor evidence and the change status. Missing readings are marked unavailable." }
                    Copy { text: "Names, addresses, pairing details, session IDs and raw logs stay private. Nothing is uploaded."; color: NovaTheme.secondary }
                    Copy { text: "Find the saved JSON file in Documents → Nova reports in Desktop Mode. Include a Polaris support bundle from your PC when reporting a host issue."; color: NovaTheme.secondary }
                    Copy { objectName: "doctor-report-result"; visible: !!doctor.reportMessage; text: doctor.reportMessage; color: doctor.reportSaved ? NovaTheme.text : NovaTheme.warning }
                }
                ColumnLayout {
                    visible: doctor.page === 2
                    Layout.fillWidth: true; spacing: 14 * doctor.unit
                    Heading { text: "HOST" }
                    Copy { objectName: "doctor-host-readings"; text: "Encoder applied: " + doctor.metric("appliedBitrate", "bps") + "\nQuality ceiling: " + doctor.metric("qualityLimit", "bps") + "\nHost processing: " + doctor.metric("host") }
                    Heading { text: "NET" }
                    Copy { text: "Received video: " + doctor.metric("bitrate", "bps") + "\nRound trip: " + doctor.metric("rtt") + "\nRTT variation: " + doctor.metric("jitter") }
                    Heading { text: "CLIENT" }
                    Copy { text: "Incoming: " + doctor.metric("incoming", " FPS") + "\nDecoded: " + doctor.metric("decoded", " FPS") + "\nComposed: " + doctor.metric("fps", " FPS") + "\nStream: " + doctor.metric("resolution") + " · " + doctor.metric("codec") }
                    Copy { text: "Composed FPS measures app draws. Panel presentation, decoder latency and client media loss are not measured in this preview."; color: NovaTheme.secondary }
                }
            }
        }
        Copy {
            objectName: "doctor-action-result"
            visible: doctor.page !== 3 && !!doctor.readings.doctorActionMessage
            text: doctor.readings.doctorActionMessage || ""
            color: doctor.readings.doctorActionState === "resolved" ? NovaTheme.text : NovaTheme.secondary
        }
        RowLayout {
            id: actions
            visible: doctor.page !== 3 && doctor.hasAction
            Layout.fillWidth: true; spacing: 12 * doctor.unit
            NovaButton {
                id: fix
                objectName: "doctor-fix"
                Layout.fillWidth: true; Layout.preferredWidth: 1; unit: doctor.unit
                text: doctor.readings.doctorActionBusy ? "Working…" : doctor.readings.doctorHasReceipt ? (doctor.readings.doctorCheckLabel || "Check result")
                    : doctor.readings.doctorActionLabel || "No live fix"
                Accessible.description: doctor.readings.doctorHasReceipt ? "Check the outcome of the existing Doctor change" : doctor.readings.doctorProposal || "No live fix is available"
                onClicked: {
                    if (doctor.readings.doctorActionBusy) return
                    if (doctor.readings.doctorHasReceipt) {
                        if (!doctor.readings.doctorCanCheck) return
                        doctor.error = session.checkDoctorResult() ? "" : "The session changed. Refresh before checking the result."
                    } else {
                        if (!doctor.readings.doctorCanApply) return
                        doctor.error = session.applyDoctorFix() ? "" : "The proposed fix changed. Refresh to review it."
                    }
                }
                opacity: doctor.readings.doctorCanApply || doctor.readings.doctorCanCheck || activeFocus ? 1 : 0.6
                Keys.onUpPressed: reader.forceActiveFocus()
                Keys.onRightPressed: undo.forceActiveFocus()
                Keys.onDownPressed: refresh.forceActiveFocus()
            }
            NovaButton {
                id: undo
                objectName: "doctor-undo"
                Layout.fillWidth: true; Layout.preferredWidth: 1; unit: doctor.unit
                text: doctor.readings.doctorCanUndo ? "Undo" : "Undo unavailable"
                Accessible.description: doctor.readings.doctorCanUndo ? "Restore the live settings from before this Doctor run" : "No current Undo is available"
                onClicked: {
                    if (!doctor.readings.doctorCanUndo || doctor.readings.doctorActionBusy) return
                    doctor.error = session.undoDoctorFix() ? "" : "The session changed. Refresh before Undo."
                }
                opacity: doctor.readings.doctorCanUndo || activeFocus ? 1 : 0.6
                Keys.onUpPressed: reader.forceActiveFocus()
                Keys.onLeftPressed: fix.forceActiveFocus()
                Keys.onDownPressed: back.forceActiveFocus()
            }
        }
        Copy { visible: doctor.error.length > 0; text: doctor.error; color: NovaTheme.warning }
        RowLayout {
            Layout.fillWidth: true; spacing: 12 * doctor.unit
            NovaButton {
                id: refresh
                objectName: "doctor-refresh"
                Layout.fillWidth: true; Layout.preferredWidth: 1; unit: doctor.unit
                text: doctor.page === 3 ? (doctor.reportSaved ? "Report saved" : "Save report") : doctor.readings.diagnosticsRefreshing ? "Refreshing…" : doctor.readings.canRefreshDiagnostics ? "Refresh readings" : "Refresh unavailable"
                Accessible.description: doctor.page === 3 ? "Save sanitized readings locally without uploading" : doctor.readings.canRefreshDiagnostics ? "Read current session diagnostics" : "Waiting for the paired host or a pending operation"
                onClicked: {
                    if (doctor.page === 3) {
                        if (doctor.reportSaved) return
                        const result = session.exportSupportReport()
                        doctor.reportSaved = result.saved === true
                        doctor.reportMessage = result.message || "The report couldn't be saved. Try again."
                        return
                    }
                    if (!doctor.readings.canRefreshDiagnostics || doctor.readings.diagnosticsRefreshing) return
                    doctor.error = session.refreshDiagnostics() ? "" : "The session changed. Review the connection before refreshing."
                }
                Keys.onUpPressed: { if (actions.visible) fix.forceActiveFocus(); else reader.forceActiveFocus() }
                Keys.onRightPressed: back.forceActiveFocus()
                Keys.onDownPressed: back.forceActiveFocus()
            }
            NovaButton {
                id: back
                objectName: "doctor-back"
                Layout.fillWidth: true; Layout.preferredWidth: 1; unit: doctor.unit
                text: "Back"
                onClicked: doctor.close()
                Keys.onUpPressed: { if (actions.visible) undo.forceActiveFocus(); else reader.forceActiveFocus() }
                Keys.onLeftPressed: refresh.forceActiveFocus()
                Keys.onDownPressed: doctor.focusTab()
            }
        }
    }
}

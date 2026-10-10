import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Mark freezes local evidence. Save is a separate explicit action on those bytes.
Item {
    id: owner
    required property var session
    property real unit: 1
    readonly property alias opened: report.opened
    property string markError: ""
    signal closed()
    function open() {
        const marked = session.markMoment()
        if (marked.status !== "marked") {
            markError = marked.status === "inactive"
                ? "Open this during an active stream with Nova focused and the Command Center shown. Nothing was marked."
                : marked.status === "too_large" ? "This moment couldn't be kept within the local size limit. Nothing was marked."
                : "Couldn't mark this moment. Nothing was marked; return to the active stream and try again."
            return
        }
        markError = ""
        report.summary = marked; report.message = ""; report.completed = false
        report.open()
    }
    function closeAll() { markError = ""; report.close() }
    function back() { report.close() }
    component Copy: Label {
        Layout.fillWidth: true
        textFormat: Text.PlainText; wrapMode: Text.WordWrap
        color: NovaTheme.text
        font.pixelSize: 17 * owner.unit * NovaTheme.fontScale
    }
    Popup {
        id: report
        objectName: "moment-report-popup"
        property var summary: ({})
        property string message: ""
        property bool completed: false
        readonly property bool canSave: !completed && owner.session && owner.session.canSaveMoment === true
        anchors.centerIn: Overlay.overlay
        width: Math.min(820 * owner.unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
        height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 32)
        padding: 24 * owner.unit
        modal: true; focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        function resultText(status) {
            if (status === "saved") return "Saved in Documents → Nova reports. You can share the JSON file from Desktop Mode."
            if (status === "saved_durability_unknown") return "The report file was saved, but Nova couldn't confirm storage cleanup or durability. It may be lost after a power failure. No second copy will be saved from this moment."
            if (status === "too_large") return "This report exceeds the local size limit. Nothing was saved."
            if (status === "storage_unavailable") return "The Documents folder is unavailable. The frozen moment is still here; check local storage before trying Save again."
            if (status === "storage_unsafe") return "Nova's reports folder isn't private, so the moment wasn't saved. The folder was left as it is."
            if (status === "storage_busy") return "Another Nova window is saving a moment. Nothing was saved; you can try Save again."
            if (status === "storage_full") return "There are already 32 moment report files. Move an older report from Documents → Nova reports in Desktop Mode, then try Save again. Nova won't delete your reports."
            if (status === "write_failed") return "Couldn't write the report. The frozen moment is still here; check local storage before trying Save again."
            if (status === "inactive") return "Return focus to Nova during this active stream with the Command Center shown to save the frozen moment."
            if (status === "stream_changed") return "The stream ended or changed. This moment can't be saved into another run; close this page and mark a new moment."
            if (status === "no_pending") return "This moment is no longer pending. Close this page and mark another moment."
            return "Couldn't mark this moment. Return to the active stream and try again."
        }
        onOpened: keepAction.forceActiveFocus()
        onCanSaveChanged: if (!canSave && saveAction.activeFocus) keepAction.forceActiveFocus()
        onClosed: {
            if (summary.token) owner.session.discardMoment(summary.token)
            summary = ({})
            owner.closed()
        }
        background: Rectangle { color: NovaTheme.panel; radius: 12 * owner.unit; border.color: NovaTheme.divider }
        contentItem: NovaScrollColumn {
            spacing: 14 * owner.unit
            Copy { text: "Report this moment"; font.pixelSize: 28 * owner.unit * NovaTheme.fontScale; font.bold: true }
            Copy {
                objectName: "moment-report-storage-copy"
                color: NovaTheme.secondary
                text: "Frozen locally when you marked it. Save writes that moment on this device; nothing is sent automatically. No logs, identities, host record or Doctor verdict are included. Picture, audio and controls aren't checked."
            }
            Copy { objectName: "moment-report-marked-time"; text: "Marked " + (report.summary.markedAtUtc || "") + " (UTC)." }
            Copy {
                objectName: "moment-report-state"
                text: report.summary.state === "current" ? "Current readings were frozen at the mark."
                    : report.summary.state === "stale" ? "Readings were stale at the mark. The snapshot contains no observed readings; earlier samples keep their own receipt age and state."
                    : "No current sample was available at the mark. The snapshot contains no observed readings."
            }
            Copy {
                objectName: "moment-report-window"
                color: NovaTheme.secondary
                text: (report.summary.entries || 0) + " samples received" +
                    (report.summary.spanMs !== null && report.summary.spanMs !== undefined
                        ? " over " + (report.summary.spanMs / 1000).toFixed(1) + " s" : "") +
                    " before the mark (sampled readings, not continuous)."
            }
            Copy {
                objectName: "moment-report-focus-copy"
                visible: !report.completed && !report.canSave
                text: "Save is unavailable while Nova is unfocused or the stream is finishing. The frozen moment is kept while focus is away."
                color: NovaTheme.secondary
            }
            Copy { objectName: "moment-report-message"; visible: report.message.length > 0; text: report.message; color: report.completed ? NovaTheme.text : NovaTheme.warning }
            NovaButton {
                id: keepAction; objectName: "moment-report-keep-playing"
                Layout.fillWidth: true; unit: owner.unit
                visible: !report.completed
                text: "Keep playing"
                onClicked: report.close()
                Keys.onDownPressed: if (saveAction.enabled) saveAction.forceActiveFocus()
                Keys.onUpPressed: if (saveAction.enabled) saveAction.forceActiveFocus()
            }
            NovaButton {
                id: saveAction; objectName: "moment-report-save"
                Layout.fillWidth: true; unit: owner.unit
                visible: !report.completed; enabled: report.canSave
                text: "Save moment report"
                onClicked: {
                    const result = owner.session.saveMoment(report.summary.token)
                    report.message = report.resultText(result.status)
                    report.completed = result.status === "saved" || result.status === "saved_durability_unknown"
                    if (report.completed) Qt.callLater(function() { if (report.opened) doneAction.forceActiveFocus() })
                    else if (!report.canSave) keepAction.forceActiveFocus()
                }
                Keys.onUpPressed: keepAction.forceActiveFocus()
                Keys.onDownPressed: keepAction.forceActiveFocus()
            }
            NovaButton {
                id: doneAction; objectName: "moment-report-done"
                Layout.fillWidth: true; unit: owner.unit
                visible: report.completed
                text: "Done"
                onClicked: report.close()
            }
        }
    }
}

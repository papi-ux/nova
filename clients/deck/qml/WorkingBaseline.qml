import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Working baseline (#282 Linux). Saving needs an explicit confirmation that picture,
// audio and controls work; the session freezes fresh readings at that press. Compare
// is read-only and descriptive. Stored locally on this device; nothing is sent.
// The page and its two confirmations are sibling popups owned by this item.
Item {
    id: owner
    required property var session
    property real unit: 1
    readonly property alias opened: baseline.opened
    readonly property bool confirming: saveConfirmation.opened || clearConfirmation.opened
    signal closed()
    function open() { baseline.open() }
    function closeAll() { saveConfirmation.close(); clearConfirmation.close(); baseline.close() }
    // Controller Back: cancel an open confirmation first (the safe choice), else close the page.
    function back() { if (confirming) { saveConfirmation.close(); clearConfirmation.close() } else baseline.close() }
    component Copy: Label {
        Layout.fillWidth: true
        textFormat: Text.PlainText; wrapMode: Text.WordWrap
        color: NovaTheme.text
        font.pixelSize: 17 * owner.unit * NovaTheme.fontScale
    }
    Popup {
        id: baseline
        objectName: "working-baseline-popup"
        readonly property var session: owner.session
        readonly property real unit: owner.unit
        property var status: ({})
        property var comparison: ({})
        property string message: ""
        // Opaque run generation read when a confirmation opens; memory only.
        property string confirmationGeneration: ""
        readonly property bool writable: ["ready", "no_baseline", "baseline_unreadable"].indexOf(status.reason) >= 0
        readonly property bool canSave: status.available === true && writable && status.canSave === true
        readonly property bool hasBaseline: status.available === true && status.hasBaseline === true
        anchors.centerIn: Overlay.overlay
        width: Math.min(820 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
        height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 32)
        padding: 24 * unit
        modal: true; focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        function refresh() { status = session ? session.workingBaselineStatus() : ({}) }
        // While open, follow the session's HUD and state so Save tracks fresh readings.
        // Status only: no write, no other authority, and confirmationGeneration is kept.
        function liveRefresh() {
            const hadSaveFocus = saveAction.activeFocus
            refresh()
            if (hadSaveFocus && !canSave) (compareAction.enabled ? compareAction : clearAction.enabled ? clearAction : backAction).forceActiveFocus()
        }
        function reasonText(reason) {
            if (reason === "no_scope") return "This stream has no stable game identity, so a baseline can't be kept for it."
            if (reason === "inactive") return "Open this during an active stream with the Command Center shown."
            if (reason === "no_baseline") return "No baseline for this PC and game yet."
            if (reason === "baseline_unreadable") return "The saved baseline couldn't be read. You can replace or clear it."
            if (reason === "storage_unsafe") return "Nova's baseline folder isn't private, so it wasn't used."
            if (reason === "storage_unavailable") return "Local storage is unavailable."
            if (reason === "index_unreadable") return "Nova's baseline file couldn't be read. It was left as it is, and baselines can't be saved or cleared until it can be read."
            return ""
        }
        function resultText(result) {
            if (result === "saved") return "Saved on this device."
            if (result === "cleared") return "Baseline cleared."
            if (result === "no_baseline") return "There was no baseline to clear."
            if (result === "stale_readings") return "Readings aren't current yet. Nothing was saved."
            if (result === "stream_changed") return "The stream changed. Nothing was changed."
            if (result === "no_scope") return reasonText("no_scope")
            if (result === "storage_full") return "Baseline storage is full. Clear an older baseline first."
            if (result === "storage_busy") return "Another Nova window is updating baselines. Try again."
            if (result === "index_unreadable") return reasonText("index_unreadable")
            return "Couldn't update this device's baseline storage. Nothing was changed."
        }
        onAboutToShow: { comparison = ({}); message = ""; refresh() }
        onOpened: (canSave ? saveAction : hasBaseline ? compareAction : backAction).forceActiveFocus()
        onClosed: if (!owner.confirming) owner.closed()
        background: Rectangle { color: NovaTheme.panel; radius: 12 * baseline.unit; border.color: NovaTheme.divider }

        contentItem: NovaScrollColumn {
            spacing: 14 * baseline.unit
            Copy { text: "Working baseline"; font.pixelSize: 28 * baseline.unit * NovaTheme.fontScale; font.bold: true }
            Copy {
                objectName: "working-baseline-storage-copy"
                color: NovaTheme.secondary
                text: "Stored locally on this device for this PC and game. A baseline only records readings when you say the stream works; it doesn't check the game scene, host build or network route."
            }
            Copy {
                objectName: "working-baseline-status"
                text: baseline.hasBaseline ? "Baseline saved " + baseline.status.markedAtUtc + " (UTC)." : baseline.reasonText(baseline.status.reason)
            }
            Copy {
                objectName: "working-baseline-waiting"
                visible: baseline.status.available === true && baseline.writable && baseline.status.canSave !== true
                color: NovaTheme.secondary
                text: "Waiting for current readings. Save becomes available when they arrive."
            }
            Copy { objectName: "working-baseline-message"; visible: baseline.message.length > 0; text: baseline.message; color: NovaTheme.warning }
            NovaButton {
                id: saveAction
                objectName: "working-baseline-save"
                Layout.fillWidth: true; unit: baseline.unit
                enabled: baseline.canSave
                text: baseline.hasBaseline ? "Replace baseline" : "Save as working"
                onClicked: if (baseline.canSave) {
                    baseline.refresh()
                    baseline.confirmationGeneration = baseline.status.generation || ""
                    saveConfirmation.open()
                }
                Keys.onDownPressed: (compareAction.enabled ? compareAction : clearAction.enabled ? clearAction : backAction).forceActiveFocus()
                Keys.onUpPressed: backAction.forceActiveFocus()
            }
            NovaButton {
                id: compareAction
                objectName: "working-baseline-compare"
                Layout.fillWidth: true; unit: baseline.unit
                enabled: baseline.hasBaseline
                text: "Compare with now"
                onClicked: if (baseline.hasBaseline) baseline.comparison = baseline.session.compareWorkingBaseline()
                Keys.onUpPressed: (saveAction.enabled ? saveAction : backAction).forceActiveFocus()
                Keys.onDownPressed: (clearAction.enabled ? clearAction : backAction).forceActiveFocus()
            }
            ColumnLayout {
                objectName: "working-baseline-comparison"
                Layout.fillWidth: true
                visible: baseline.comparison.status === "ready"
                spacing: 6 * baseline.unit
                Copy {
                    text: "Baseline marked " + (baseline.comparison.markedAtUtc || "") + " UTC, from readings this interface received at " +
                        (baseline.comparison.sampleReceivedUtc || "") + ". Now: " +
                        (baseline.comparison.currentFresh ? "readings received at " + baseline.comparison.currentSampleReceivedUtc + " UTC." : "current readings are unavailable.")
                }
                // Name the specific known differences and unknowns; no overall verdict.
                Copy {
                    objectName: "working-baseline-context-different"
                    visible: (baseline.comparison.differentFields || []).length > 0
                    color: NovaTheme.warning
                    text: "Different from the baseline: " + (baseline.comparison.differentFields || []).join(", ") + "."
                }
                Copy {
                    objectName: "working-baseline-context-unknown"
                    visible: (baseline.comparison.unknownFields || []).length > 0
                    color: NovaTheme.secondary
                    text: "Unknown on one or both sides: " + (baseline.comparison.unknownFields || []).join(", ") + "."
                }
                Repeater {
                    model: (baseline.comparison.context || []).concat(baseline.comparison.readings || [])
                    Copy {
                        required property var modelData
                        font.pixelSize: 15 * baseline.unit * NovaTheme.fontScale
                        text: modelData.label + ": " + modelData.baseline + " → " + modelData.now +
                            (modelData.state === "same" || modelData.state === "different" ? " (" + modelData.state + ")" : "") +
                            (modelData.scope ? " · " + modelData.scope : "")
                    }
                }
                Copy {
                    color: NovaTheme.secondary
                    text: "Not compared: " + (baseline.comparison.notCompared || []).join(", ") + "."
                }
            }
            Copy { visible: baseline.comparison.status !== undefined && baseline.comparison.status !== "ready"; text: baseline.reasonText(baseline.comparison.status) }
            NovaButton {
                id: clearAction
                objectName: "working-baseline-clear"
                Layout.fillWidth: true; unit: baseline.unit
                enabled: baseline.hasBaseline || baseline.status.reason === "baseline_unreadable"
                text: "Clear baseline"
                onClicked: {
                    baseline.refresh()
                    baseline.confirmationGeneration = baseline.status.generation || ""
                    clearConfirmation.open()
                }
                Keys.onUpPressed: (compareAction.enabled ? compareAction : saveAction.enabled ? saveAction : backAction).forceActiveFocus()
                Keys.onDownPressed: backAction.forceActiveFocus()
            }
            NovaButton {
                id: backAction
                objectName: "working-baseline-back"
                Layout.fillWidth: true; unit: baseline.unit
                text: "Back"
                onClicked: baseline.close()
                Keys.onUpPressed: (clearAction.enabled ? clearAction : compareAction.enabled ? compareAction : saveAction.enabled ? saveAction : backAction).forceActiveFocus()
                Keys.onDownPressed: (saveAction.enabled ? saveAction : backAction).forceActiveFocus()
            }
        }
    }
    Connections {
        target: baseline.opened ? owner.session : null
        ignoreUnknownSignals: true
        function onHudChanged() { baseline.liveRefresh() }
        function onStateChanged() { baseline.liveRefresh() }
    }

    component Confirmation: Popup {
        id: confirmation
        property alias title: titleLabel.text
        property alias body: bodyLabel.text
        property alias safeText: safe.text
        property alias actionText: action.text
        property string safeName: ""
        property string actionName: ""
        signal confirmed()
        anchors.centerIn: Overlay.overlay
        width: Math.min(620 * baseline.unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 48)
        height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
        padding: 24 * baseline.unit
        modal: true; focus: true
        // Escape, B and an outside press all mean the safe choice.
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        onOpened: safe.forceActiveFocus()
        onClosed: if (baseline.opened) Qt.callLater(() => { if (baseline.opened) saveAction.enabled ? saveAction.forceActiveFocus() : backAction.forceActiveFocus() })
        background: Rectangle { color: NovaTheme.panel; radius: 12 * baseline.unit; border.color: NovaTheme.divider }
        contentItem: NovaScrollColumn {
            spacing: 14 * baseline.unit
            Copy { id: titleLabel; font.pixelSize: 24 * baseline.unit * NovaTheme.fontScale; font.bold: true }
            Copy { id: bodyLabel; color: NovaTheme.secondary }
            NovaButton {
                id: safe
                objectName: confirmation.safeName
                Layout.fillWidth: true; unit: baseline.unit
                onClicked: confirmation.close()
                Keys.onDownPressed: action.forceActiveFocus()
            }
            NovaButton {
                id: action
                objectName: confirmation.actionName
                Layout.fillWidth: true; unit: baseline.unit
                onClicked: { confirmation.close(); confirmation.confirmed() }
                Keys.onUpPressed: safe.forceActiveFocus()
            }
        }
    }
    Confirmation {
        id: saveConfirmation
        objectName: "working-baseline-save-confirmation"
        title: "Mark this stream as working?"
        body: "Only save this if picture, audio and controls work for you right now. Nova keeps these readings on this device for this PC and game."
        safeText: "Keep playing"; safeName: "working-baseline-keep-playing"
        actionText: "Save as working"; actionName: "working-baseline-confirm-save"
        onConfirmed: {
            const result = baseline.session.saveWorkingBaseline(baseline.confirmationGeneration)
            baseline.message = baseline.resultText(result.result)
            baseline.comparison = ({})
            baseline.refresh()
        }
    }
    Confirmation {
        id: clearConfirmation
        objectName: "working-baseline-clear-confirmation"
        title: "Clear this baseline?"
        body: "This removes the saved baseline for this PC and game from this device."
        safeText: "Keep baseline"; safeName: "working-baseline-keep"
        actionText: "Clear baseline"; actionName: "working-baseline-confirm-clear"
        onConfirmed: {
            const result = baseline.session.clearWorkingBaseline(baseline.confirmationGeneration)
            baseline.message = baseline.resultText(result.result)
            baseline.comparison = ({})
            baseline.refresh()
        }
    }
}

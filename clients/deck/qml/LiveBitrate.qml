import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: bitrate
    objectName: "live-bitrate-popup"
    required property var session
    property real unit: 1
    readonly property var status: session && session.hud ? session.hud : ({})
    property int draftKbps: 20000
    property string error: ""
    function clamp(value) { return Math.max(1000, Math.min(300000, Math.round(value / 1000) * 1000)) }
    function adjust(delta) { if (!status.tuningBusy) draftKbps = clamp(draftKbps + delta) }
    function format(kbps) { return kbps > 0 ? (kbps / 1000).toFixed(kbps % 1000 ? 1 : 0) + " Mbps" : "Unavailable" }
    anchors.centerIn: Overlay.overlay
    width: Math.min(640 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 24 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    onAboutToShow: { error = ""; draftKbps = clamp(status.appliedBitrateKbps > 0 ? status.appliedBitrateKbps : 20000) }
    onOpened: decrease.forceActiveFocus()
    background: Rectangle { color: NovaTheme.panel; radius: 12 * bitrate.unit; border.color: NovaTheme.divider }
    contentItem: NovaScrollColumn {
        spacing: 14 * bitrate.unit
        Label { text: "Live bitrate"; color: NovaTheme.text; font.pixelSize: 28 * bitrate.unit * NovaTheme.fontScale; font.bold: true }
        Label {
            Layout.fillWidth: true
            text: "Turns Live Tuning off and replaces pending Doctor bitrate changes. Play Setup stays unchanged."
            color: NovaTheme.secondary; wrapMode: Text.WordWrap
            font.pixelSize: 16 * bitrate.unit * NovaTheme.fontScale
        }
        Label {
            objectName: "live-bitrate-applied"
            Layout.fillWidth: true
            text: "Encoder applied: " + bitrate.format(bitrate.status.appliedBitrateKbps || 0)
            color: NovaTheme.secondary; wrapMode: Text.WordWrap
            font.pixelSize: 18 * bitrate.unit * NovaTheme.fontScale
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 12 * bitrate.unit
            NovaButton {
                id: decrease
                objectName: "live-bitrate-minus"
                Layout.fillWidth: true; unit: bitrate.unit
                text: "− 1 Mbps"
                onClicked: bitrate.adjust(-1000)
                Keys.onLeftPressed: bitrate.adjust(-1000)
                Keys.onRightPressed: increase.forceActiveFocus()
                Keys.onDownPressed: apply.forceActiveFocus()
            }
            Label {
                objectName: "live-bitrate-draft"
                Layout.preferredWidth: 140 * bitrate.unit
                text: bitrate.format(bitrate.draftKbps)
                color: NovaTheme.text; font.bold: true
                font.pixelSize: 22 * bitrate.unit * NovaTheme.fontScale
                horizontalAlignment: Text.AlignHCenter
            }
            NovaButton {
                id: increase
                objectName: "live-bitrate-plus"
                Layout.fillWidth: true; unit: bitrate.unit
                text: "+ 1 Mbps"
                onClicked: bitrate.adjust(1000)
                Keys.onLeftPressed: decrease.forceActiveFocus()
                Keys.onRightPressed: bitrate.adjust(1000)
                Keys.onDownPressed: apply.forceActiveFocus()
            }
        }
        Slider {
            objectName: "live-bitrate-slider"
            Layout.fillWidth: true
            from: 1000; to: 300000; stepSize: 1000; snapMode: Slider.SnapAlways
            value: bitrate.draftKbps
            enabled: !bitrate.status.tuningBusy
            activeFocusOnTab: false
            Accessible.name: "Fixed bitrate in kilobits per second"
            onMoved: bitrate.draftKbps = bitrate.clamp(value)
            Keys.onDownPressed: apply.forceActiveFocus()
            Keys.onUpPressed: decrease.forceActiveFocus()
        }
        NovaButton {
            id: apply
            objectName: "live-bitrate-apply"
            Layout.fillWidth: true; unit: bitrate.unit
            // Keep the current control focused while the backend is pending or
            // loses permission. The caption explains why another press is inert.
            text: bitrate.status.hostRefreshing ? "Refreshing…" : bitrate.status.tuningBusy ? bitrate.status.bitrateBusy ? "Applying…" : "Saving…"
                : !bitrate.status.canSetBitrate ? "Unavailable" : "Apply " + bitrate.format(bitrate.draftKbps)
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
                if (!bitrate.status.canSetBitrate || bitrate.status.tuningBusy) return
                bitrate.error = session.setFixedBitrate(bitrate.draftKbps) ? "" : "The session changed. Review the current state before trying again."
            }
            Keys.onUpPressed: decrease.forceActiveFocus()
            Keys.onDownPressed: done.forceActiveFocus()
        }
        NovaButton {
            id: done
            objectName: "live-bitrate-done"
            Layout.fillWidth: true; unit: bitrate.unit
            text: "Back to Command Center"
            onClicked: bitrate.close()
            Keys.onUpPressed: apply.forceActiveFocus()
        }
    }
}

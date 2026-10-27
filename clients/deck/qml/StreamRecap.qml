import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Last Stream (#383 Linux). Presentation only: the session freezes the recap
// when the player ends a stream and clears it when another starts. Showing it
// never takes focus; hiding it while Dismiss is focused hands focus back.
Rectangle {
    id: card
    objectName: "stream-recap"
    property var recap: ({})
    // The owner's fence: after this run ended, for the same reviewed PC and game.
    property bool allowed: false
    property real unit: 1
    signal dismissRequested(string generation)
    signal focusReturned()
    readonly property bool shown: allowed && !!recap && typeof recap.generation === "string" && recap.generation.length > 0
    readonly property var rates: shown && recap.rates ? recap.rates : null
    readonly property var video: shown && recap.video ? recap.video : null
    function focusDismiss() { dismissAction.forceActiveFocus() }
    function count(value) { return Number(value).toLocaleString(Qt.locale(), "f", 0) }
    function duration(ms) {
        const seconds = Math.floor(ms / 1000), minutes = Math.floor(seconds / 60), hours = Math.floor(minutes / 60)
        if (seconds < 60) return seconds + " s"
        if (minutes < 60) return minutes + " min " + seconds % 60 + " s"
        return hours + " h " + minutes % 60 + " min"
    }
    function perFrame(reading, absent, extra) {
        return reading ? { value: reading.meanMs.toFixed(1) + " ms avg", detail: count(reading.frames) + " frames" + (extra || "") }
            : { value: "Unknown", detail: absent }
    }
    readonly property var tiles: !shown ? [] : [
        { key: "received", label: "Video received",
          value: rates ? rates.receivedFps.toFixed(1) + " fps" : "Unknown",
          detail: rates ? "Decoded " + rates.decodedFps.toFixed(1) + " fps over " + duration(rates.spanMs) : "No continuous readings" },
        { key: "payload", label: "Video payload",
          value: rates ? rates.receivedVideoMbps.toFixed(1) + " Mbps" : "Unknown",
          detail: rates ? "Received, not encoder bitrate" : "No continuous readings" },
        { key: "missing", label: "Missing frames",
          value: recap.missingFrames ? recap.missingFrames.percent.toFixed(1) + "%" : "Unknown",
          detail: recap.missingFrames ? count(recap.missingFrames.lost) + " of " + count(recap.missingFrames.expected)
              + (recap.missingFrames.partial ? " since video restarted" : " expected") : "No current frame count" },
        Object.assign({ key: "host", label: "PC processing" }, perFrame(recap.hostProcessing, "Not reported by the PC")),
        Object.assign({ key: "work", label: "Decoder submit" }, perFrame(recap.videoWork, "Not measured",
            recap.videoWork && recap.refusedFrames > 0 ? " · " + count(recap.refusedFrames) + " refused" : ""))
    ]
    visible: false
    Component.onCompleted: visible = shown
    // Visibility follows shown only here, after focus is read, so Dismiss can't
    // drop focus onto nothing. Reduce Motion (zero duration) shows it at once.
    onShownChanged: {
        if (shown) {
            visible = true
            if (NovaTheme.motionDuration > 0) appear.restart()
        } else {
            const hadFocus = dismissAction.activeFocus
            appear.stop(); opacity = 1; visible = false
            if (hadFocus) focusReturned()
        }
    }
    NumberAnimation { id: appear; target: card; property: "opacity"; from: 0; to: 1; duration: NovaTheme.motionDuration; easing.type: NovaTheme.motionEasing }
    implicitHeight: content.implicitHeight + 2 * padding
    readonly property real padding: 20 * unit
    radius: 16 * unit
    color: NovaTheme.panel
    border.color: NovaTheme.divider; border.width: NovaTheme.highContrast ? 2 : 1
    Accessible.role: Accessible.Grouping
    Accessible.name: "Last stream"
    Rectangle {
        anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
        anchors.leftMargin: card.radius; anchors.rightMargin: card.radius
        height: 3 * card.unit; radius: height / 2
        color: NovaTheme.accent
    }
    component Copy: Label {
        Layout.fillWidth: true
        textFormat: Text.PlainText; wrapMode: Text.WordWrap
        color: NovaTheme.secondary
        font.pixelSize: 15 * card.unit * NovaTheme.fontScale
    }
    NovaScrollColumn {
        id: content
        anchors.fill: parent; anchors.margins: card.padding
        reserveScrollBarSpace: false
        spacing: 12 * card.unit
        RowLayout {
            Layout.fillWidth: true
            spacing: 16 * card.unit
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4 * card.unit
                Copy {
                    objectName: "stream-recap-title"
                    text: "Last stream"
                    color: NovaTheme.text; font.bold: true
                    font.pixelSize: 24 * card.unit * NovaTheme.fontScale
                }
                Copy {
                    objectName: "stream-recap-summary"
                    text: !card.shown ? "" : (card.recap.end === "disconnect"
                        ? "You disconnected without asking the PC to quit the game."
                        : "You ended the stream and asked the PC to close the game.")
                        + " Connected at least " + card.duration(card.recap.measuredForMs) + "."
                }
            }
            NovaButton {
                id: dismissAction
                objectName: "stream-recap-dismiss"
                unit: card.unit
                quiet: true
                text: "Dismiss"
                Layout.alignment: Qt.AlignTop
                onClicked: card.dismissRequested(card.recap.generation)
                Keys.onDownPressed: card.focusReturned()
            }
        }
        GridLayout {
            id: grid
            Layout.fillWidth: true
            columns: width >= 640 * card.unit ? 3 : 2
            columnSpacing: 10 * card.unit; rowSpacing: 10 * card.unit
            Repeater {
                model: card.tiles
                Rectangle {
                    required property var modelData
                    objectName: "stream-recap-" + modelData.key
                    readonly property bool known: modelData.value !== "Unknown"
                    Layout.fillWidth: true; Layout.fillHeight: true
                    Layout.preferredWidth: 1
                    implicitHeight: tile.implicitHeight + 24 * card.unit
                    radius: 10 * card.unit
                    color: NovaTheme.raised
                    border.color: NovaTheme.divider; border.width: NovaTheme.highContrast ? 2 : 1
                    Accessible.role: Accessible.StaticText
                    Accessible.name: modelData.label + ": " + modelData.value + ". " + modelData.detail
                    ColumnLayout {
                        id: tile
                        anchors.left: parent.left; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                        anchors.margins: 12 * card.unit
                        spacing: 2 * card.unit
                        Copy { text: modelData.label; font.pixelSize: 13 * card.unit * NovaTheme.fontScale }
                        Copy {
                            objectName: "stream-recap-" + modelData.key + "-value"
                            text: modelData.value
                            color: known ? NovaTheme.text : NovaTheme.muted
                            font.bold: known
                            font.pixelSize: (known ? 24 : 18) * card.unit * NovaTheme.fontScale
                        }
                        Copy {
                            objectName: "stream-recap-" + modelData.key + "-detail"
                            text: modelData.detail; color: NovaTheme.muted; font.pixelSize: 13 * card.unit * NovaTheme.fontScale
                        }
                    }
                }
            }
        }
        Copy {
            objectName: "stream-recap-video"
            visible: !!card.video
            text: !card.video ? "" : "Video: " + card.video.codec + " · " + card.video.width + "×" + card.video.height
                + (card.video.decoder ? " · " + card.video.decoder : "")
        }
        Copy {
            objectName: "stream-recap-scope"
            color: NovaTheme.muted
            font.pixelSize: 13 * card.unit * NovaTheme.fontScale
            text: (card.shown && card.recap.restarted ? "The video decoder restarted during this stream; totals cover the part after it. " : "")
                + "Measured on this device for this connection. Rates cover the span shown and averages the frames shown. Nothing is saved or sent, and this isn't a quality verdict."
        }
    }
}

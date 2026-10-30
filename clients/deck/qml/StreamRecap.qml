import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Last Stream (#383 Linux). Presentation only: the session freezes the recap
// when the player ends a stream and clears it when another starts. Showing it
// never takes focus; hiding it with any reading focused hands focus back.
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
    function focusReading(index) {
        if (index < 0) focusDismiss()
        else if (index >= metricTiles.count) footer.forceActiveFocus()
        else metricTiles.itemAt(index).forceActiveFocus()
    }
    function ownsFocus() {
        let item = Window.window ? Window.window.activeFocusItem : null
        while (item) {
            if (item === card) return true
            item = item.parent
        }
        return false
    }
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
    readonly property var readings: recap || ({})
    // Every frame of the whole run arrived, as far as its count can say. A count restarted
    // part way through only speaks for the part after it.
    readonly property bool nothingMissing: shown && !!readings.missingFrames && readings.missingFrames.expected > 0
        && readings.missingFrames.lost === 0 && !readings.missingFrames.partial
    // An hour or more of continuous readings with nothing missing (#424).
    readonly property bool clearSkies: nothingMissing && !!rates && rates.spanMs >= 3600000
    readonly property var fpsTrail: shown && readings.fpsTrail && readings.fpsTrail.length >= 2 ? readings.fpsTrail : []
    readonly property string fpsTrailWords: fpsTrail.length === 0 ? ""
        : "Video received over this stream: " + Math.min.apply(null, fpsTrail).toFixed(1) + " to " + Math.max.apply(null, fpsTrail).toFixed(1) + " fps"
    readonly property var tiles: [
        { key: "received", label: "Video received",
          value: rates ? rates.receivedFps.toFixed(1) + " fps" : "Unknown",
          detail: rates ? "Decoded " + rates.decodedFps.toFixed(1) + " fps over " + duration(rates.spanMs) : "No continuous readings" },
        { key: "payload", label: "Video payload",
          value: rates ? rates.receivedVideoMbps.toFixed(1) + " Mbps" : "Unknown",
          detail: rates ? "Received, not encoder bitrate" : "No continuous readings" },
        { key: "missing", label: "Missing frames",
          value: readings.missingFrames ? readings.missingFrames.percent.toFixed(1) + "%" : "Unknown",
          detail: readings.missingFrames ? count(readings.missingFrames.lost) + " of " + count(readings.missingFrames.expected)
              + (readings.missingFrames.partial ? " since video restarted" : " expected") : "No current frame count" },
        Object.assign({ key: "host", label: "PC processing" }, perFrame(readings.hostProcessing, "Not reported by the PC")),
        Object.assign({ key: "work", label: "Decoder submit" }, perFrame(readings.videoWork, "Not measured",
            readings.videoWork && readings.refusedFrames > 0 ? " · " + count(readings.refusedFrames) + " refused" : ""))
    ]
    visible: false
    Component.onCompleted: visible = shown
    // Keep the reading delegates alive while hiding, so any focused descendant
    // can return focus before visibility changes. Reduce Motion shows it at once.
    onShownChanged: {
        if (shown) {
            visible = true
            if (NovaTheme.motionDuration > 0) appear.restart()
        } else {
            const hadFocus = ownsFocus()
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
        objectName: "stream-recap-scroll"
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
                Keys.onDownPressed: card.focusReading(0)
            }
        }
        // At a glance: only what was measured, and none of it moves or takes focus.
        Rectangle {
            objectName: "stream-recap-clear-skies"
            visible: card.clearSkies
            implicitWidth: clearSkiesWords.implicitWidth + 16 * card.unit
            implicitHeight: clearSkiesWords.implicitHeight + 8 * card.unit
            radius: 4 * card.unit
            color: NovaTheme.accent
            Accessible.role: Accessible.StaticText
            Accessible.name: clearSkiesWords.text
            Label {
                id: clearSkiesWords
                anchors.centerIn: parent
                text: "Clear skies"
                color: NovaTheme.window
                font.bold: true
                font.pixelSize: 14 * card.unit * NovaTheme.fontScale
            }
        }
        Copy {
            objectName: "stream-recap-nothing-missing"
            visible: card.nothingMissing
            text: "0.00% frames missing"
            color: NovaTheme.text
        }
        // Static, from zero up to the run's best, so a steady stream reads flat.
        Canvas {
            id: fpsLine
            objectName: "stream-recap-fps-line"
            visible: card.fpsTrail.length >= 2
            Layout.fillWidth: true
            Layout.preferredHeight: 40 * card.unit
            readonly property var values: card.fpsTrail
            readonly property color ink: NovaTheme.accent
            onValuesChanged: requestPaint()
            onInkChanged: requestPaint()
            onWidthChanged: requestPaint()
            Accessible.role: Accessible.Graphic
            Accessible.name: card.fpsTrailWords
            onPaint: {
                const context = getContext("2d")
                context.reset()
                if (values.length < 2) return
                const stroke = 2 * card.unit, best = Math.max(Math.max.apply(null, values), 1)
                context.strokeStyle = ink
                context.lineWidth = stroke
                context.lineCap = "round"; context.lineJoin = "round"
                context.beginPath()
                for (let i = 0; i < values.length; ++i) {
                    const x = i / (values.length - 1) * width
                    const y = stroke / 2 + (1 - values[i] / best) * (height - stroke)
                    if (i === 0) context.moveTo(x, y); else context.lineTo(x, y)
                }
                context.stroke()
            }
        }
        Copy {
            objectName: "stream-recap-fps-words"
            visible: card.fpsTrail.length >= 2
            text: card.fpsTrailWords
        }
        GridLayout {
            id: grid
            Layout.fillWidth: true
            columns: width >= 640 * card.unit ? 3 : 2
            columnSpacing: 10 * card.unit; rowSpacing: 10 * card.unit
            Repeater {
                id: metricTiles
                // Stable delegates preserve focus when a recap is cleared or hidden.
                model: 5
                Rectangle {
                    required property int index
                    readonly property var modelData: card.tiles[index]
                    activeFocusOnTab: true
                    Keys.onUpPressed: card.focusReading(index - 1)
                    Keys.onDownPressed: card.focusReading(index + 1)
                    objectName: "stream-recap-" + modelData.key
                    readonly property bool known: modelData.value !== "Unknown"
                    Layout.fillWidth: true; Layout.fillHeight: true
                    Layout.preferredWidth: 1
                    implicitHeight: tile.implicitHeight + 24 * card.unit
                    radius: 10 * card.unit
                    color: NovaTheme.raised
                    border.color: activeFocus ? NovaTheme.focus : NovaTheme.divider
                    border.width: activeFocus ? 3 : NovaTheme.highContrast ? 2 : 1
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
        Rectangle {
            id: footer
            objectName: "stream-recap-footer"
            Layout.fillWidth: true
            implicitHeight: footerCopy.implicitHeight + 16 * card.unit
            activeFocusOnTab: true
            color: "transparent"; radius: 8 * card.unit
            border.color: activeFocus ? NovaTheme.focus : "transparent"
            border.width: activeFocus ? 3 : 0
            Accessible.role: Accessible.StaticText
            Accessible.name: (videoCopy.visible ? videoCopy.text + ". " : "") + scopeCopy.text
            Keys.onUpPressed: card.focusReading(metricTiles.count - 1)
            Keys.onDownPressed: card.focusReturned()
            ColumnLayout {
                id: footerCopy
                anchors.left: parent.left; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                anchors.margins: 8 * card.unit
                spacing: 12 * card.unit
                Copy {
                    id: videoCopy
                    objectName: "stream-recap-video"
                    visible: !!card.video
                    text: !card.video ? "" : "Video: " + card.video.codec + " · " + card.video.width + "×" + card.video.height
                        + (card.video.decoder ? " · " + card.video.decoder : "")
                }
                Copy {
                    id: scopeCopy
                    objectName: "stream-recap-scope"
                    color: NovaTheme.muted
                    font.pixelSize: 13 * card.unit * NovaTheme.fontScale
                    text: (card.shown && card.recap.restarted ? "The video decoder restarted during this stream; totals cover the part after it. " : "")
                        + "Measured on this device for this connection. Rates cover the span shown and averages the frames shown. Nothing is saved or sent, and this isn't a quality verdict."
                }
            }
        }
    }
}

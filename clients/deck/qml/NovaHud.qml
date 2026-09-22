import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: hud
    objectName: "nova-stream-hud"
    property var readings: ({})
    property real unit: 1
    readonly property real scale: unit * NovaTheme.fontScale
    readonly property string mode: NovaHudPreferences.mode
    readonly property real margin: 12 * unit
    readonly property real availableX: Math.max(0, parent.width - width - 2 * margin)
    readonly property real availableY: Math.max(0, parent.height - height - 2 * margin)
    property bool dragging: false
    property real dragX: 0
    property real dragY: 0
    signal commandCenterRequested()
    function value(key) { return readings[key] === undefined ? "--" : readings[key] }
    function tone(name) {
        return name === "warning" ? NovaTheme.warning : name === "danger" ? NovaTheme.danger
            : name === "stable" ? "#87D9AB" : name === "info" ? NovaTheme.accent : NovaTheme.muted
    }
    x: margin + (dragging ? dragX : availableX * NovaHudPreferences.positionX)
    y: margin + (dragging ? dragY : availableY * NovaHudPreferences.positionY)
    width: Math.min(parent.width - margin * 2, (mode === "debug" ? 390 : mode === "minimal" ? 255 : 460) * scale)
    height: contents.implicitHeight + 20 * scale
    // One panel, no per-fact tiles. Zero opacity removes the panel and border,
    // retaining readable text, as in Android's unboxed HUD.
    Rectangle {
        anchors.fill: parent
        radius: hud.mode === "slim" || hud.mode === "minimal" ? height / 2 : 14 * hud.scale
        color: NovaTheme.alpha(NovaTheme.panel, NovaHudPreferences.panelOpacity / 100)
        border.color: NovaTheme.alpha(NovaTheme.divider, NovaHudPreferences.panelOpacity / 100)
    }
    component Fact: Text {
        property string label: ""
        property string reading: "--"
        text: label + " " + reading
        textFormat: Text.PlainText
        color: NovaTheme.text
        font.pixelSize: 13 * hud.scale
        font.family: "monospace"
        elide: Text.ElideRight
    }
    ColumnLayout {
        id: contents
        anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
        anchors.margins: 10 * hud.scale
        spacing: 7 * hud.scale
        RowLayout {
            Layout.fillWidth: true
            spacing: 8 * hud.scale
            Rectangle { objectName: "hud-health-indicator"; width: 3 * hud.scale; height: 24 * hud.scale; radius: 2; color: hud.tone(hud.readings.healthTone) }
            Text {
                objectName: "hud-fps"
                text: hud.value("fps")
                color: NovaTheme.text; font.pixelSize: 24 * hud.scale; font.bold: true
            }
            Column {
                Text { text: "COMP FPS"; color: NovaTheme.secondary; font.pixelSize: 9 * hud.scale; font.bold: true }
                Text { visible: hud.mode !== "slim"; text: hud.readings.target || ""; color: NovaTheme.secondary; font.pixelSize: 10 * hud.scale }
            }
            Canvas {
                id: sparkline
                Layout.fillWidth: true; Layout.minimumWidth: 32 * hud.scale; Layout.preferredHeight: 22 * hud.scale
                visible: hud.mode !== "minimal"
                property var samples: hud.readings.history || []
                onSamplesChanged: requestPaint()
                onWidthChanged: requestPaint()
                onPaint: {
                    const ctx = getContext("2d")
                    ctx.clearRect(0, 0, width, height)
                    if (samples.length < 2) return
                    const max = Math.max(1, ...samples)
                    ctx.strokeStyle = NovaTheme.accent; ctx.lineWidth = 1.5
                    ctx.beginPath()
                    for (let i = 0; i < samples.length; ++i) {
                        const x = i * width / (samples.length - 1), y = height - 2 - samples[i] / max * (height - 4)
                        if (i) ctx.lineTo(x, y); else ctx.moveTo(x, y)
                    }
                    ctx.stroke()
                }
            }
            Fact { visible: hud.mode === "minimal" || hud.mode === "slim"; label: "RTT"; reading: hud.value("rtt") }
            Fact { visible: hud.mode === "slim"; label: "BIT"; reading: hud.value("bitrate") }
            ColumnLayout {
                visible: hud.mode === "debug"
                Layout.maximumWidth: 145 * hud.scale
                spacing: 3 * hud.scale
                Text {
                    objectName: "hud-tuning"
                    Layout.fillWidth: true
                    text: hud.readings.tuningLabel || "Tuning: Unknown"
                    textFormat: Text.PlainText
                    color: hud.tone(hud.readings.tuningTone)
                    font.pixelSize: 11 * hud.scale; font.bold: true
                    elide: Text.ElideRight
                }
                Text {
                    objectName: "hud-applied-limit"
                    Layout.fillWidth: true
                    text: hud.value("appliedBitrate") + " applied / " + hud.value("qualityLimit") + " limit"
                    textFormat: Text.PlainText
                    color: NovaTheme.secondary
                    font.pixelSize: 9 * hud.scale
                    elide: Text.ElideRight
                }
            }
        }
        Text {
            objectName: "hud-health-label"
            visible: hud.mode === "debug" || hud.mode === "performance"
            Layout.fillWidth: true
            text: hud.readings.healthLabel || "Host readings unavailable"
            textFormat: Text.PlainText
            color: hud.tone(hud.readings.healthTone)
            font.pixelSize: 12 * hud.scale
            wrapMode: Text.WordWrap
        }
        RowLayout {
            visible: hud.mode === "performance"
            Layout.fillWidth: true
            Fact { label: "RTT"; reading: hud.value("rtt"); Layout.fillWidth: true }
            Fact { label: "BIT"; reading: hud.value("bitrate"); Layout.fillWidth: true }
            Fact { label: "RES"; reading: hud.value("resolution"); Layout.fillWidth: true }
            Fact { label: "CODEC"; reading: hud.value("codec"); Layout.fillWidth: true }
        }
        RowLayout {
            visible: hud.mode === "debug"
            Layout.fillWidth: true
            spacing: 12 * hud.scale
            Repeater {
                model: [
                    { title: "HOST", tone: hud.readings.hostTone, facts: [["HOST", hud.value("host")], ["RES", hud.value("resolution")], ["CODEC", hud.value("codec")], ["BIT", hud.value("appliedBitrate")]] },
                    { title: "NET", tone: hud.readings.netTone, facts: [["RTT", hud.value("rtt")], ["JIT", hud.value("jitter")], ["LOSS", "--"], ["IN", hud.value("incoming")], ["VIDEO", hud.value("bitrate")]] },
                    { title: "CLIENT", tone: hud.readings.clientTone, facts: [["DEC FPS", hud.value("decoded")], ["OUT", hud.value("fps")], ["1% LOW", "--"], ["DROPS", "--"]] }
                ]
                ColumnLayout {
                    required property var modelData
                    Layout.fillWidth: true; Layout.preferredWidth: 1; Layout.alignment: Qt.AlignTop
                    spacing: 5 * hud.scale
                    Text { text: modelData.title; color: hud.tone(modelData.tone); font.pixelSize: 12 * hud.scale; font.bold: true }
                    Repeater {
                        model: modelData.facts
                        Fact {
                            required property var modelData
                            Layout.fillWidth: true
                            label: modelData[0]; reading: modelData[1]
                            font.pixelSize: 11 * hud.scale
                        }
                    }
                }
            }
        }
        Text {
            visible: hud.mode === "debug" || hud.mode === "performance"
            Layout.fillWidth: true
            text: hud.readings.truth || "Waiting for stream readings"
            color: NovaTheme.secondary; font.pixelSize: 10 * hud.scale
            wrapMode: Text.WordWrap
        }
    }
    MouseArea {
        id: gesture
        anchors.fill: parent
        propagateComposedEvents: true
        property point start
        property real originX: 0
        property real originY: 0
        property bool moved: false
        property bool held: false
        onPressed: mouse => {
            start = mapToItem(null, mouse.x, mouse.y)
            originX = hud.availableX * NovaHudPreferences.positionX
            originY = hud.availableY * NovaHudPreferences.positionY
            hud.dragX = originX; hud.dragY = originY
            moved = false; held = false
        }
        onPositionChanged: mouse => {
            if (!pressed || held) return
            const point = mapToItem(null, mouse.x, mouse.y)
            const dx = point.x - start.x, dy = point.y - start.y
            if (!moved && Math.hypot(dx, dy) < Qt.styleHints.startDragDistance) return
            moved = true; hud.dragging = true
            hud.dragX = Math.max(0, Math.min(hud.availableX, originX + dx))
            hud.dragY = Math.max(0, Math.min(hud.availableY, originY + dy))
        }
        onReleased: {
            if (moved) NovaHudPreferences.setPosition(hud.availableX ? hud.dragX / hud.availableX : 0, hud.availableY ? hud.dragY / hud.availableY : 0)
            hud.dragging = false
        }
        onCanceled: hud.dragging = false
        onPressAndHold: mouse => {
            mouse.accepted = true
            if (!moved) { held = true; hud.commandCenterRequested() }
        }
        // Only a completed ordinary tap passes to the surface below. Drags
        // and long presses belong to the HUD and never emit that click.
        onClicked: mouse => { mouse.accepted = moved || held }
        onWheel: wheel => { wheel.accepted = false }
    }
}

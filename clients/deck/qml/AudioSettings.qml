import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: audio
    objectName: "audio-settings-popup"
    required property var settingsProvider
    property real unit: 1
    property string error: ""
    readonly property var current: settingsProvider.audioSettings
    anchors.centerIn: Overlay.overlay
    width: Math.min(620 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 28 * unit
    modal: true
    focus: true
    enter: Transition { }
    exit: Transition { }
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    onAboutToShow: error = ""
    onOpened: choices.itemAt(current.channels === 8 ? 2 : current.channels === 6 ? 1 : 0).forceActiveFocus()
    function save(values) {
        error = settingsProvider.saveAudioSettings(values) ? "" : "Couldn't save audio settings. Try again."
    }
    function state() {
        function center(item) {
            if (!item) return { x: 0, y: 0 }
            const point = item.mapToItem(null, item.width / 2, item.height / 2)
            return { x: Math.round(point.x), y: Math.round(point.y) }
        }
        return { opened: opened, settings: current, error: error,
            channels: [0, 1, 2].map(index => center(choices.itemAt(index))),
            host: center(hostAudio), reset: center(resetAudio), done: center(done) }
    }
    background: Rectangle { color: NovaTheme.panel; radius: 12 * unit; border.color: NovaTheme.divider }
    component Copy: Label {
        textFormat: Text.PlainText
        color: NovaTheme.text
        font.pixelSize: 18 * audio.unit * NovaTheme.fontScale
        wrapMode: Text.WordWrap
        Layout.fillWidth: true
    }
    component Action: NovaButton { unit: audio.unit; Layout.fillWidth: true }
    contentItem: NovaScrollColumn {
        spacing: 12 * audio.unit
        Copy { text: "Audio"; font.pixelSize: 28 * audio.unit * NovaTheme.fontScale; font.bold: true }
        Copy { text: "This device · Applies to the next new stream"; color: NovaTheme.secondary }
        Copy { text: "Stereo suits the Deck's speakers and headphones. Surround needs a compatible output; SteamOS controls audio routing."; color: NovaTheme.secondary }
        Repeater {
            id: choices
            model: [{ channels: 2, label: "Stereo" }, { channels: 6, label: "5.1 surround" }, { channels: 8, label: "7.1 surround" }]
            Action {
                required property var modelData
                required property int index
                objectName: "audio-channels-" + modelData.channels
                text: modelData.label + (audio.current.channels === modelData.channels ? " · Selected" : "")
                onClicked: audio.save({ channels: modelData.channels, playHostAudio: audio.current.playHostAudio })
                Keys.onUpPressed: if (index > 0) choices.itemAt(index - 1).forceActiveFocus()
                Keys.onDownPressed: index < 2 ? choices.itemAt(index + 1).forceActiveFocus() : hostAudio.forceActiveFocus()
            }
        }
        Action {
            id: hostAudio
            objectName: "audio-host-playback"
            text: "Play audio on PC: " + (audio.current.playHostAudio ? "On" : "Off")
            onClicked: audio.save({ channels: audio.current.channels, playHostAudio: !audio.current.playHostAudio })
            Keys.onUpPressed: choices.itemAt(2).forceActiveFocus()
            Keys.onDownPressed: resetAudio.forceActiveFocus()
        }
        Copy { text: "Also play through the PC's audio output while streaming."; color: NovaTheme.secondary }
        Copy { visible: audio.error.length > 0; text: audio.error; color: NovaTheme.warning }
        Action {
            id: resetAudio
            objectName: "audio-reset"
            text: "Reset audio defaults"
            onClicked: audio.error = settingsProvider.resetAudioSettings() ? "" : "Couldn't reset audio settings. Try again."
            Keys.onUpPressed: hostAudio.forceActiveFocus()
            Keys.onDownPressed: done.forceActiveFocus()
        }
        Action {
            id: done
            objectName: "audio-done"
            text: "Back to System"
            onClicked: audio.close()
            Keys.onUpPressed: resetAudio.forceActiveFocus()
        }
    }
}

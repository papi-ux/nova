import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: scaling
    objectName: "video-scale-popup"
    required property var settingsProvider
    property real unit: 1
    property string error: ""
    readonly property string current: settingsProvider.videoScaleMode
    readonly property var modes: [
        {id: "fit", title: "Fit", detail: "Show the whole picture. Black bars appear when the picture and screen have different shapes."},
        {id: "fill", title: "Fill", detail: "Fill the screen and crop the outer edges. Keep the picture's proportions."},
        {id: "stretch", title: "Stretch", detail: "Fill the screen by changing the picture's proportions."}
    ]
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(720 * unit, parent ? parent.width - 32 : 720)
    height: Math.min(implicitHeight, parent ? parent.height - 48 : 720)
    padding: 24 * unit
    modal: true; focus: true
    enter: Transition { }
    exit: Transition { }
    closePolicy: Popup.CloseOnEscape
    onAboutToShow: error = ""
    onOpened: focusCurrent()
    function focusCurrent() { choices.itemAt(Math.max(0, modes.findIndex(item => item.id === current))).focusChoice() }
    function save(mode) { error = settingsProvider.setVideoScaleMode(mode) ? "" : "Couldn't save video scaling. Try again." }
    background: Rectangle { color: NovaTheme.panel; radius: 12 * scaling.unit; border.color: NovaTheme.divider }
    component Copy: Label {
        Layout.fillWidth: true; wrapMode: Text.WordWrap; textFormat: Text.PlainText
        color: NovaTheme.secondary; font.pixelSize: 17 * scaling.unit * NovaTheme.fontScale
    }
    contentItem: ColumnLayout {
        spacing: 12 * scaling.unit
        Copy { text: "Video scaling"; color: NovaTheme.text; font.pixelSize: 28 * scaling.unit * NovaTheme.fontScale; font.bold: true }
        Copy { text: "This device · All streams · Applies immediately" }
        NovaScrollColumn {
            Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 0
            reserveScrollBarSpace: true; spacing: 12 * scaling.unit
            Repeater {
                id: choices; model: scaling.modes
                ColumnLayout {
                    required property var modelData
                    required property int index
                    Layout.fillWidth: true; spacing: 6 * scaling.unit
                    function focusChoice() { action.forceActiveFocus() }
                    NovaButton {
                        id: action; objectName: "video-scale-" + modelData.id
                        Layout.fillWidth: true; unit: scaling.unit
                        text: modelData.title + (scaling.current === modelData.id ? " · Selected" : "")
                        Accessible.checkable: true; Accessible.checked: scaling.current === modelData.id
                        Accessible.description: modelData.detail
                        onClicked: scaling.save(modelData.id)
                        Keys.onUpPressed: if (index > 0) choices.itemAt(index - 1).focusChoice(); else done.forceActiveFocus()
                        Keys.onDownPressed: index < 2 ? choices.itemAt(index + 1).focusChoice() : reset.forceActiveFocus()
                    }
                    Copy { text: modelData.detail }
                }
            }
            Copy { text: "HUD and menus keep their size. Stream resolution stays the same." }
        }
        Copy { objectName: "video-scale-error"; visible: scaling.error.length > 0; text: scaling.error; color: NovaTheme.warning }
        RowLayout {
            Layout.fillWidth: true
            NovaButton {
                id: done; objectName: "video-scale-back"; Layout.fillWidth: true; unit: scaling.unit; text: "Back"
                onClicked: scaling.close()
                Keys.onUpPressed: scaling.focusCurrent()
                Keys.onRightPressed: reset.forceActiveFocus()
            }
            NovaButton {
                id: reset; objectName: "video-scale-reset"; Layout.fillWidth: true; unit: scaling.unit; text: "Reset to Fit"
                onClicked: scaling.error = settingsProvider.resetVideoScaleMode() ? "" : "Couldn't reset video scaling. Try again."
                Keys.onUpPressed: choices.itemAt(2).focusChoice()
                Keys.onLeftPressed: done.forceActiveFocus()
                Keys.onDownPressed: done.forceActiveFocus()
            }
        }
    }
}

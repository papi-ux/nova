import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: editor
    objectName: "deadzone-popup"
    required property var settingsProvider
    property real unit: 1
    property int draft: 5
    property string error: ""
    function adjust(delta) { draft = Math.max(-20, Math.min(20, draft + delta)) }
    function format(value) { return (value > 0 ? "+" : "") + value + "%" }
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(700 * unit, parent ? parent.width - 32 : 700)
    height: Math.min(implicitHeight, parent ? parent.height - 48 : 720)
    padding: 24 * unit
    modal: true; focus: true; closePolicy: Popup.CloseOnEscape
    enter: Transition { }
    exit: Transition { }
    onAboutToShow: { draft = settingsProvider.stickDeadzonePercent; error = "" }
    onOpened: decrease.forceActiveFocus()
    background: Rectangle { color: NovaTheme.panel; radius: 12 * editor.unit; border.color: NovaTheme.divider }
    component Copy: Label {
        Layout.fillWidth: true; wrapMode: Text.WordWrap; textFormat: Text.PlainText
        color: NovaTheme.secondary; font.pixelSize: 17 * editor.unit * NovaTheme.fontScale
    }
    contentItem: ColumnLayout {
        spacing: 12 * editor.unit
        Copy { text: "Stick deadzone"; color: NovaTheme.text; font.pixelSize: 28 * editor.unit * NovaTheme.fontScale; font.bold: true }
        Copy { text: "This device · All controllers · Next new stream" }
        NovaScrollColumn {
            Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 0
            reserveScrollBarSpace: true; spacing: 12 * editor.unit
            Copy { text: "Positive values ignore small stick movements near the center. Negative values boost small movements and can amplify drift. Default: 5%." }
            RowLayout {
                Layout.fillWidth: true
                NovaButton {
                    id: decrease; objectName: "deadzone-minus"; unit: editor.unit; Layout.fillWidth: true; text: "− 1%"
                    Accessible.name: "Decrease stick deadzone"
                    onClicked: editor.adjust(-1)
                    Keys.onLeftPressed: editor.adjust(-1)
                    Keys.onRightPressed: increase.forceActiveFocus()
                    Keys.onDownPressed: amount.forceActiveFocus()
                }
                Copy {
                    objectName: "deadzone-value"; text: editor.format(editor.draft); color: NovaTheme.text
                    horizontalAlignment: Text.AlignHCenter; font.bold: true
                    font.pixelSize: 24 * editor.unit * NovaTheme.fontScale
                }
                NovaButton {
                    id: increase; objectName: "deadzone-plus"; unit: editor.unit; Layout.fillWidth: true; text: "+ 1%"
                    Accessible.name: "Increase stick deadzone"
                    onClicked: editor.adjust(1)
                    Keys.onLeftPressed: decrease.forceActiveFocus()
                    Keys.onRightPressed: editor.adjust(1)
                    Keys.onDownPressed: amount.forceActiveFocus()
                }
            }
            Slider {
                id: amount; objectName: "deadzone-slider"
                Layout.fillWidth: true; from: -20; to: 20; stepSize: 1; snapMode: Slider.SnapAlways
                implicitHeight: Math.max(48, 48 * editor.unit)
                value: editor.draft; Accessible.name: "Stick deadzone percent"
                onMoved: editor.draft = Math.round(value)
                Keys.onLeftPressed: editor.adjust(-1)
                Keys.onRightPressed: editor.adjust(1)
                Keys.onUpPressed: decrease.forceActiveFocus()
                Keys.onDownPressed: save.forceActiveFocus()
                TapHandler {
                    acceptedDevices: PointerDevice.TouchScreen
                    onTapped: (point) => {
                        const position = Math.max(0, Math.min(1, (point.position.x - amount.leftPadding - amount.handle.width / 2)
                            / Math.max(1, amount.availableWidth - amount.handle.width)))
                        amount.forceActiveFocus()
                        editor.draft = Math.round(amount.valueAt(amount.mirrored ? 1 - position : position))
                    }
                }
                background: Rectangle {
                    x: amount.leftPadding; y: amount.topPadding + amount.availableHeight / 2 - height / 2
                    width: amount.availableWidth; height: 8 * editor.unit; radius: height / 2; color: NovaTheme.divider
                }
                handle: Rectangle {
                    x: amount.leftPadding + amount.visualPosition * (amount.availableWidth - width)
                    y: amount.topPadding + amount.availableHeight / 2 - height / 2
                    implicitWidth: 28 * editor.unit; implicitHeight: implicitWidth; radius: width / 2
                    color: amount.activeFocus ? NovaTheme.focus : NovaTheme.text
                    border.width: amount.activeFocus ? 3 : 1; border.color: NovaTheme.text
                }
            }
            Copy { text: editor.draft <= 0 ? "At zero or below, a tiny 1% center floor remains. A game's or Steam Input's own deadzone may also affect the result." : "Both sticks use the same radial deadzone. A game's or Steam Input's own deadzone may also affect the result." }
            Copy { text: "Save for your next new stream. Reconnect and wake keep the current stream's choice." }
        }
        Copy { objectName: "deadzone-error"; visible: editor.error.length > 0; text: editor.error; color: NovaTheme.warning }
        RowLayout {
            Layout.fillWidth: true
            NovaButton {
                id: cancel; objectName: "deadzone-cancel"; Layout.fillWidth: true; unit: editor.unit; text: "Cancel"
                onClicked: editor.close()
                Keys.onRightPressed: defaults.forceActiveFocus()
                Keys.onUpPressed: amount.forceActiveFocus()
            }
            NovaButton {
                id: defaults; objectName: "deadzone-default"; Layout.fillWidth: true; unit: editor.unit; text: "Default 5%"
                onClicked: editor.draft = 5
                Keys.onLeftPressed: cancel.forceActiveFocus()
                Keys.onRightPressed: save.forceActiveFocus()
                Keys.onUpPressed: amount.forceActiveFocus()
            }
            NovaButton {
                id: save; objectName: "deadzone-save"; Layout.fillWidth: true; unit: editor.unit; text: "Save"
                onClicked: {
                    if (editor.settingsProvider.setStickDeadzonePercent(editor.draft)) editor.close()
                    else editor.error = "Couldn't save the deadzone. Try again."
                }
                Keys.onLeftPressed: defaults.forceActiveFocus()
                Keys.onUpPressed: amount.forceActiveFocus()
            }
        }
    }
}

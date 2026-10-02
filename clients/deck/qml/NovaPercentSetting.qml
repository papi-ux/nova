import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: editor
    property real unit: 1
    property string title: ""
    property int currentValue: 100
    property int minimumValue: 0
    property int maximumValue: 100
    property int resetValue: 100
    property bool textPreview: false
    readonly property bool validDraft: valueInput.acceptableInput && /^\d+$/.test(valueInput.text)
        && Number(valueInput.text) >= minimumValue && Number(valueInput.text) <= maximumValue
    readonly property int draftValue: validDraft ? Number(valueInput.text) : currentValue
    signal valueSaved(int value)
    anchors.centerIn: Overlay.overlay
    width: Math.min(560 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
    height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 32)
    padding: 20 * unit * NovaTheme.controlScale
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    onOpened: {
        valueInput.text = String(Math.max(minimumValue, Math.min(maximumValue, currentValue)))
        if (decrease.enabled) decrease.forceActiveFocus()
        else increase.forceActiveFocus()
    }
    function adjust(delta) {
        valueInput.text = String(Math.max(minimumValue, Math.min(maximumValue, draftValue + delta)))
    }
    background: Rectangle { color: NovaTheme.panel; radius: 10 * editor.unit; border.color: NovaTheme.divider }
    contentItem: ColumnLayout {
        spacing: 12 * editor.unit * NovaTheme.controlScale
        Label {
            Layout.fillWidth: true
            text: editor.title
            color: NovaTheme.text
            font.pixelSize: 26 * editor.unit * NovaTheme.fontScale
            font.bold: true
            wrapMode: Text.WordWrap
        }
        NovaScrollColumn {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.minimumHeight: 0
            spacing: 12 * editor.unit * NovaTheme.controlScale
            Label {
                Layout.fillWidth: true
                text: "Adjust one percent at a time, or enter a value. Save to apply."
                color: NovaTheme.secondary
                font.pixelSize: 16 * editor.unit * NovaTheme.fontScale
                wrapMode: Text.WordWrap
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 8 * editor.unit * NovaTheme.controlScale
                NovaButton {
                    id: decrease
                    objectName: editor.objectName + "-decrease"
                    unit: editor.unit
                    implicitWidth: 48
                    Layout.minimumWidth: 48
                    Layout.preferredWidth: 48
                    text: "−"
                    Accessible.name: "Decrease " + editor.title + " by one percent"
                    enabled: editor.draftValue > editor.minimumValue
                    onClicked: editor.adjust(-1)
                    KeyNavigation.right: valueInput
                    KeyNavigation.down: reset
                }
                TextField {
                    id: valueInput
                    objectName: editor.objectName + "-value"
                    Layout.fillWidth: true
                    Layout.minimumWidth: 48
                    Layout.minimumHeight: 48
                    horizontalAlignment: Text.AlignHCenter
                    color: NovaTheme.text
                    selectedTextColor: NovaTheme.focusText
                    selectionColor: NovaTheme.focus
                    font.pixelSize: 24 * editor.unit * NovaTheme.fontScale
                    inputMethodHints: Qt.ImhDigitsOnly
                    validator: IntValidator { bottom: editor.minimumValue; top: editor.maximumValue }
                    selectByMouse: true
                    Accessible.name: editor.title + " percent"
                    background: Rectangle {
                        color: NovaTheme.input
                        radius: 6 * editor.unit
                        border.color: valueInput.activeFocus ? NovaTheme.focus : NovaTheme.divider
                        border.width: valueInput.activeFocus ? 2 : 1
                    }
                    KeyNavigation.tab: increase
                    KeyNavigation.backtab: decrease
                    Keys.onUpPressed: editor.adjust(1)
                    Keys.onDownPressed: reset.forceActiveFocus()
                    Keys.onReturnPressed: if (editor.validDraft) save.forceActiveFocus()
                    Keys.onEnterPressed: if (editor.validDraft) save.forceActiveFocus()
                }
                NovaButton {
                    id: increase
                    objectName: editor.objectName + "-increase"
                    unit: editor.unit
                    implicitWidth: 48
                    Layout.minimumWidth: 48
                    Layout.preferredWidth: 48
                    text: "+"
                    Accessible.name: "Increase " + editor.title + " by one percent"
                    enabled: editor.draftValue < editor.maximumValue
                    onClicked: editor.adjust(1)
                    KeyNavigation.left: valueInput
                    KeyNavigation.down: reset
                }
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: preview.implicitHeight + 24 * editor.unit
                color: NovaTheme.alpha(NovaTheme.raised, editor.textPreview ? 1 : editor.draftValue / 100)
                radius: 6 * editor.unit
                Label {
                    id: preview
                    anchors.fill: parent
                    anchors.margins: 12 * editor.unit
                    text: editor.textPreview ? "Nova · Preview text" : "Command Center · Preview"
                    color: NovaTheme.text
                    font.pixelSize: 22 * editor.unit * (editor.textPreview ? editor.draftValue / 100 : NovaTheme.fontScale)
                    wrapMode: Text.WordWrap
                }
            }
            NovaButton {
                id: reset
                objectName: editor.objectName + "-reset"
                unit: editor.unit
                Layout.fillWidth: true
                text: "Reset to " + editor.resetValue + "%"
                onClicked: valueInput.text = String(editor.resetValue)
                KeyNavigation.up: decrease.enabled ? decrease : valueInput
                KeyNavigation.down: save.enabled ? save : cancel
            }
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 8 * editor.unit * NovaTheme.controlScale
            NovaButton {
                id: cancel
                objectName: editor.objectName + "-cancel"
                unit: editor.unit
                Layout.fillWidth: true
                Layout.minimumWidth: 48
                implicitWidth: 48
                text: "Cancel"
                onClicked: editor.close()
                KeyNavigation.up: reset
                KeyNavigation.right: save.enabled ? save : null
            }
            NovaButton {
                id: save
                objectName: editor.objectName + "-save"
                unit: editor.unit
                Layout.fillWidth: true
                Layout.minimumWidth: 48
                implicitWidth: 48
                primary: true
                text: "Save"
                enabled: editor.validDraft
                onClicked: { editor.valueSaved(editor.draftValue); editor.close() }
                KeyNavigation.up: reset
                KeyNavigation.left: cancel
            }
        }
    }
}

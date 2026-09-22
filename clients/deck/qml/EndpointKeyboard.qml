import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

Popup {
    id: keyboard
    objectName: "endpoint-keyboard"
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    padding: 24
    width: Math.min(940, parent.width - 32)
    height: Math.min(contentItem.implicitHeight + topPadding + bottomPadding, parent.height - 32)
    x: (parent.width - width) / 2
    y: (parent.height - height) / 2
    property var targetField: null
    property bool digitsOnly: false
    property bool decimalEntry: false
    property string entryTitle: ""
    property bool alphabet: false
    property bool titleEntry: false
    readonly property var characters: alphabet
        ? (titleEntry ? "qwertyuiopasdfghjkl⌫zxcvbnm '&" : "qwertyuiopasdfghjkl⌫zxcvbnm-.:").split("")
        : ["1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", ":"]

    function edit(field, port, heading = "", decimal = false) {
        entryTitle = heading
        decimalEntry = decimal
        targetField = field
        digitsOnly = port
        alphabet = false
        entry.text = field.text
        open()
        Qt.callLater(function() { entry.forceActiveFocus(); entry.cursorPosition = entry.length })
    }
    function typeCharacter(value) {
        if (entry.selectedText.length) entry.remove(entry.selectionStart, entry.selectionEnd)
        entry.insert(entry.cursorPosition, value)
    }
    function erase() {
        if (entry.selectedText.length) entry.remove(entry.selectionStart, entry.selectionEnd)
        else if (entry.cursorPosition > 0) entry.remove(entry.cursorPosition - 1, entry.cursorPosition)
    }
    function accept() {
        if (targetField) { targetField.text = entry.text; targetField.cursorPosition = targetField.length }
        close()
    }
    function focusKey(index) {
        const key = keys.itemAt(index)
        if (key && key.enabled) key.forceActiveFocus()
    }
    onClosed: if (targetField) targetField.forceActiveFocus()
    background: Rectangle { color: NovaTheme.window; radius: 18; border.color: NovaTheme.divider; border.width: 2 }
    Overlay.modal: Rectangle { color: NovaTheme.alpha(NovaTheme.window, 0.733) }

    component PadButton: Button {
        id: key
        Layout.fillWidth: true
        Layout.preferredHeight: 56
        focusPolicy: Qt.NoFocus
        font.pixelSize: 24 * NovaTheme.fontScale
        font.bold: true
        Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat && enabled) clicked() }
        Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat && enabled) clicked() }
        contentItem: Text {
            text: key.text; font: key.font; color: key.activeFocus ? NovaTheme.window : "white"
            horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
        }
        background: Rectangle {
            radius: 10; color: key.activeFocus ? NovaTheme.focus : NovaTheme.raised
            border.color: key.activeFocus ? NovaTheme.focus : NovaTheme.divider
            border.width: key.activeFocus ? 4 : 1
            opacity: key.enabled ? 1 : 0.35
        }
    }
    contentItem: NovaScrollColumn {
        spacing: 12
        Label {
            text: keyboard.entryTitle || (keyboard.titleEntry ? "Search title" : keyboard.digitsOnly ? "Host port" : "PC address")
            color: NovaTheme.text; font.pixelSize: 28 * NovaTheme.fontScale; font.bold: true
        }
        TextField {
            id: entry
            objectName: "endpoint-keyboard-entry"
            Layout.fillWidth: true
            Layout.preferredHeight: 58
            color: NovaTheme.text; font.pixelSize: 24 * NovaTheme.fontScale
            selectByMouse: true
            maximumLength: keyboard.decimalEntry ? 8 : keyboard.digitsOnly ? 5 : 253
            inputMethodHints: keyboard.decimalEntry ? Qt.ImhFormattedNumbersOnly : keyboard.digitsOnly ? Qt.ImhDigitsOnly : Qt.ImhNoAutoUppercase | Qt.ImhNoPredictiveText
            validator: RegularExpressionValidator { regularExpression: keyboard.decimalEntry ? /[0-9.]{0,8}/ : keyboard.digitsOnly ? /[0-9]{0,5}/ : keyboard.titleEntry ? /[A-Za-z0-9 .:\-&']{0,253}/ : /[A-Za-z0-9.:-]{0,253}/ }
            background: Rectangle {
                color: NovaTheme.panel; radius: 10
                border.color: entry.activeFocus ? NovaTheme.focus : NovaTheme.divider; border.width: entry.activeFocus ? 4 : 1
            }
            Keys.onDownPressed: keyboard.focusKey(0)
            Keys.onReturnPressed: keyboard.accept()
            Keys.onEnterPressed: keyboard.accept()
        }
        GridLayout {
            id: grid
            Layout.fillWidth: true
            columns: keyboard.alphabet ? 10 : 3
            columnSpacing: 8; rowSpacing: 8
            Repeater {
                id: keys
                model: keyboard.characters
                PadButton {
                    required property string modelData
                    required property int index
                    objectName: "endpoint-key-" + modelData
                    text: modelData
                    enabled: !keyboard.digitsOnly || /[0-9]/.test(modelData) || (keyboard.decimalEntry && modelData === ".")
                    Accessible.name: modelData === "⌫" ? "Backspace" : modelData
                    onClicked: modelData === "⌫" ? keyboard.erase() : keyboard.typeCharacter(modelData)
                    Keys.onLeftPressed: keyboard.focusKey(index - 1)
                    Keys.onRightPressed: keyboard.focusKey(index + 1)
                    Keys.onUpPressed: { if (index < grid.columns) entry.forceActiveFocus(); else keyboard.focusKey(index - grid.columns) }
                    Keys.onDownPressed: {
                        if (index + grid.columns < keys.count && keys.itemAt(index + grid.columns).enabled) keyboard.focusKey(index + grid.columns)
                        else if (keyboard.digitsOnly) backspace.forceActiveFocus()
                        else mode.forceActiveFocus()
                    }
                }
            }
        }
        RowLayout {
            spacing: 8
            PadButton {
                id: mode
                objectName: "endpoint-keyboard-mode"
                text: keyboard.alphabet ? "#123" : "ABC"
                visible: !keyboard.digitsOnly
                onClicked: keyboard.alphabet = !keyboard.alphabet
                Keys.onRightPressed: backspace.forceActiveFocus()
                Keys.onUpPressed: keyboard.focusKey(0)
            }
            PadButton {
                id: backspace
                objectName: "endpoint-keyboard-backspace"
                text: "⌫"
                Accessible.name: "Backspace"
                onClicked: keyboard.erase()
                Keys.onLeftPressed: { if (mode.visible) mode.forceActiveFocus() }
                Keys.onRightPressed: clear.forceActiveFocus()
                Keys.onUpPressed: keyboard.focusKey(0)
            }
            PadButton {
                id: clear
                objectName: "endpoint-keyboard-clear"
                text: "Clear"
                onClicked: entry.clear()
                Keys.onLeftPressed: backspace.forceActiveFocus()
                Keys.onRightPressed: cancel.forceActiveFocus()
                Keys.onUpPressed: keyboard.focusKey(0)
            }
            PadButton {
                id: cancel
                objectName: "endpoint-keyboard-cancel"
                text: "Cancel"
                onClicked: keyboard.close()
                Keys.onLeftPressed: clear.forceActiveFocus()
                Keys.onRightPressed: done.forceActiveFocus()
                Keys.onUpPressed: keyboard.focusKey(0)
            }
            PadButton {
                id: done
                objectName: "endpoint-keyboard-done"
                text: "Done"
                onClicked: keyboard.accept()
                Keys.onLeftPressed: cancel.forceActiveFocus()
                Keys.onUpPressed: keyboard.focusKey(0)
            }
        }
    }
}

import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: editor
    objectName: "stream-profile-editor"
    property var settingsProvider: null
    property var hostController: null
    property string hostId: ""
    property string gameId: ""
    property string field: "resolution"
    property bool defaultsScope: false
    property bool editable: true
    onEditableChanged: if (!editable && opened) close()
    property var initial: ({})
    property var returnFocus: null
    property string error: ""
    property real unit: 1
    readonly property bool sizeVisible: defaultsScope || field === "resolution"
    readonly property bool rateVisible: defaultsScope || field === "fps"
    readonly property bool bitrateVisible: defaultsScope || field === "bitrateKbps"
    readonly property var fields: [widthField, heightField, rateField, bitrateField].filter(item => item.visible)
    signal saved()
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(720 * unit, parent ? parent.width - 32 : 720)
    height: Math.min(implicitHeight, parent ? parent.height - 32 : 760)
    padding: 24 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    onHostIdChanged: if (opened) close()
    onGameIdChanged: if (opened) close()
    onAboutToShow: {
        error = ""
        initial = defaultsScope ? settingsProvider.streamDefaults : settingsProvider.load(hostId, gameId).configuration
        widthField.text = String(initial.width); heightField.text = String(initial.height)
        rateField.text = String(initial.fps); bitrateField.text = String(initial.bitrateKbps / 1000)
    }
    onOpened: fields[0].forceActiveFocus()
    onClosed: { keypad.close(); if (returnFocus) returnFocus.forceActiveFocus() }
    function back() { if (keypad.opened) keypad.close(); else close() }
    function focusField(item, direction) {
        const next = fields.indexOf(item) + direction
        if (next < 0) back.forceActiveFocus()
        else if (next >= fields.length) saveButton.forceActiveFocus()
        else fields[next].forceActiveFocus()
    }
    function apply() {
        if (!editable) return
        const values = {}
        if (sizeVisible) {
            if (!/^[0-9]{3,4}$/.test(widthField.text) || !/^[0-9]{3,4}$/.test(heightField.text)
                    || Number(widthField.text) < 320 || Number(widthField.text) > 4096
                    || Number(heightField.text) < 240 || Number(heightField.text) > 4096
                    || Number(widthField.text) % 2 || Number(heightField.text) % 2) {
                error = "Use an even width from 320–4096 and an even height from 240–4096."; return
            }
            values.width = Number(widthField.text); values.height = Number(heightField.text)
        }
        if (rateVisible) {
            if (!/^[0-9]{2}$/.test(rateField.text) || Number(rateField.text) < 30 || Number(rateField.text) > 90) {
                error = "Choose a whole-number frame rate from 30–90 fps."; return
            }
            values.fps = Number(rateField.text)
        }
        if (bitrateVisible) {
            if (!/^[0-9]{1,3}(\.[0-9]{1,3})?$/.test(bitrateField.text)
                    || Number(bitrateField.text) < 1 || Number(bitrateField.text) > 300) {
                error = "Choose a bitrate from 1–300 Mbps, with up to three decimal places."; return
            }
            values.bitrateKbps = Math.round(Number(bitrateField.text) * 1000)
        }
        const ok = defaultsScope ? hostController && hostController.saveDefaults(values, initial)
                                 : settingsProvider.saveChoice(hostId, gameId, values)
        if (!ok) { error = "Couldn't save these choices. Check the current settings and try again."; return }
        saved(); close()
    }
    background: Rectangle { color: NovaTheme.panel; radius: 12 * editor.unit; border.color: NovaTheme.divider }
    EndpointKeyboard { id: keypad; parent: Overlay.overlay }
    component Copy: Label {
        Layout.fillWidth: true
        textFormat: Text.PlainText
        wrapMode: Text.WordWrap
        color: NovaTheme.secondary
        font.pixelSize: 17 * editor.unit * NovaTheme.fontScale
    }
    component NumberField: TextField {
        id: number
        property bool decimal: false
        property string title: ""
        Layout.fillWidth: true
        Layout.preferredHeight: 56 * editor.unit
        font.pixelSize: 24 * editor.unit * NovaTheme.fontScale
        color: NovaTheme.text
        selectByMouse: true
        maximumLength: 8
        inputMethodHints: decimal ? Qt.ImhFormattedNumbersOnly : Qt.ImhDigitsOnly
        Accessible.name: title
        background: Rectangle { color: NovaTheme.input; radius: 8; border.color: number.activeFocus ? NovaTheme.focus : NovaTheme.divider; border.width: number.activeFocus ? 3 : 1 }
        function typeNumber() { keypad.edit(number, true, title, decimal) }
        TapHandler { onTapped: number.typeNumber() }
        Keys.onReturnPressed: typeNumber()
        Keys.onEnterPressed: typeNumber()
        Keys.onUpPressed: editor.focusField(number, -1)
        Keys.onDownPressed: editor.focusField(number, 1)
    }
    contentItem: ColumnLayout {
        spacing: 12 * editor.unit
        NovaScrollColumn {
            reserveScrollBarSpace: true
            Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 0
            spacing: 12 * editor.unit
            Copy { text: editor.defaultsScope ? "Nova stream defaults" : "Custom " + (editor.field === "resolution" ? "resolution" : editor.field === "fps" ? "frame rate" : "bitrate"); color: NovaTheme.text; font.bold: true; font.pixelSize: 28 * editor.unit * NovaTheme.fontScale }
            Copy { text: editor.defaultsScope ? "For games without their own choice, on this device across PCs. Keep in step may copy these defaults to the paired PC while Every Game is open." : "For this game on this PC. Other games and Nova's device defaults stay unchanged." }
            Copy { text: "Width · pixels"; visible: editor.sizeVisible }
            NumberField { id: widthField; objectName: "stream-profile-width"; title: "Width in pixels"; visible: editor.sizeVisible }
            Copy { text: "Height · pixels"; visible: editor.sizeVisible }
            NumberField { id: heightField; objectName: "stream-profile-height"; title: "Height in pixels"; visible: editor.sizeVisible }
            Copy { text: "Frame rate · fps"; visible: editor.rateVisible }
            NumberField { id: rateField; objectName: "stream-profile-fps"; title: "Frame rate in fps"; visible: editor.rateVisible }
            Copy { text: "Bitrate · Mbps"; visible: editor.bitrateVisible }
            NumberField { id: bitrateField; objectName: "stream-profile-bitrate"; title: "Bitrate in Mbps"; visible: editor.bitrateVisible; decimal: true }
            Copy { text: "The stream still needs a supported decoder, PC and display. Play Setup shows adjustments before Play. Fractional frame rates and rates above 90 fps are not available yet." }
        }
        Copy { objectName: "stream-profile-error"; text: editor.error; color: NovaTheme.warning; visible: text.length > 0 }
        RowLayout {
            Layout.fillWidth: true; spacing: 12 * editor.unit
            NovaButton {
                id: saveButton; objectName: "stream-profile-save"
                Layout.fillWidth: true; unit: editor.unit
                text: "Save"; onClicked: editor.apply()
                Keys.onUpPressed: editor.fields[editor.fields.length - 1].forceActiveFocus()
                Keys.onDownPressed: back.forceActiveFocus()
                Keys.onRightPressed: back.forceActiveFocus()
            }
            NovaButton {
                id: back; objectName: "stream-profile-back"
                Layout.fillWidth: true; unit: editor.unit
                text: "Cancel"; onClicked: editor.close()
                Keys.onUpPressed: editor.fields[editor.fields.length - 1].forceActiveFocus()
                Keys.onLeftPressed: saveButton.forceActiveFocus()
            }
        }
    }
}

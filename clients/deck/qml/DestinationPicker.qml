import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: picker
    objectName: "destination-picker"
    required property var controller
    property real unit: 1
    property string focusId: ""
    property string pendingId: ""
    readonly property var state: controller ? controller.state : ({})
    readonly property var spaces: state.spaces || ({ rows: [] })
    anchors.centerIn: Overlay.overlay
    width: Math.min(760 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 40)
    height: Math.min(680 * unit, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
    padding: 24 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    background: Rectangle { color: NovaTheme.window; radius: 12; border.color: NovaTheme.divider }
    function restoreFocus() {
        if (!opened) return
        if (focusId === "refresh") { refresh.forceActiveFocus(); return }
        if (focusId === "back") { back.forceActiveFocus(); return }
        for (let i = 0; i < choices.count; ++i) {
            const item = choices.itemAt(i)
            if (item && item.modelData.id === focusId) { item.forceActiveFocus(); return }
        }
        back.forceActiveFocus()
    }
    function choose(id) {
        if (state.busy || !spaces.known) return
        if (id === spaces.selectedId) { close(); return }
        if (controller.selectDestination(id)) pendingId = id
    }
    function observation() {
        return { opened: opened, known: spaces.known || false, selectedId: spaces.selectedId || "",
            caption: spaces.caption || "", pending: pendingId, rows: spaces.rows || [],
            choices: (spaces.rows || []).map((row, index) => {
                const item = choices.itemAt(index)
                if (!item) return null
                const point = item.mapToItem(null, item.width / 2, item.height / 2)
                return { id: row.id, x: point.x, y: point.y }
            }).filter(row => row !== null) }
    }
    onOpened: {
        pendingId = ""
        focusId = spaces.selectedId || "desktop"
        restoreFocus()
        controller.refresh()
    }
    Connections {
        target: picker.controller
        function onStateChanged() {
            if (!picker.opened) return
            if (!picker.state.busy && picker.pendingId.length) {
                picker.pendingId = ""
                if (picker.state.selectionConfirmed) { picker.close(); return }
            }
            Qt.callLater(picker.restoreFocus)
        }
    }
    contentItem: ColumnLayout {
        spacing: 14 * picker.unit
        Label {
            text: "Where to play"
            color: NovaTheme.text
            font.pixelSize: 28 * picker.unit * NovaTheme.fontScale
            font.weight: Font.DemiBold
        }
        Label {
            Layout.fillWidth: true
            text: picker.state.busy ? (picker.spaces.changing ? "Changing destination…" : "Checking destinations…") : picker.spaces.caption || ""
            color: NovaTheme.secondary
            font.pixelSize: 16 * picker.unit * NovaTheme.fontScale
            textFormat: Text.PlainText
            wrapMode: Text.WordWrap
        }
        Label {
            Layout.fillWidth: true
            visible: picker.state.failed || false
            text: picker.state.copy || ""
            color: NovaTheme.text
            font.pixelSize: 15 * picker.unit * NovaTheme.fontScale
            textFormat: Text.PlainText
            wrapMode: Text.WordWrap
        }
        NovaScrollColumn {
            id: scroll
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.minimumHeight: 0
            spacing: 10 * picker.unit
            Repeater {
                id: choices
                model: picker.spaces.rows || []
                NovaButton {
                    id: choice
                    required property var modelData
                    required property int index
                    unit: picker.unit
                    objectName: "destination-choice-" + modelData.id
                    Layout.fillWidth: true
                    implicitHeight: Math.max(78 * unit, labels.implicitHeight + 24 * unit)
                    property bool available: modelData.available && !picker.state.busy
                    // Unavailable rows stay readable by controller; activation
                    // always rechecks backend permission and pending work.
                    opacity: modelData.available || activeFocus ? 1 : 0.6
                    Accessible.name: modelData.name + (modelData.selected ? ". Current destination" : "")
                    Accessible.description: modelData.caption + (modelData.status ? ". " + modelData.status : "")
                    Accessible.checkable: true
                    Accessible.checked: modelData.selected
                    onActiveFocusChanged: if (activeFocus) picker.focusId = modelData.id
                    onClicked: if (available) picker.choose(modelData.id)
                    Keys.onUpPressed: index > 0 ? choices.itemAt(index - 1).forceActiveFocus() : refresh.forceActiveFocus()
                    Keys.onDownPressed: index < choices.count - 1 ? choices.itemAt(index + 1).forceActiveFocus() : refresh.forceActiveFocus()
                    contentItem: ColumnLayout {
                        id: labels
                        spacing: 4 * picker.unit
                        RowLayout {
                            Layout.fillWidth: true
                            Label {
                                Layout.fillWidth: true
                                text: choice.modelData.name + (choice.modelData.selected ? "  ✓" : "")
                                textFormat: Text.PlainText
                                color: choice.activeFocus ? NovaTheme.focusText : NovaTheme.text
                                font.pixelSize: 20 * picker.unit * NovaTheme.fontScale
                                font.weight: Font.DemiBold
                                elide: Text.ElideRight
                            }
                            Label {
                                text: choice.modelData.status
                                color: choice.activeFocus ? NovaTheme.focusText : NovaTheme.secondary
                                font.pixelSize: 14 * picker.unit * NovaTheme.fontScale
                            }
                        }
                        Label {
                            Layout.fillWidth: true
                            text: choice.modelData.caption
                            textFormat: Text.PlainText
                            color: choice.activeFocus ? NovaTheme.focusText : NovaTheme.secondary
                            font.pixelSize: 14 * picker.unit * NovaTheme.fontScale
                            wrapMode: Text.WordWrap
                        }
                    }
                }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 12 * picker.unit
            NovaButton {
                id: refresh
                objectName: "destination-refresh"
                unit: picker.unit
                text: "Refresh"
                onActiveFocusChanged: if (activeFocus) picker.focusId = "refresh"
                onClicked: if (!picker.state.busy) picker.controller.refresh()
                Keys.onUpPressed: if (choices.count) choices.itemAt(choices.count - 1).forceActiveFocus()
                Keys.onRightPressed: back.forceActiveFocus()
                Keys.onDownPressed: back.forceActiveFocus()
            }
            Item { Layout.fillWidth: true }
            NovaButton {
                id: back
                objectName: "destination-back"
                unit: picker.unit
                text: "Back"
                onActiveFocusChanged: if (activeFocus) picker.focusId = "back"
                onClicked: picker.close()
                Keys.onLeftPressed: refresh.forceActiveFocus()
                Keys.onUpPressed: if (choices.count) choices.itemAt(choices.count - 1).forceActiveFocus()
            }
        }
        Label {
            text: "A  Select     B  Back"
            color: NovaTheme.secondary
            font.pixelSize: 14 * picker.unit * NovaTheme.fontScale
        }
    }
}

import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: editor
    objectName: "logo-placement-editor"
    property var settingsProvider: null
    property string hostId: ""
    property string gameId: ""
    property url logoSource: ""
    property url heroSource: ""
    property var fallback: ({})
    property var returnFocus: null
    property var original: ({scale: 1, x: 0.5, y: 0.5})
    property var draft: ({scale: 1, x: 0.5, y: 0.5})
    property string error: ""
    readonly property real unit: Math.max(0.85, Math.min(1.0, width / 800))
    readonly property bool dirty: Math.abs(draft.scale-original.scale) > 0.00001
        || Math.abs(draft.x-original.x) > 0.00001 || Math.abs(draft.y-original.y) > 0.00001
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(800, parent ? parent.width - 32 : 800)
    height: Math.min(740, parent ? parent.height - 32 : 740)
    padding: 20 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    onHostIdChanged: if (opened) close()
    onGameIdChanged: if (opened) close()
    onFallbackChanged: if (opened) close()
    onLogoSourceChanged: if (opened) close()
    onAboutToShow: {
        original = settingsProvider.logoPlacement(hostId, gameId, fallback)
        draft = ({scale: original.scale, x: original.x, y: original.y})
        error = ""; body.contentY = 0
    }
    onOpened: smaller.forceActiveFocus()
    onClosed: if (returnFocus && returnFocus.visible && returnFocus.enabled) returnFocus.forceActiveFocus()
    function change(scale, x, y) {
        draft = ({scale: Math.max(0.25, Math.min(4, Math.round(scale * 100) / 100)),
            x: Math.max(0, Math.min(1, Math.round(x * 100) / 100)),
            y: Math.max(0, Math.min(1, Math.round(y * 100) / 100))})
        error = ""
    }
    background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
    component Copy: Label {
        Layout.fillWidth: true; wrapMode: Text.WordWrap; textFormat: Text.PlainText
        color: NovaTheme.text; font.pixelSize: 17 * editor.unit * NovaTheme.fontScale
    }
    component Action: NovaButton { unit: editor.unit; Layout.fillWidth: true }
    contentItem: ColumnLayout {
        spacing: 12 * editor.unit
        Copy { text: "Logo placement"; font.bold: true; font.pixelSize: 28 * editor.unit * NovaTheme.fontScale }
        NovaScrollColumn {
            id: body; Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 0
            spacing: 12 * editor.unit; reserveScrollBarSpace: true
            Copy { text: "For this game on this device. Preview your changes, then save."; color: NovaTheme.secondary }
            Item {
                Layout.fillWidth: true; Layout.preferredHeight: Math.min(250, width * 0.42)
                clip: true
                Rectangle { anchors.fill: parent; color: NovaTheme.window }
                Image { anchors.fill: parent; source: editor.heroSource; fillMode: Image.PreserveAspectCrop; asynchronous: true; opacity: 0.65 }
                LogoArtwork { id: preview; objectName: "logo-placement-preview"; anchors.fill: parent; source: editor.logoSource; placement: editor.draft; description: "Logo placement preview" }
                Copy { anchors.centerIn: parent; width: parent.width - 24; horizontalAlignment: Text.AlignHCenter
                    visible: preview.status !== Image.Ready; text: preview.status === Image.Loading ? "Loading logo…" : "No logo available. Choose a logo in Artwork Studio first." }
            }
            Copy { text: "Size " + Math.round(editor.draft.scale * 100) + "% · Horizontal " + Math.round(editor.draft.x * 100) + "% · Vertical " + Math.round(editor.draft.y * 100) + "%" }
            RowLayout {
                Layout.fillWidth: true
                Action { id: smaller; objectName: "logo-smaller"; text: "Smaller"; onClicked: editor.change(editor.draft.scale - 0.1, editor.draft.x, editor.draft.y)
                    Keys.onRightPressed: reset.forceActiveFocus(); Keys.onDownPressed: left.forceActiveFocus(); Keys.onUpPressed: body.contentY = 0 }
                Action { id: reset; objectName: "logo-reset"; text: "Reset layout"; onClicked: editor.change(1, 0.5, 0.5)
                    Keys.onLeftPressed: smaller.forceActiveFocus(); Keys.onRightPressed: larger.forceActiveFocus(); Keys.onDownPressed: up.forceActiveFocus(); Keys.onUpPressed: body.contentY = 0 }
                Action { id: larger; objectName: "logo-larger"; text: "Larger"; onClicked: editor.change(editor.draft.scale + 0.1, editor.draft.x, editor.draft.y)
                    Keys.onLeftPressed: reset.forceActiveFocus(); Keys.onDownPressed: right.forceActiveFocus(); Keys.onUpPressed: body.contentY = 0 }
            }
            RowLayout {
                Layout.fillWidth: true
                Action { id: left; objectName: "logo-left"; text: "Left"; onClicked: editor.change(editor.draft.scale, editor.draft.x - 0.05, editor.draft.y)
                    Keys.onRightPressed: up.forceActiveFocus(); Keys.onUpPressed: smaller.forceActiveFocus(); Keys.onDownPressed: save.forceActiveFocus() }
                Action { id: up; objectName: "logo-up"; text: "Up"; onClicked: editor.change(editor.draft.scale, editor.draft.x, editor.draft.y - 0.05)
                    Keys.onLeftPressed: left.forceActiveFocus(); Keys.onRightPressed: down.forceActiveFocus(); Keys.onUpPressed: reset.forceActiveFocus(); Keys.onDownPressed: save.forceActiveFocus() }
                Action { id: down; objectName: "logo-down"; text: "Down"; onClicked: editor.change(editor.draft.scale, editor.draft.x, editor.draft.y + 0.05)
                    Keys.onLeftPressed: up.forceActiveFocus(); Keys.onRightPressed: right.forceActiveFocus(); Keys.onUpPressed: reset.forceActiveFocus(); Keys.onDownPressed: cancel.forceActiveFocus() }
                Action { id: right; objectName: "logo-right"; text: "Right"; onClicked: editor.change(editor.draft.scale, editor.draft.x + 0.05, editor.draft.y)
                    Keys.onLeftPressed: down.forceActiveFocus(); Keys.onUpPressed: larger.forceActiveFocus(); Keys.onDownPressed: cancel.forceActiveFocus() }
            }
        }
        Copy { objectName: "logo-placement-error"; visible: text.length > 0; text: editor.error; color: NovaTheme.warning }
        RowLayout {
            Layout.fillWidth: true
            Action {
                id: save; objectName: "logo-save"; text: "Save"; primary: true
                onClicked: {
                    if (preview.status !== Image.Ready) { editor.error = "Wait for a logo before saving its placement."; return }
                    if (!editor.dirty) { editor.close(); return }
                    if (editor.settingsProvider.saveLogoPlacement(editor.hostId, editor.gameId, editor.draft, editor.original, editor.fallback)) editor.close()
                    else editor.error = "Couldn't save this layout, or it changed while you were editing. Close and reopen to try again."
                }
                Keys.onRightPressed: cancel.forceActiveFocus(); Keys.onUpPressed: left.forceActiveFocus()
            }
            Action { id: cancel; objectName: "logo-cancel"; text: "Cancel"; onClicked: editor.close()
                Keys.onLeftPressed: save.forceActiveFocus(); Keys.onUpPressed: right.forceActiveFocus() }
        }
    }
}

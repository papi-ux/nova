import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// The one question asked before a game is ended on the PC: in the Command Center and on every
// End Session control outside a stream. Keep playing has focus; only End game emits confirmed().
Popup {
    id: confirmation
    // Object names of the two actions are namePrefix + "-stay" and namePrefix + "-confirm".
    property string namePrefix: "end"
    property real unit: 1
    // What the question is centred in and sized against: the stream content in the Command
    // Center, the window's overlay everywhere else.
    property Item frame: parent
    signal confirmed()
    anchors.centerIn: frame
    width: Math.min(620, (frame ? frame.width : 1280) - 48)
    height: Math.min(implicitHeight, (frame ? frame.height : 800) - 48)
    padding: 24
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    Overlay.modal: Rectangle { color: NovaTheme.alpha(NovaTheme.window, 0.702) }
    onOpened: stayAction.forceActiveFocus()
    background: Rectangle { color: "#152238"; radius: 18; border.color: NovaTheme.divider; border.width: 1 }
    contentItem: NovaScrollColumn {
        spacing: 14
        Label {
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            text: "End this game?"
            color: NovaTheme.text; font.pixelSize: 26 * NovaTheme.fontScale; font.bold: true
        }
        Label {
            Layout.fillWidth: true
            text: "This closes the game on your PC. Unsaved progress may be lost."
            color: NovaTheme.secondary; font.pixelSize: 18 * NovaTheme.fontScale; wrapMode: Text.WordWrap
        }
        StreamAction {
            id: stayAction
            objectName: confirmation.namePrefix + "-stay"
            unit: confirmation.unit
            Layout.fillWidth: true
            Layout.preferredHeight: Math.max(56, implicitHeight)
            textScale: 1
            text: "Keep playing"
            onClicked: confirmation.close()
            Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) confirmation.close() }
            Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) confirmation.close() }
            Keys.onDownPressed: confirmEndAction.forceActiveFocus()
        }
        StreamAction {
            id: confirmEndAction
            objectName: confirmation.namePrefix + "-confirm"
            unit: confirmation.unit
            Layout.fillWidth: true
            Layout.preferredHeight: Math.max(56, implicitHeight)
            textScale: 1
            text: "End game"; destructive: true
            function endGame() {
                if (!confirmation.opened) return
                confirmation.close()
                confirmation.confirmed()
            }
            onClicked: endGame()
            Keys.onReturnPressed: (event) => { if (!event.isAutoRepeat) endGame() }
            Keys.onEnterPressed: (event) => { if (!event.isAutoRepeat) endGame() }
            Keys.onUpPressed: stayAction.forceActiveFocus()
        }
    }
}

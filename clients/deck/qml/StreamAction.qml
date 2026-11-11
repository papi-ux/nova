import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// The Command Center's action button. EndConfirmation uses it too, so the question about ending a
// game looks the same wherever it is asked.
Button {
    id: action
    property real unit: 1
    property string caption: ""
    property bool destructive: false
    property real textScale: unit
    readonly property real titleWidth: actionTitle.implicitWidth + leftPadding + rightPadding
    Accessible.description: caption
    implicitHeight: Math.max(52, contentItem.implicitHeight + 20 * unit)
    Layout.minimumHeight: implicitHeight
    topPadding: 10 * unit; bottomPadding: 10 * unit
    leftPadding: 10 * unit; rightPadding: 10 * unit
    topInset: 0; bottomInset: 0
    contentItem: Item {
        implicitHeight: actionLabels.implicitHeight
        Column {
            id: actionLabels
            anchors.centerIn: parent
            width: parent.width
            spacing: 4
            Text {
                id: actionTitle
                width: parent.width
                text: action.text; textFormat: Text.PlainText
                wrapMode: Text.Wrap
                color: action.activeFocus ? NovaTheme.window : "white"
                font.pixelSize: 20 * action.textScale * NovaTheme.fontScale; font.bold: true
                horizontalAlignment: Text.AlignHCenter
            }
            Text {
                width: parent.width
                visible: action.caption.length > 0
                text: action.caption; textFormat: Text.PlainText
                wrapMode: Text.WordWrap
                color: action.activeFocus ? NovaTheme.raised : NovaTheme.secondary
                font.pixelSize: 13 * action.textScale * NovaTheme.fontScale
                horizontalAlignment: Text.AlignHCenter
            }
        }
    }
    background: Rectangle {
        radius: 12
        color: action.activeFocus ? NovaTheme.focus : action.destructive ? "#422B3A" : NovaTheme.raised
        border.color: action.activeFocus ? NovaTheme.focus : action.destructive ? NovaTheme.danger : NovaTheme.divider
        border.width: action.activeFocus ? 5 : 1
    }
}

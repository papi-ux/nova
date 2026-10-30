import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

FocusScope {
    id: pill
    property var sessionState: ({})
    property var game: ({})
    property string namePrefix: "host"
    property bool compact: false
    property real unit: 1
    property bool actionsEnabled: true
    implicitHeight: content.implicitHeight + 16 * unit
    objectName: namePrefix + "-now-playing"
    signal resumeRequested()
    signal endRequested()
    signal leaveUp()
    signal leaveDown()
    function focusResume() { if (resume.enabled) resume.forceActiveFocus(); else leaveUp() }
    Rectangle { anchors.fill: parent; color: NovaTheme.panel; radius: 12 * pill.unit }
    GridLayout {
        id: content
        anchors.fill: parent; anchors.margins: 8 * pill.unit
        columns: pill.compact ? 1 : 2
        columnSpacing: 12 * pill.unit; rowSpacing: 6 * pill.unit
        RowLayout {
            Layout.fillWidth: true; spacing: 12 * pill.unit
            Image {
                source: pill.game.poster || ""
                Layout.preferredWidth: 40 * pill.unit; Layout.preferredHeight: 56 * pill.unit
                fillMode: Image.PreserveAspectFit; asynchronous: true
            }
            Label {
                Layout.fillWidth: true
                text: (pill.compact ? "" : "Resume your stream · ") + (pill.sessionState.title || pill.game.title || "Game")
                textFormat: Text.PlainText; wrapMode: Text.Wrap
                color: NovaTheme.text; font.pixelSize: 18 * pill.unit * NovaTheme.fontScale
            }
        }
        RowLayout {
            Layout.fillWidth: pill.compact
            NovaButton {
                id: resume; objectName: pill.namePrefix + "-resume"
                Layout.fillWidth: pill.compact; unit: pill.unit; text: "Resume"
                enabled: pill.actionsEnabled && pill.sessionState.canResume !== false && !pill.sessionState.busy
                onClicked: pill.resumeRequested()
                Keys.onRightPressed: end.enabled ? end.forceActiveFocus() : pill.leaveUp()
                Keys.onLeftPressed: pill.leaveUp()
                Keys.onUpPressed: pill.leaveUp()
                Keys.onDownPressed: pill.leaveDown()
            }
            NovaButton {
                id: end; objectName: pill.namePrefix + "-end-session"
                Layout.fillWidth: pill.compact; unit: pill.unit; text: "End Session"
                enabled: pill.actionsEnabled && pill.sessionState.canEnd === true && !pill.sessionState.busy
                onClicked: pill.endRequested()
                Keys.onLeftPressed: resume.forceActiveFocus()
                Keys.onRightPressed: pill.leaveUp()
                Keys.onUpPressed: pill.leaveUp()
                Keys.onDownPressed: pill.leaveDown()
            }
        }
    }
}

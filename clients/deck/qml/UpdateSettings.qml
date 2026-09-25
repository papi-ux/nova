import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: updates
    objectName: "update-settings"
    property var controller: null
    readonly property var current: controller ? controller.state : ({})
    property real unit: 1
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(700 * unit, parent ? parent.width - 32 : 700)
    height: Math.min(implicitHeight, parent ? parent.height - 32 : 760)
    padding: 24 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    enter: Transition { }
    exit: Transition { }
    onOpened: back.forceActiveFocus()
    background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
    component Copy: Label {
        textFormat: Text.PlainText
        color: NovaTheme.text
        font.pixelSize: 18 * updates.unit * NovaTheme.fontScale
        wrapMode: Text.WordWrap
        Layout.fillWidth: true
    }
    component Action: NovaButton { unit: updates.unit; Layout.fillWidth: true }
    contentItem: NovaScrollColumn {
        spacing: 12 * updates.unit
        Copy { text: "Nova Updates"; font.bold: true; font.pixelSize: 28 * updates.unit * NovaTheme.fontScale }
        Copy {
            text: "Running: " + (updates.current.version || "Development build")
                + (updates.current.supported ? " · " + updates.current.channel + " channel" : "")
            color: NovaTheme.secondary
        }
        Copy { objectName: "update-status"; text: updates.current.message || "Updates aren't available for this installation." }
        Copy {
            visible: !!updates.current.available && !!updates.current.latestVersion && !updates.current.restartRequired
            text: "Available: " + (updates.current.latestVersion || "")
            color: NovaTheme.secondary
        }
        ProgressBar {
            objectName: "update-progress"
            Layout.fillWidth: true
            visible: !!updates.current.installing
            from: 0; to: 100; value: updates.current.progress || 0
            indeterminate: value === 0
            Accessible.name: "Nova update progress"
        }
        Copy {
            visible: !!updates.current.blocked && !updates.current.installing
            text: "Updates wait until your session and other operations finish."
            color: NovaTheme.secondary
        }
        Action {
            id: check; objectName: "update-check"
            visible: !!updates.current.supported
            enabled: !!updates.current.canCheck
            text: updates.current.checking ? "Checking…" : "Check for Updates"
            onClicked: { updates.controller.check(); automatic.forceActiveFocus() }
            KeyNavigation.down: install.enabled && install.visible ? install : automatic
            KeyNavigation.up: back
        }
        Action {
            id: install; objectName: "update-install"
            visible: !!updates.current.available && !updates.current.restartRequired
            enabled: !!updates.current.canInstall
            text: updates.current.installing ? "Updating…" : "Update Nova"
            onClicked: { updates.controller.install(); automatic.forceActiveFocus() }
            KeyNavigation.up: check.enabled ? check : back
            KeyNavigation.down: automatic
        }
        Action {
            id: automatic; objectName: "update-automatic"
            visible: !!updates.current.supported
            text: "Automatic Updates: " + (updates.current.automatic ? "On" : "Off")
            Accessible.checkable: true
            Accessible.checked: !!updates.current.automatic
            onClicked: updates.controller.setAutomatic(!updates.current.automatic)
            KeyNavigation.up: install.enabled && install.visible ? install : check.enabled ? check : back
            KeyNavigation.down: finish.visible && finish.enabled ? finish : back
        }
        Copy {
            visible: !!updates.current.supported
            text: "When enabled, Nova installs updates after 15 seconds idle. A new stream waits until installation finishes. Nova never closes itself automatically."
            color: NovaTheme.secondary
        }
        Action {
            id: finish; objectName: "update-finish"
            visible: !!updates.current.restartRequired
            enabled: !!updates.current.canFinish
            text: "Close Nova to Finish"
            onClicked: updates.controller.finishUpdate()
            KeyNavigation.up: automatic
            KeyNavigation.down: back
        }
        Action {
            id: downloads; objectName: "update-downloads"
            visible: !updates.current.supported
            text: "Open Nova Downloads"
            onClicked: Qt.openUrlExternally("https://github.com/papi-ux/nova/releases")
            KeyNavigation.up: back
            KeyNavigation.down: back
        }
        Action {
            id: back; objectName: "update-back"
            text: "Back to Settings"
            onClicked: updates.close()
            KeyNavigation.up: finish.visible && finish.enabled ? finish : automatic.visible ? automatic : downloads
            KeyNavigation.down: check.visible && check.enabled ? check : automatic.visible ? automatic : downloads
        }
    }
}

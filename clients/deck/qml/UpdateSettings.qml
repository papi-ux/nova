import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: updates
    objectName: "update-settings"
    property var controller: null
    readonly property var current: controller ? controller.state : ({})
    // Presentation follows backend facts, never translated message text or the
    // absence of an available revision. A check in flight has no result yet.
    readonly property string stateId: !current.supported ? "manual"
        : current.installing ? "updating" : current.restartRequired ? "ready"
        : current.checking ? "checking" : current.available ? "available"
        : current.lastCheck === "failed" ? "failed"
        : current.lastCheck === "succeeded" ? "current" : "unchecked"
    readonly property var presentation: ({
        manual: ["Manual updates", "Install newer releases manually"],
        updating: ["Updating", "Updating Nova"],
        ready: ["Ready to finish", "Reopen Nova to finish the update"],
        checking: ["Checking", "Checking for updates"],
        available: ["Update available", "A Nova update is available"],
        failed: ["Couldn't check", "Couldn't check for updates"],
        current: ["Up to date", "Up to date on the " + (channelName || current.channel || "installed") + " channel"],
        unchecked: ["Not checked yet", "Updates haven't been checked yet"]
    })[stateId]
    readonly property string channelName: current.channel === "beta" ? "Beta" : current.channel === "stable" ? "Stable" : ""
    // Linux channels are branches of the same app, so pairing and settings carry
    // over. This view cannot know whether the other channel's installer is
    // published, so it never claims one is, and it switches nothing.
    readonly property string channelNote: !current.supported
        ? "Stable and Beta channels use the same Nova app, pairing and settings. Once the channel installer you want is published on Nova Downloads, installing it turns on updates here. Until then, install newer releases for your device from there; Steam Frame uses its own ARM64 download."
        : channelName === "Beta"
            ? "You're on Beta. Stable uses the same Nova app, pairing and settings. Once a Stable channel installer is published on Nova Downloads, installing it lets you return to Stable; check its release notes first. Nova never switches channels by itself."
            : "You're on Stable. Beta uses the same Nova app, pairing and settings. Once a Beta channel installer is published on Nova Downloads, installing it lets you choose Beta. Nova never switches channels by itself."
    // New status copy settles in briefly. Errors, an update that ends without
    // finishing, Reduce Motion and a closed popup show at once. Buttons, focus
    // and progress never wait for it.
    property real statusOpacity: 1
    property string shownState: ""
    Component.onCompleted: shownState = stateId
    onStateIdChanged: {
        const interrupted = shownState === "updating" && stateId !== "ready"
        shownState = stateId
        statusFade.stop()
        if (opened && NovaTheme.motionDuration > 0 && stateId !== "failed" && !interrupted)
            statusFade.start()
        else
            statusOpacity = 1
    }
    NumberAnimation {
        id: statusFade
        target: updates; property: "statusOpacity"
        from: 0.4; to: 1
        duration: NovaTheme.motionDuration; easing.type: NovaTheme.motionEasing
    }
    // A zero duration only affects the next fade, so settle one already running.
    Connections {
        target: NovaTheme
        function onReduceMotionChanged() {
            if (!NovaTheme.reduceMotion) return
            statusFade.stop()
            updates.statusOpacity = 1
        }
    }
    function primary() { return current.canInstall ? install : current.canCheck ? check : back }
    function keepFocus() {
        if (!opened) return
        const controls = [check, install, automatic, finish, downloads, back]
        if (!controls.some(control => control.activeFocus && control.visible && control.enabled))
            back.forceActiveFocus()
    }
    onCurrentChanged: Qt.callLater(keepFocus)
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
    onOpened: {
        statusFade.stop()
        statusOpacity = 1
        primary().forceActiveFocus()
    }
    background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
    component Copy: Label {
        textFormat: Text.PlainText
        color: NovaTheme.text
        font.pixelSize: 18 * updates.unit * NovaTheme.fontScale
        wrapMode: Text.WordWrap
        Layout.fillWidth: true
    }
    // A state change while the sheet is closed is laid out only after it opens and
    // focus is placed, so the focused action can still move. Keep it in view as
    // soon as layout moves it, in the same frame.
    component Action: NovaButton {
        unit: updates.unit; Layout.fillWidth: true
        onYChanged: if (activeFocus) updates.contentItem.revealFocus()
    }
    contentItem: NovaScrollColumn {
        spacing: 12 * updates.unit
        Copy { text: "Nova Updates"; font.bold: true; font.pixelSize: 28 * updates.unit * NovaTheme.fontScale }
        Copy {
            text: "Running: " + (updates.current.version || "Development build")
                + (updates.current.supported ? " · " + (updates.channelName || updates.current.channel) + " channel" : "")
            color: NovaTheme.secondary
        }
        Copy {
            objectName: "update-state"
            readonly property string stateId: updates.stateId
            text: updates.presentation[0]
            color: updates.stateId === "failed" ? NovaTheme.warning : NovaTheme.secondary
            opacity: updates.statusOpacity
            font.bold: true
            font.pixelSize: 14 * updates.unit * NovaTheme.fontScale
        }
        Copy {
            objectName: "update-headline"
            text: updates.presentation[1]
            opacity: updates.statusOpacity
            font.bold: true
            font.pixelSize: 24 * updates.unit * NovaTheme.fontScale
        }
        Copy {
            objectName: "update-status"
            text: updates.current.message || "Updates aren't available for this installation."
            opacity: updates.statusOpacity
        }
        Copy {
            visible: !!updates.current.available && !!updates.current.latestVersion && !updates.current.restartRequired
            objectName: "update-latest"
            text: "Latest on this channel: " + (updates.current.latestVersion || "")
            color: NovaTheme.secondary
            opacity: updates.statusOpacity
        }
        ProgressBar {
            objectName: "update-progress"
            Layout.fillWidth: true
            visible: !!updates.current.installing
            from: 0; to: 100; value: updates.current.progress || 0
            // Flatpak reports the current operation, not an overall percentage.
            // New operations may restart at a lower value; do not animate it.
            indeterminate: value <= 0
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
            onClicked: { updates.controller.check(); back.forceActiveFocus() }
            KeyNavigation.down: install.enabled && install.visible ? install : automatic
            KeyNavigation.up: back
        }
        Action {
            id: install; objectName: "update-install"
            visible: !!updates.current.available && !updates.current.restartRequired
            enabled: !!updates.current.canInstall
            text: updates.current.installing ? "Updating…" : "Update Nova"
            onClicked: { updates.controller.install(); back.forceActiveFocus() }
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
        // Channel guidance follows the actions it explains, so Finish and Downloads
        // stay near the status. Back remains last and keeps this text in view.
        Copy {
            objectName: "update-channel"
            text: updates.channelNote
            color: NovaTheme.secondary
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

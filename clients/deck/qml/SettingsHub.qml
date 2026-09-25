import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// The same stores used by Play Setup and Command Center own these values.
// Browsing or searching this screen never opens the paired-host controller.
Popup {
    id: hub
    objectName: "settings-hub"
    property var windowController: null
    property var desktopInput: null
    property var updateController: null
    required property var settingsProvider
    property var hostController: null
    property var libraryPreferences: null
    property bool hostAvailable: false
    property string category: "all"
    property string selectedKey: "stream"
    property string error: ""
    readonly property real unit: Math.max(0.85, Math.min(1.15, width / 1280))
    readonly property var categories: [
        {id: "all", title: "All Settings"}, {id: "stream", title: "Video & Stream"},
        {id: "audio", title: "Audio"}, {id: "controls", title: "Controls"},
        {id: "appearance", title: "Appearance"}, {id: "ingame", title: "In-Game UI"},
        {id: "pc", title: "Polaris Sync"}, {id: "app", title: "Nova"}
    ]
    readonly property var definitions: [
        {key: "updates", category: "app", title: "Nova Updates", words: "update upgrade download install automatic version beta channel", scope: "This device · Applies after reopening Nova", detail: "Check for a new version and choose whether Nova updates automatically while idle."},
        {key: "stream", category: "stream", title: "Stream Defaults", words: "resolution width height fps frame rate bitrate mbps every game video", scope: "This device · Games without an override · Next new stream", detail: "Review resolution, frame rate and bitrate in Every Game. Per-game choices stay in Play Setup."},
        {key: "window", category: "stream", title: "Window Mode", words: "fullscreen full screen window desktop display monitor", scope: "This device · Applies immediately", detail: "Switch between a desktop window and fullscreen. Ctrl + Alt + Shift + F also switches modes. During play, resume from Command Center after switching."},
        {key: "scale", category: "stream", title: "Video Scaling", words: "fit fill stretch crop bars aspect ratio display screen", scope: "This device · All streams · Applies immediately", detail: "Choose how the picture fits your screen.", reset: "fit"},
        {key: "pacing", category: "stream", title: "Video Frame Pacing", words: "latency balanced smooth timing stutter frames", scope: "This device · Next new stream · Reconnect keeps the current choice", detail: "Prefer lowest latency shows the newest frame as soon as possible. Balanced spaces decoded frames at the stream rate with a small buffer, which adds delay. Display timing still depends on your system. Warp, FPS-limit and smoothest-video modes are not available in the Linux client yet.", reset: "latency"},
        {key: "channels", category: "audio", title: "Audio Channels", words: "stereo surround speakers headphones 5.1 7.1", scope: "This device · Next new stream", detail: "Surround needs a compatible output. Your system controls audio routing.", reset: 2},
        {key: "hostAudio", category: "audio", title: "Play Audio on PC", words: "host sound speakers", scope: "This device · Next new stream", detail: "Also play through the PC's audio output while streaming.", reset: false},
        {key: "face", category: "controls", title: "Face Buttons", words: "controller layout labels positions swap ab xy", scope: "This device · Games without an override · Next new stream", detail: "Match positions swaps A/B and X/Y. A game's own layout takes priority.", reset: "labels"},
        {key: "rumble", category: "controls", title: "Controller Rumble", words: "vibration haptics", scope: "This device · Next new stream", detail: "Vibration on the active controller when supported. Pauses while controls are open.", reset: true},
        {key: "deadzone", category: "controls", title: "Stick Deadzone", words: "controller analog sensitivity drift radial anti dead zone", scope: "This device · All controllers · Next new stream", detail: "Adjust small stick movements near the center. Reconnect and wake keep the current choice.", reset: 5},
        {key: "mouse", category: "controls", title: "Mouse Mode", words: "relative aiming capture direct pointer mouse keyboard", scope: "This device · Resume to capture the mouse", detail: "Relative Aiming keeps moving at screen edges. Direct Pointer follows the video for desktop apps. Ctrl + Alt + Shift + M releases capture and opens Command Center.", reset: "direct"},
        {key: "theme", category: "appearance", title: "Theme", words: "colors oled high contrast polaris portable chrome miami", scope: "This device · Applies immediately", detail: "Use the same Nova theme across your library and in-game menus.", reset: "polaris"},
        {key: "text", category: "appearance", title: "Text Size", words: "font scale accessibility large", scope: "This device · Applies immediately", detail: "Increase text size while keeping controls reachable.", reset: 1},
        {key: "layout", category: "appearance", title: "Library Layout", words: "grid compact stage posters", scope: "This device · Applies immediately", detail: "Choose a poster grid, compact grid or cinematic Stage view.", reset: "grid"},
        {key: "command", category: "ingame", title: "Command Center Button", words: "hide touch overlay shortcut menu view", scope: "This device · Applies immediately", detail: "The controller shortcut still opens Command Center. Without a controller, the touch button stays available.", reset: true},
        {key: "hint", category: "ingame", title: "Controller Shortcut Hint", words: "hide press view menu steam deck symbols", scope: "This device · Applies immediately", detail: "Show the controller shortcut reminder during play.", reset: true},
        {key: "hud", category: "ingame", title: "NovaHUD", words: "overlay statistics performance fps", scope: "This device · Applies immediately", detail: "Show stream readings during play. Unavailable measurements remain blank.", reset: false},
        {key: "hudMode", category: "ingame", title: "HUD Layout", words: "slim minimal performance debug statistics", scope: "This device · Applies immediately", detail: "Choose how much stream detail to show.", reset: "minimal"},
        {key: "opacity", category: "ingame", title: "HUD Background Opacity", words: "transparent transparency panel", scope: "This device · Applies immediately", detail: "Adjust the background behind stream readings.", reset: 64},
        {key: "position", category: "ingame", title: "HUD Position", words: "top bottom left right corner", scope: "This device · Applies immediately", detail: "Choose a corner, or drag the HUD during play.", reset: "0,0"},
        {key: "sync", category: "pc", title: "Polaris Sync", words: "host paired profile keep in step match send import reset display mode resume timeout", scope: "Selected PC · Paired profile and PC-wide settings are labeled separately", detail: "Compare Nova and Polaris, manage Keep in step, and review host settings. Each action shows its scope."}
    ]
    readonly property var shown: definitions.filter(item => {
        if (item.key === "updates" && !updateController) return false
        if (item.key === "window" && !windowController) return false
        if (item.key === "mouse" && !desktopInput) return false
        const words = search.text.trim().toLowerCase().split(/\s+/).filter(Boolean)
        const matches = words.every(word => (item.title + " " + item.words + " " + item.scope).toLowerCase().includes(word))
        return words.length ? matches : category === "all" || item.category === category
    })
    parent: Overlay.overlay
    width: parent ? parent.width : 1280
    height: parent ? parent.height : 800
    padding: 24 * unit
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    enter: Transition { }
    exit: Transition { }
    onOpened: { error = ""; focusCategory() }
    onClosed: { choices.close(); keyboard.close(); hostSheet.close(); scaleSheet.close(); deadzoneSheet.close(); updateSheet.close() }
    background: Rectangle { color: NovaTheme.window }

    function value(key) {
        switch (key) {
        case "updates": return updateController && updateController.state.restartRequired ? "Ready to Finish" : updateController && updateController.state.available ? "Update Available" : "Review Updates"
        case "window": return windowController && windowController.fullscreen ? "fullscreen" : "windowed"
        case "scale": return settingsProvider.videoScaleMode
        case "pacing": return settingsProvider.framePacingMode
        case "stream": {
            const s = settingsProvider.streamDefaults
            return s.width + " × " + s.height + " · " + s.fps + " fps · " + s.bitrateKbps / 1000 + " Mbps"
        }
        case "channels": return settingsProvider.audioSettings.channels
        case "hostAudio": return settingsProvider.audioSettings.playHostAudio
        case "mouse": return settingsProvider.mouseMode
        case "face": return settingsProvider.defaultFaceButtonLayout
        case "rumble": return settingsProvider.rumbleEnabled
        case "deadzone": return settingsProvider.stickDeadzonePercent
        case "theme": return NovaTheme.themeId
        case "text": return NovaTheme.fontScale
        case "layout": return libraryPreferences ? libraryPreferences.layoutMode : "grid"
        case "command": return NovaStreamPreferences.commandCenterButton
        case "hint": return NovaStreamPreferences.shortcutHint
        case "hud": return NovaHudPreferences.enabled
        case "hudMode": return NovaHudPreferences.mode
        case "opacity": return NovaHudPreferences.panelOpacity
        case "position": return NovaHudPreferences.positionX + "," + NovaHudPreferences.positionY
        case "sync": return hostAvailable ? "Review profiles" : "Choose a paired PC"
        }
        return ""
    }
    function options(key) {
        switch (key) {
        case "window": return [{id: "windowed", title: "Windowed"}, {id: "fullscreen", title: "Fullscreen"}]
        case "scale": return scaleSheet.modes
        case "pacing": return [{id: "latency", title: "Prefer lowest latency"}, {id: "balanced", title: "Balanced"}]
        case "channels": return [{id: 2, title: "Stereo"}, {id: 6, title: "5.1 surround"}, {id: 8, title: "7.1 surround"}]
        case "face": return [{id: "labels", title: "Match labels"}, {id: "positions", title: "Match positions"}]
        case "mouse": return [{id: "direct", title: "Direct Pointer"}, {id: "relative", title: "Relative Aiming", available: !!desktopInput && desktopInput.mouseState.available}]
        case "theme": return NovaTheme.choices
        case "text": return [1, 1.15, 1.3].map(v => ({id: v, title: Math.round(v * 100) + "%"}))
        case "layout": return [{id: "grid", title: "Grid"}, {id: "compact", title: "Compact"}, {id: "stage", title: "Stage"}]
        case "hudMode": return NovaHudPreferences.modes
        case "opacity": return [0, 25, 64, 90, 100].map(v => ({id: v, title: v + "%"}))
        case "position": return [{id: "0,0", title: "Top left"}, {id: "1,0", title: "Top right"}, {id: "1,1", title: "Bottom right"}, {id: "0,1", title: "Bottom left"}]
        }
        return []
    }
    function label(key) {
        const v = value(key), choice = options(key).find(item => item.id === v)
        if (choice) return choice.title
        if (key === "deadzone") return deadzoneSheet.format(v)
        if (typeof v === "boolean") return v ? "On" : "Off"
        if (key === "position") return "Custom position"
        if (key === "opacity") return v + "%"
        return String(v)
    }
    function save(key, next) {
        let ok = true
        switch (key) {
        case "window": if (windowController) windowController.setFullscreen(next === "fullscreen"); else ok = false; break
        case "scale": ok = settingsProvider.setVideoScaleMode(next); break
        case "pacing": ok = next === "latency" ? settingsProvider.resetFramePacingMode() : settingsProvider.setFramePacingMode(next); break
        case "channels": ok = settingsProvider.saveAudioSettings({channels: next, playHostAudio: settingsProvider.audioSettings.playHostAudio}); break
        case "hostAudio": ok = settingsProvider.saveAudioSettings({channels: settingsProvider.audioSettings.channels, playHostAudio: next}); break
        case "face": ok = settingsProvider.setDefaultFaceButtonLayout(next); break
        case "rumble": ok = settingsProvider.setRumbleEnabled(next); break
        case "deadzone": ok = settingsProvider.resetStickDeadzonePercent(); break
        case "mouse": ok = settingsProvider.setMouseMode(next); break
        case "theme": NovaTheme.setTheme(next); break
        case "text": NovaTheme.setFontScale(next); break
        case "layout": if (libraryPreferences) libraryPreferences.layoutMode = next; else ok = false; break
        case "command": NovaStreamPreferences.setCommandCenterButton(next); break
        case "hint": NovaStreamPreferences.setShortcutHint(next); break
        case "hud": NovaHudPreferences.setEnabled(next); break
        case "hudMode": NovaHudPreferences.setMode(next); break
        case "opacity": NovaHudPreferences.setOpacity(next); break
        case "position": { const xy = next.split(","); NovaHudPreferences.setPosition(Number(xy[0]), Number(xy[1])); break }
        default: ok = false
        }
        error = ok ? "" : "Couldn't save this setting. Try again."
        return ok
    }
    function activate(definition) {
        selectedKey = definition.key; error = ""
        if (definition.key === "stream" || definition.key === "sync") {
            if (!hostAvailable) { error = "Choose a paired PC to review stream defaults and Polaris Sync."; return }
            hostSheet.syncView = definition.key === "sync"
            hostSheet.open()
        } else if (definition.key === "updates") updateSheet.open()
        else if (definition.key === "scale") scaleSheet.open()
        else if (definition.key === "deadzone") deadzoneSheet.open()
        else if (typeof value(definition.key) === "boolean") save(definition.key, !value(definition.key))
        else { choices.definition = definition; choices.open() }
    }
    function focusCategory() {
        const i = Math.max(0, categories.findIndex(item => item.id === category))
        categoryButtons.itemAt(i).forceActiveFocus()
    }
    function openUpdates() {
        selectedKey = "updates"
        selectCategory("app")
        open()
        Qt.callLater(() => { if (opened && updateController) updateSheet.open() })
    }
    function selectCategory(id) {
        category = id; search.clear(); rows.contentY = 0; error = ""
    }
    function focusRow(index) {
        if (!shown.length) { clearSearch.forceActiveFocus(); return }
        const i = Math.max(0, Math.min(index, shown.length - 1))
        rowItems.itemAt(i).focusAction()
    }
    function restoreRow() {
        if (opened) focusRow(shown.findIndex(item => item.key === selectedKey))
    }
    function back() {
        if (updateSheet.opened) updateSheet.close()
        else if (scaleSheet.opened) scaleSheet.close()
        else if (deadzoneSheet.opened) deadzoneSheet.close()
        else if (keyboard.opened) keyboard.close()
        else if (choices.opened) choices.close()
        else if (hostSheet.opened) hostSheet.back()
        else if (search.activeFocus && search.text.length) search.clear()
        else close()
    }
    function state() {
        function center(item) { const p = item.mapToItem(null, item.width / 2, item.height / 2); return {x: Math.round(p.x), y: Math.round(p.y)} }
        const values = {}
        for (const item of definitions) values[item.key] = value(item.key)
        return {opened: opened, width: width, height: height, category: category, query: search.text, keys: shown.map(item => item.key),
            values: values, error: error,
            host: hostSheet.state(), choicesOpen: choices.opened || scaleSheet.opened || deadzoneSheet.opened, keyboardOpen: keyboard.opened,
            search: center(search), back: center(done), scroll: rows.contentY}
    }
    HostDefaults {
        id: hostSheet
        controller: hub.hostController; settingsProvider: hub.settingsProvider
        backLabel: "Settings"
        onClosed: Qt.callLater(hub.restoreRow)
    }
    UpdateSettings {
        id: updateSheet; controller: hub.updateController; unit: hub.unit
        onClosed: Qt.callLater(hub.restoreRow)
    }
    EndpointKeyboard { id: keyboard; parent: Overlay.overlay; titleEntry: true }
    VideoScaleSettings {
        id: scaleSheet; settingsProvider: hub.settingsProvider; unit: hub.unit
        onClosed: Qt.callLater(hub.restoreRow)
    }
    DeadzoneSettings {
        id: deadzoneSheet; settingsProvider: hub.settingsProvider; unit: hub.unit
        onClosed: Qt.callLater(hub.restoreRow)
    }
    Popup {
        id: choices; objectName: "settings-choice-popup"
        property var definition: ({key: "", title: "", scope: "", detail: ""})
        readonly property var values: hub.options(definition.key)
        parent: Overlay.overlay; anchors.centerIn: parent
        width: Math.min(660 * hub.unit, parent ? parent.width - 32 : 660)
        height: Math.min(implicitHeight, parent ? parent.height - 32 : 760)
        padding: 24 * hub.unit; modal: true; focus: true; closePolicy: Popup.CloseOnEscape
        function focusChoice(index, direction) {
            while (index >= 0 && index < values.length && values[index].available === false) index += direction
            if (index < 0) index = 0
            if (index >= values.length) choiceBack.forceActiveFocus()
            else choiceButtons.itemAt(index).forceActiveFocus()
        }
        onOpened: focusChoice(Math.max(0, values.findIndex(item => item.id === hub.value(definition.key))), -1)
        onClosed: Qt.callLater(hub.restoreRow)
        background: Rectangle { color: NovaTheme.panel; radius: 12; border.color: NovaTheme.divider }
        contentItem: ColumnLayout {
            spacing: 12 * hub.unit
            Copy { text: choices.definition.title; font.bold: true; font.pixelSize: 26 * hub.unit * NovaTheme.fontScale }
            Copy { text: choices.definition.scope; color: NovaTheme.secondary }
            NovaScrollColumn {
                Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 0
                reserveScrollBarSpace: true; spacing: 8 * hub.unit
                Copy { text: choices.definition.detail; color: NovaTheme.secondary }
                Repeater {
                    id: choiceButtons; model: choices.values
                    NovaButton {
                        required property var modelData
                        required property int index
                        objectName: "settings-choice-" + index
                        Layout.fillWidth: true; unit: hub.unit
                        enabled: modelData.available !== false
                        text: modelData.title + (modelData.available === false ? " · Unavailable" : "") + (hub.value(choices.definition.key) === modelData.id ? " · Selected" : "")
                        Accessible.checkable: true; Accessible.checked: hub.value(choices.definition.key) === modelData.id
                        onClicked: if (hub.save(choices.definition.key, modelData.id)) choices.close()
                        Keys.onUpPressed: choices.focusChoice(index - 1, -1)
                        Keys.onDownPressed: choices.focusChoice(index + 1, 1)
                    }
                }
            }
            Copy { visible: hub.error.length > 0; text: hub.error; color: NovaTheme.warning }
            NovaButton {
                id: choiceBack; objectName: "settings-choice-back"
                unit: hub.unit; Layout.fillWidth: true; text: "Cancel"
                onClicked: choices.close()
                Keys.onUpPressed: choices.focusChoice(choices.values.length - 1, -1)
            }
        }
    }
    component Copy: Label {
        textFormat: Text.PlainText; color: NovaTheme.text; wrapMode: Text.WordWrap
        font.pixelSize: 16 * hub.unit * NovaTheme.fontScale; font.weight: Font.Normal; Layout.fillWidth: true
    }
    contentItem: ColumnLayout {
        spacing: 16 * hub.unit
        RowLayout {
            Layout.fillWidth: true; spacing: 20 * hub.unit
            ColumnLayout {
                Layout.fillWidth: true; spacing: 2
                Copy { text: "Settings"; font.pixelSize: 32 * hub.unit * NovaTheme.fontScale; font.bold: true }
                Copy { text: "Make Nova yours"; color: NovaTheme.secondary }
            }
            TextField {
                id: search; objectName: "settings-search"
                Layout.preferredWidth: Math.min(440 * hub.unit, hub.width * 0.43)
                Layout.preferredHeight: 54 * hub.unit
                color: NovaTheme.text; font.pixelSize: 18 * hub.unit * NovaTheme.fontScale
                placeholderText: "Search settings"; placeholderTextColor: NovaTheme.secondary
                maximumLength: 120; selectByMouse: true
                Accessible.name: "Search all settings"
                onTextChanged: { rows.contentY = 0; error = "" }
                function typeQuery() { keyboard.edit(search, false, "Search settings"); keyboard.alphabet = true }
                TapHandler { onTapped: search.typeQuery() }
                Keys.onReturnPressed: typeQuery()
                Keys.onEnterPressed: typeQuery()
                Keys.onDownPressed: hub.focusRow(0)
                Keys.onEscapePressed: event => { if (text.length) { clear(); event.accepted = true } else event.accepted = false }
                background: Rectangle { color: NovaTheme.input; radius: 8; border.color: search.activeFocus ? NovaTheme.focus : NovaTheme.divider; border.width: search.activeFocus ? 3 : 1 }
            }
            NovaButton {
                id: clearSearch; objectName: "settings-search-clear"
                unit: hub.unit; text: "Clear"; visible: search.text.length > 0
                Layout.preferredWidth: Math.max(100, 100 * hub.unit * NovaTheme.fontScale)
                onClicked: { search.clear(); search.forceActiveFocus() }
                Keys.onLeftPressed: search.forceActiveFocus()
                Keys.onDownPressed: hub.focusRow(0)
            }
        }
        RowLayout {
            Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 0
            spacing: 20 * hub.unit
            NovaScrollColumn {
                Layout.preferredWidth: 215 * hub.unit * Math.min(NovaTheme.fontScale, 1.15)
                Layout.fillHeight: true; Layout.minimumHeight: 0; spacing: 8 * hub.unit
                Repeater {
                    id: categoryButtons; model: hub.categories
                    NovaButton {
                        required property var modelData
                        required property int index
                        objectName: "settings-category-" + modelData.id
                        unit: hub.unit; Layout.fillWidth: true
                        text: modelData.title
                        primary: hub.category === modelData.id && !search.text.trim().length
                        Accessible.checkable: true; Accessible.checked: primary
                        onClicked: hub.selectCategory(modelData.id)
                        Keys.onUpPressed: index ? categoryButtons.itemAt(index - 1).forceActiveFocus() : search.forceActiveFocus()
                        Keys.onDownPressed: index < hub.categories.length - 1 ? categoryButtons.itemAt(index + 1).forceActiveFocus() : done.forceActiveFocus()
                        Keys.onRightPressed: hub.focusRow(0)
                    }
                }
            }
            ColumnLayout {
                Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 0
                Copy {
                    text: search.text.trim().length ? shown.length + (shown.length === 1 ? " result across all settings" : " results across all settings")
                        : categories.find(item => item.id === category).title
                    color: NovaTheme.secondary
                }
                NovaScrollColumn {
                    id: rows; objectName: "settings-rows"
                    Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 0
                    reserveScrollBarSpace: true; spacing: 10 * hub.unit
                    Copy { visible: !hub.shown.length; text: "No settings found. Try another word or clear your search." }
                    Repeater {
                        id: rowItems; model: hub.shown
                        RowLayout {
                            id: settingRow
                            required property var modelData
                            required property int index
                            Layout.fillWidth: true; spacing: 8 * hub.unit
                            Layout.preferredHeight: rowCopy.implicitHeight + 24 * hub.unit
                            Layout.minimumHeight: Layout.preferredHeight
                            function focusAction() { action.forceActiveFocus() }
                            NovaButton {
                                id: action; objectName: "settings-row-" + settingRow.modelData.key
                                unit: hub.unit; Layout.fillWidth: true; Layout.fillHeight: true
                                text: settingRow.modelData.title + ": " + hub.label(settingRow.modelData.key)
                                Accessible.description: settingRow.modelData.scope + ". " + settingRow.modelData.detail
                                Accessible.checkable: typeof hub.value(settingRow.modelData.key) === "boolean"
                                Accessible.checked: Accessible.checkable && hub.value(settingRow.modelData.key)
                                leftPadding: 16 * hub.unit; rightPadding: 16 * hub.unit
                                topPadding: 12 * hub.unit; bottomPadding: 12 * hub.unit
                                onActiveFocusChanged: if (activeFocus) hub.selectedKey = settingRow.modelData.key
                                onClicked: hub.activate(settingRow.modelData)
                                contentItem: ColumnLayout {
                                    id: rowCopy
                                    spacing: 4 * hub.unit
                                    Copy { text: settingRow.modelData.title; font.bold: true; font.pixelSize: 20 * hub.unit * NovaTheme.fontScale; color: action.activeFocus ? NovaTheme.focusText : NovaTheme.text }
                                    Copy { text: hub.label(settingRow.modelData.key); color: action.activeFocus ? NovaTheme.focusText : NovaTheme.text }
                                    Copy { text: settingRow.modelData.scope; font.pixelSize: 14 * hub.unit * NovaTheme.fontScale; color: action.activeFocus ? NovaTheme.focusText : NovaTheme.secondary }
                                }
                                Keys.onUpPressed: settingRow.index ? hub.focusRow(settingRow.index - 1) : search.forceActiveFocus()
                                Keys.onDownPressed: settingRow.index < hub.shown.length - 1 ? hub.focusRow(settingRow.index + 1) : done.forceActiveFocus()
                                Keys.onLeftPressed: hub.focusCategory()
                                Keys.onRightPressed: if (reset.visible) reset.forceActiveFocus()
                            }
                            NovaButton {
                                id: reset; objectName: "settings-reset-" + settingRow.modelData.key
                                unit: hub.unit; Layout.preferredWidth: Math.max(100, 110 * hub.unit * NovaTheme.fontScale)
                                text: "Reset"; visible: settingRow.modelData.reset !== undefined
                                Accessible.name: "Reset " + settingRow.modelData.title
                                Accessible.description: "Reset only this setting on this device."
                                onClicked: hub.save(settingRow.modelData.key, settingRow.modelData.reset)
                                Keys.onLeftPressed: action.forceActiveFocus()
                                Keys.onUpPressed: settingRow.index ? hub.focusRow(settingRow.index - 1) : search.forceActiveFocus()
                                Keys.onDownPressed: settingRow.index < hub.shown.length - 1 ? hub.focusRow(settingRow.index + 1) : done.forceActiveFocus()
                            }
                        }
                    }
                }
            }
        }
        Copy { visible: hub.error.length > 0; text: hub.error; color: NovaTheme.warning }
        RowLayout {
            Layout.fillWidth: true; spacing: 16 * hub.unit
            Copy { text: "A  Select     B  Back"; color: NovaTheme.secondary }
            NovaButton {
                id: done; objectName: "settings-back"; unit: hub.unit; text: "Back to Library"
                onClicked: hub.close()
                Keys.onUpPressed: hub.restoreRow()
                Keys.onLeftPressed: hub.focusCategory()
            }
        }
    }
}

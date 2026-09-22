import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

FocusScope {
    id: setup
    objectName: "play-setup"
    required property var settingsProvider
    property var hostSettingsController: null
    property var gameTools: null
    readonly property var toolsState: gameTools ? gameTools.state : ({})
    readonly property var encoderChoices: [{ encoderBackend: "", label: "Host default", detail: "Let the PC choose its encoder." }].concat((toolsState.settings || {}).encoders || [])
    readonly property var presetChoices: [
        { profilePreference: "auto", label: "Auto", detail: "Use the host's automatic launch preset." },
        { profilePreference: "quality", label: "Quality", detail: "Ask the host to favor picture detail within your stream choices." },
        { profilePreference: "high_fps", label: "High FPS", detail: "Favor smooth motion. An explicit frame-rate choice takes priority." },
        { profilePreference: "stability", label: "Stability", detail: "Ask the host to favor a steady stream." }
    ]
    readonly property bool setupAllowed: !gameTools || (!toolsState.writing && !toolsState.uncertain
        && ((!configuration.encoderBackend && (configuration.profilePreference || "auto") === "auto")
            || (!toolsState.busy && toolsState.available && Object.keys(toolsState.settings || {}).length > 0
                && encoderChoices.some(choice => choice.encoderBackend === (configuration.encoderBackend || "")))))
    readonly property var requestedConfiguration: {
        const result = Object.assign({}, configuration)
        if (result.profilePreference === "high_fps" && !overrides.fps)
            result.fps = Math.max(30, settingsProvider.displayRateLimit(displayCapabilities.known ? displayCapabilities.refreshHz : 0))
        return result
    }
    Timer { id: planRefresh; interval: 150; onTriggered: if (setup.gameTools && setup.visible) setup.gameTools.review(setup.plan.configuration) }
    function checkPlan() { if (gameTools && !spaceSession) planRefresh.restart() }

    property string hostId: ""
    property string gameId: ""
    property string hostName: "Your PC"
    property string gameTitle: "Selected game"
    property string destinationId: "desktop"
    property string destinationName: "Desktop"
    readonly property bool spaceDestination: destinationId.length > 0 && destinationId !== "desktop"
    property string returnLabel: "Back to details"
    property var configuration: ({ width: 1280, height: 800, fps: 60, bitrateKbps: 20000, faceButtonLayout: "default", launchMode: "default", videoCodec: "h264" })
    property var launchPolicy: ({ known: false, hostDefault: "", allowed: [] })
    property var streamCapabilities: ({})
    property var displayPlanner: ({})
    property var displayCapabilities: ({})
    readonly property bool spaceSession: spaceDestination || gameId.indexOf("space.") === 0
        || gameId === "706f6c61-7269-4373-8000-6d756c746973" || gameId === "1347244801"
    readonly property var plan: settingsProvider.streamPlan(requestedConfiguration, streamCapabilities, displayPlanner, displayCapabilities, spaceSession)
    readonly property var audioSettings: settingsProvider.audioSettings
    readonly property int audioChannels: spaceSession ? 2 : audioSettings.channels
    readonly property string audioLabel: audioChannels === 8 ? "7.1 surround" : audioChannels === 6 ? "5.1 surround" : "Stereo audio"
    readonly property var modeOptions: [
        { launchMode: "headless_stream", label: "Private Stream", detail: "Start a private streaming desktop without taking over your PC's screen." },
        { launchMode: "host_virtual_display", label: "Host Virtual Display", detail: "Use a virtual display on your PC for this stream." },
        { launchMode: "desktop_display", label: "Mirror Desktop", detail: "Stream the desktop shown on your PC's screen." },
        { launchMode: "desktop_takeover", label: "Desktop Takeover", detail: "Use your PC's desktop for this game and stream." },
        { launchMode: "windowed_stream", label: "Private Stream (GPU-native)", detail: "Use the host's GPU-native private streaming path." },
        { launchMode: "gamescope_stream", label: "Gamescope Stream", detail: "Run the game in the host's Gamescope streaming session." }
    ]
    readonly property var launchChoices: [{ launchMode: "default", label: spaceDestination ? "Space default" : "Host default",
        detail: spaceDestination ? "Use this Space's launch settings." : "Let your PC choose its configured launch mode. This does not change the PC's settings." }].concat(
            modeOptions.filter(option => !spaceDestination && launchPolicy.known && (launchPolicy.allowed || []).indexOf(option.launchMode) >= 0))
    readonly property bool launchModeAllowed: configuration.launchMode === "default"
        || (!spaceDestination && launchPolicy.known && (launchPolicy.allowed || []).indexOf(configuration.launchMode) >= 0)
    readonly property string effectiveFaceButtonLayout: configuration.faceButtonLayout === "default"
        ? settingsProvider.defaultFaceButtonLayout : configuration.faceButtonLayout
    property bool custom: false
    property var overrides: ({})
    property int focusedRow: 0
    property bool editable: true
    property string error: ""
    property string notice: ""
    readonly property real unit: Math.max(0.85, Math.min(1.15, width / 1280))
    readonly property var rows: [resolution, rate, bitrate, faceButtons, launchMode, videoCodec, encoder, tuning, steamLaunch, reset].filter(row => row.visible)
    signal choiceOpened()
    signal focusPlayRequested()
    signal backRequested()
    signal hostDefaultsMayHaveChanged()
    signal novaDefaultsChanged()
    Connections {
        target: setup.hostSettingsController
        function onHostDefaultsMayHaveChanged() { setup.hostDefaultsMayHaveChanged() }
        function onNovaDefaultsChanged() { setup.novaDefaultsChanged() }
    }
    HostDefaults {
        id: hostDefaults
        controller: setup.hostSettingsController
        settingsProvider: setup.settingsProvider
        onClosed: if (setup.visible) Qt.callLater(setup.editable ? everyGame.forceActiveFocus : setup.focusBack)
    }

    onPlanChanged: {
        // A display move or PC refresh can withdraw the focused rate. Return
        // to its row so a copied popup model cannot offer an obsolete choice.
        if (picker && picker.opened && ((picker.returnFocus === rate
                && JSON.stringify(picker.choices.filter(c => c.customField === undefined)) !== JSON.stringify(plan.rates)) || (picker.returnFocus === videoCodec
                && JSON.stringify(picker.choices) !== JSON.stringify(plan.codecs)))) picker.close()
    }

    function prepare() {
        customEditor.close()
        picker.close()
        planRead.contentY = 0
        focusedRow = 0
        const saved = settingsProvider.load(hostId, gameId)
        configuration = saved.configuration
        custom = saved.custom
        overrides = saved.overrides || ({})
        error = ""
        notice = ""
        if (gameTools && !spaceSession) gameTools.prepare(hostId, gameId, plan.configuration)
        if (!launchModeAllowed && launchPolicy.known) {
            if (save({ launchMode: "default" })) notice = "Your saved launch mode is no longer available. Using host default."
        }
    }
    function save(values) {
        if (!editable || !settingsProvider.saveChoice(hostId, gameId, values)) {
            error = "Couldn't save these choices. Try again."
            return false
        }
        reloadChoices()
        error = ""
        notice = ""
        return true
    }
    function reloadChoices() {
        const saved = settingsProvider.load(hostId, gameId)
        configuration = saved.configuration
        custom = saved.custom
        overrides = saved.overrides || ({})
        checkPlan()
    }
    function resetChoice(field) {
        if (!editable || !settingsProvider.resetChoice(hostId, gameId, field)) {
            error = "Couldn't reset this choice. Try again."
            return false
        }
        reloadChoices()
        error = ""
        notice = "This choice now uses its default. Your other choices are unchanged."
        return true
    }
    function focusSettings() { resolution.forceActiveFocus() }
    function focusLast() { reset.forceActiveFocus() }
    function focusBack() { backButton.forceActiveFocus() }
    function closeChoice() {
        if (customEditor.opened) { customEditor.close(); return true }
        if (hostDefaults.opened) { hostDefaults.close(); return true }
        if (!picker.opened) return false
        picker.close()
        return true
    }
    function closeHostDefaults() { hostDefaults.close(); customEditor.close(); picker.close() }
    function state() {
        function center(control) {
            if (!control) return { x: 0, y: 0 }
            const point = control.mapToItem(null, control.width / 2, control.height / 2)
            return { x: Math.round(point.x), y: Math.round(point.y) }
        }
        return { configuration: configuration, custom: custom, overrides: overrides, choicesOpen: picker.opened || customEditor.opened, customEditorOpen: customEditor.opened, error: error,
            hostDefaults: hostDefaults.state(), hostPlan: toolsState, setupAllowed: setupAllowed,
            destination: { id: destinationId, name: destinationName, space: spaceDestination },
            readPlan: { headline: planHeadline, intro: planIntro, facts: planFacts, scroll: planRead.contentY,
                scrollMaximum: Math.max(0, planRead.contentHeight - planRead.height) },
            effectiveFaceButtonLayout: effectiveFaceButtonLayout, launchModeAllowed: launchModeAllowed,
            launchChoices: launchChoices.map(choice => choice.launchMode), notice: notice, streamPlan: plan,
            audio: { channels: audioChannels, playHostAudio: audioSettings.playHostAudio, label: audioLabel },
            controls: { resolution: center(resolution), rate: center(rate), bitrate: center(bitrate), faceButtons: center(faceButtons),
                launchMode: center(launchMode), videoCodec: center(videoCodec), resetChoice: center(choiceReset), plan: center(planRead), everyGame: center(everyGame) },
            choiceCenters: picker.choices.map((choice, index) => center(choiceButtons.itemAt(index))) }
    }
    function modeLabel(mode) {
        if (spaceDestination && mode === "default") return "Space default"
        return (modeOptions.find(option => option.launchMode === mode) || {}).label
            || (mode === "headless_dongle" ? "Headless Dongle" : "Host default")
    }
    function hostPlanFact(field) {
        const labels = { display_mode: "Display", target_bitrate_kbps: "Bitrate", target_fps: "Frame rate", preferred_codec: "Codec", hdr: "Color" }
        let value = field.value
        if (field.key === "display_mode") { const parts = value.split("x"); value = parts[0] + " × " + parts[1] + " · " + parts[2] + " fps" }
        if (field.key === "target_bitrate_kbps") value = (Number(value) / 1000) + " Mbps"
        if (field.key === "target_fps") value += " fps"
        if (field.key === "preferred_codec") value = value.toUpperCase()
        if (field.key === "hdr") value = value === "true" ? "HDR requested by host · Deck uses SDR" : "SDR"
        const sources = { explicit_launch_request: "Your launch choice", client_launch_request: "Nova's stream choice", paired_client: "PC device settings",
            client_profile: "PC profile", device_profile_v1: "Device profile", capability_validation: "Available hardware", composed_display_components: "Display choices" }
        return (labels[field.key] || field.key) + ": " + value + " · " + (sources[field.source] || "Host plan") + (field.normalized ? " (adjusted)" : "")
    }
    readonly property string planHeadline: spaceDestination ? "Play in " + destinationName
        : configuration.launchMode === "default" ? "Play on Desktop" : modeLabel(configuration.launchMode)
    readonly property string planIntro: "Start " + gameTitle + (spaceDestination ? " in " + destinationName + " on " : " on ")
        + hostName + " and stream it here."
    readonly property var planFacts: [
        { key: "Place", value: destinationName, detail: hostName },
        { key: "Stream", value: plan.configuration.width + " × " + plan.configuration.height + " · " + plan.configuration.fps + " fps",
            detail: plan.adjustment || ((overrides.resolution || overrides.fps) ? "This game's choices" : "Nova defaults"), warning: plan.adjustment.length > 0 },
        { key: "Picture", value: plan.videoLabel + " · " + (plan.configuration.bitrateKbps / 1000) + " Mbps",
            detail: plan.displayLabel + " · " + plan.codecDetail },
        { key: "Audio", value: audioLabel + " · PC audio " + (audioSettings.playHostAudio ? "on" : "off"),
            detail: spaceDestination ? "Spaces use stereo. Your device audio preference stays saved." : "Device setting · System › Audio" },
        { key: "Buttons", value: effectiveFaceButtonLayout === "positions" ? "Match positions" : "Match labels",
            detail: overrides.faceButtonLayout ? "This game" : "Device default" },
        { key: "Host plan", value: toolsState.busy ? "Checking…" : ((toolsState.plan || {}).label || (toolsState.plan || {}).preset || "Not supplied"),
            detail: ((toolsState.plan || {}).fields || []).filter(f => f.key !== "target_fps").map(hostPlanFact).join("\n") || (toolsState.copy || "The host confirms its settings when the game starts.") },
        { key: "Launch", value: modeLabel(configuration.launchMode), detail: spaceDestination ? "Uses this Space's launch settings."
            : configuration.launchMode === "default" ? (launchPolicy.known ? "PC default: " + modeLabel(launchPolicy.hostDefault) : "Uses your PC's launch settings.")
            : "Applies to this launch; the PC default stays unchanged." }
    ]
    component Copy: Label {
        textFormat: Text.PlainText
        color: NovaTheme.text
        font.pixelSize: 20 * unit * NovaTheme.fontScale
        wrapMode: Text.WordWrap
        elide: Text.ElideRight
    }
    component Action: NovaButton { unit: setup.unit }
    component Setting: Action {
        enabled: setup.editable
        property int position: rows.indexOf(this)
        property string label: ""
        property string value: ""
        property string field: ""
        property string explanation: ""
        property string defaultExplanation: ""
        text: label + ": " + value
        Layout.fillWidth: true
        Layout.preferredHeight: Math.max(60 * unit, 52 * unit * NovaTheme.fontScale)
        Accessible.description: (overrides[field] ? "This game. " : "Default. ") + explanation
        onActiveFocusChanged: if (activeFocus) focusedRow = position
        Keys.onUpPressed: position > 0 ? rows[position - 1].forceActiveFocus() : focusPlayRequested()
        Keys.onDownPressed: position < rows.length - 1 ? rows[position + 1].forceActiveFocus() : focusPlayRequested()
        Keys.onLeftPressed: planRead.forceActiveFocus()
        Keys.onRightPressed: clicked()
        contentItem: RowLayout {
            spacing: 12 * unit
            ColumnLayout {
                spacing: 2 * unit
                Copy { text: label; color: parent.parent.parent.activeFocus ? NovaTheme.focusText : NovaTheme.text; font.pixelSize: 17 * unit * NovaTheme.fontScale }
                Copy { text: parent.parent.parent === steamLaunch ? "This game · On PC" : field ? (overrides[field] ? "This game" : "Default") : "This game's choices"; color: parent.parent.parent.activeFocus ? NovaTheme.focusText : NovaTheme.secondary; font.pixelSize: 12 * unit * NovaTheme.fontScale }
            }
            Copy {
                Layout.fillWidth: true
                text: value
                horizontalAlignment: Text.AlignRight
                color: parent.parent.activeFocus ? NovaTheme.focusText : NovaTheme.text
                font.weight: Font.DemiBold
                font.pixelSize: 20 * unit * NovaTheme.fontScale
                maximumLineCount: 1
            }
        }
    }
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 32 * unit
        anchors.bottomMargin: 120 * unit
        spacing: 20 * unit
        RowLayout {
            Layout.fillWidth: true
            Copy { text: "Play Setup"; font.pixelSize: 32 * unit * NovaTheme.fontScale; font.bold: true; Layout.fillWidth: true }
            Action {
                id: everyGame
                objectName: "play-setup-every-game"
                text: "Every Game"
                visible: setup.hostSettingsController !== null && setup.hostSettingsController.state.supported
                enabled: setup.editable
                onClicked: { setup.choiceOpened(); hostDefaults.open() }
                Keys.onDownPressed: setup.focusSettings()
                Keys.onLeftPressed: planRead.forceActiveFocus()
            }
        }
        Copy {
            Layout.fillWidth: true
            text: gameTitle + " · " + destinationName + " · " + hostName
            maximumLineCount: 1
            color: NovaTheme.secondary
        }
        Copy {
            Layout.fillWidth: true
            visible: setup.error.length > 0
            text: setup.error
            color: NovaTheme.warning
            font.pixelSize: 16 * unit * NovaTheme.fontScale
        }
        RowLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.minimumHeight: 0
            spacing: 32 * unit
            NovaScrollColumn {
                id: planRead
                objectName: "play-setup-plan"
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumHeight: 0
                Layout.preferredWidth: 0.9
                spacing: 12 * unit
                Accessible.role: Accessible.StaticText
                Accessible.name: planHeadline + ". " + planIntro
                activeFocusOnTab: true
                Keys.onDownPressed: contentY = Math.min(Math.max(0, contentHeight - height), contentY + 64 * unit)
                Keys.onUpPressed: if (contentY <= 0 && everyGame.visible && everyGame.enabled) everyGame.forceActiveFocus(); else contentY = Math.max(0, contentY - 64 * unit)
                Keys.onRightPressed: rows[Math.min(focusedRow, rows.length - 1)].forceActiveFocus()
                Keys.onLeftPressed: backButton.forceActiveFocus()
                Keys.onReturnPressed: focusPlayRequested()
                Rectangle {
                    parent: planRead
                    anchors.fill: parent
                    color: "transparent"
                    border.width: planRead.activeFocus ? 2 * unit : 0
                    border.color: NovaTheme.focus
                    radius: 6 * unit
                    z: 1
                }
                Copy { text: "WHAT WILL HAPPEN"; color: NovaTheme.secondary; font.pixelSize: 12 * unit * NovaTheme.fontScale; font.weight: Font.DemiBold }
                Copy { Layout.fillWidth: true; text: planHeadline; font.pixelSize: 28 * unit * NovaTheme.fontScale; font.bold: true }
                Copy { Layout.fillWidth: true; text: planIntro; color: NovaTheme.secondary; font.pixelSize: 16 * unit * NovaTheme.fontScale }
                Copy { Layout.fillWidth: true; text: "Choices are saved for this game on this device."; color: NovaTheme.secondary; font.pixelSize: 14 * unit * NovaTheme.fontScale }
                Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: NovaTheme.divider }
                Repeater {
                    model: planFacts
                    RowLayout {
                        required property var modelData
                        Layout.fillWidth: true
                        Layout.topMargin: 3 * unit
                        Layout.bottomMargin: 3 * unit
                        spacing: 16 * unit
                        Copy {
                            text: modelData.key.toUpperCase()
                            Layout.alignment: Qt.AlignTop
                            Layout.preferredWidth: 84 * unit
                            color: NovaTheme.secondary
                            font.pixelSize: 11 * unit * NovaTheme.fontScale
                            font.weight: Font.DemiBold
                        }
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 3 * unit
                            Copy { Layout.fillWidth: true; text: modelData.value; font.pixelSize: 17 * unit * NovaTheme.fontScale; font.weight: Font.DemiBold }
                            Copy {
                                Layout.fillWidth: true
                                visible: modelData.detail.length > 0
                                text: modelData.detail
                                color: modelData.warning ? NovaTheme.warning : NovaTheme.secondary
                                font.pixelSize: 13 * unit * NovaTheme.fontScale
                            }
                        }
                    }
                }
            }
            NovaScrollColumn {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumHeight: 0
                Layout.preferredWidth: 1.1
                spacing: 10 * unit
                Copy { text: "WHAT YOU CAN CHANGE"; color: NovaTheme.secondary; font.pixelSize: 12 * unit * NovaTheme.fontScale; font.weight: Font.DemiBold }
                Copy { text: "This game · Saved on this device"; color: NovaTheme.secondary; font.pixelSize: 16 * unit * NovaTheme.fontScale }
                Setting {
                    id: resolution; objectName: "play-setup-resolution"
                    field: "resolution"
                    explanation: "Choose the stream size. Your PC's recommendations are marked in the list."
                    defaultExplanation: "Use Nova's current device-default stream size."
                    label: "Resolution"; value: configuration.width + " × " + configuration.height
                    onClicked: picker.choose(resolution, "Resolution", plan.resolutions,
                        Math.max(0, plan.resolutions.findIndex(choice => choice.width === configuration.width && choice.height === configuration.height)))
                }
                Setting {
                    id: rate; objectName: "play-setup-rate"
                    field: "fps"
                    explanation: "Higher frame rates make motion smoother. The plan accounts for the current display and PC limits."
                    defaultExplanation: "Use Nova's current device-default frame rate, adjusted to the display and PC when needed."
                    label: "Frame rate"; value: plan.configuration.fps + " fps" + (plan.adjustment ? " · Adjusted" : "")
                    onClicked: if (plan.rates.length) picker.choose(rate, "Frame rate", plan.rates,
                        Math.max(0, plan.rates.findIndex(choice => choice.fps === plan.configuration.fps)))
                }
                Setting {
                    id: bitrate; objectName: "play-setup-bitrate"
                    field: "bitrateKbps"
                    explanation: "More bandwidth preserves fine detail. This is the next stream's starting bitrate."
                    defaultExplanation: "Use Nova's current device-default starting bitrate."
                    label: "Bitrate"; value: (configuration.bitrateKbps / 1000) + " Mbps"
                    onClicked: {
                        const choices = [10,20,30,40].map(value => ({label: value + " Mbps", detail: "Starting bitrate for this stream.", bitrateKbps: value * 1000}))
                        if (!choices.some(choice => choice.bitrateKbps === configuration.bitrateKbps))
                            choices.push({label: (configuration.bitrateKbps / 1000) + " Mbps", detail: "Your saved custom bitrate.", bitrateKbps: configuration.bitrateKbps})
                        picker.choose(bitrate, "Bitrate", choices, choices.findIndex(choice => choice.bitrateKbps === configuration.bitrateKbps))
                    }
                }
                Setting {
                    id: faceButtons; objectName: "play-setup-face-buttons"
                    field: "faceButtonLayout"
                    explanation: "Match the labels or positions on your controller, or inherit the device setting."
                    defaultExplanation: "Follow the face-button default in System."
                    label: "Face buttons"
                    value: configuration.faceButtonLayout === "default" ? "Device default"
                        : configuration.faceButtonLayout === "positions" ? "Match positions" : "Match labels"
                    onClicked: picker.choose(faceButtons, "Face buttons", [
                        { label: "Device default", detail: "Use the default in System for games without an override.", faceButtonLayout: "default" },
                        { label: "Match labels", detail: "Send A as A, B as B, X as X and Y as Y.", faceButtonLayout: "labels" },
                        { label: "Match positions", detail: "Swap A/B and X/Y so your pad's positions match a Switch-style layout.", faceButtonLayout: "positions" }
                    ], configuration.faceButtonLayout === "default" ? 0 : configuration.faceButtonLayout === "labels" ? 1 : 2)
                }
                Setting {
                    id: launchMode; objectName: "play-setup-launch-mode"
                    field: "launchMode"
                    explanation: spaceDestination ? "The selected Space provides its own launch settings." : "Choose where the game runs for this session, or use the PC's default."
                    defaultExplanation: spaceDestination ? "Use this Space's launch settings." : "Follow the PC's configured default launch mode."
                    label: "Launch mode"; value: modeLabel(configuration.launchMode)
                    onClicked: picker.choose(launchMode, "Launch mode", launchChoices,
                        Math.max(0, launchChoices.findIndex(choice => choice.launchMode === configuration.launchMode)))
                }
                Setting {
                    id: videoCodec; objectName: "play-setup-codec"
                    field: "videoCodec"
                    explanation: "Choose video compression for this game. The plan shows the codec the next stream will use."
                    defaultExplanation: "Use Nova Deck's compatible H.264 default."
                    label: "Video codec"; value: configuration.videoCodec === "auto" ? "Auto" : configuration.videoCodec === "hevc" ? "HEVC" : "H.264"
                    onClicked: picker.choose(videoCodec, "Video codec", plan.codecs,
                        Math.max(0, plan.codecs.findIndex(choice => choice.videoCodec === configuration.videoCodec)))
                }
                Setting {
                    id: encoder; objectName: "play-setup-encoder"
                    visible: !!gameTools && !spaceSession && (encoderChoices.length > 1 || !!configuration.encoderBackend)
                    field: "encoderBackend"; label: "Encoder"
                    value: (encoderChoices.find(c => c.encoderBackend === (configuration.encoderBackend || "")) || {}).label || "Unavailable choice"
                    explanation: "This launch only. Exact encoders must still be available when the game starts."
                    defaultExplanation: "Use the PC's encoder selection."
                    onClicked: picker.choose(encoder, "Encoder", encoderChoices, Math.max(0, encoderChoices.findIndex(c => c.encoderBackend === (configuration.encoderBackend || ""))))
                }
                Setting {
                    id: tuning; objectName: "play-setup-tuning"
                    visible: !!gameTools && !spaceSession && (Object.keys(toolsState.plan || {}).length > 0 || (configuration.profilePreference || "auto") !== "auto")
                    field: "profilePreference"; label: "Tuning"
                    value: (presetChoices.find(c => c.profilePreference === (configuration.profilePreference || "auto")) || presetChoices[0]).label
                    explanation: "Ask for a launch preset. The host plan shows what the PC granted; explicit stream choices stay in effect."
                    defaultExplanation: "Use the automatic launch preset."
                    onClicked: picker.choose(tuning, "Tuning", Object.keys(toolsState.plan || {}).length ? presetChoices : [presetChoices[0]],
                        Math.max(0, presetChoices.findIndex(c => c.profilePreference === (configuration.profilePreference || "auto"))))
                }
                Setting {
                    id: steamLaunch; objectName: "play-setup-steam"
                    enabled: setup.editable && !toolsState.busy && !toolsState.uncertain
                    visible: !!gameTools && !spaceSession && ((toolsState.steam || {}).allowed || []).length > 0
                    label: "Steam launch"; value: (toolsState.steam || {}).mode === "big-picture" ? "Big Picture" : "Direct"
                    explanation: "Saved on this PC for this game. Big Picture opens Steam's controller interface; Direct starts the game."
                    onClicked: picker.choose(steamLaunch, "Steam launch", ((toolsState.steam || {}).allowed || []).map(mode => ({
                        steamMode: mode, label: mode === "direct" ? "Direct" : "Big Picture",
                        detail: mode === "direct" ? "Start the game directly on this PC." : "Open Steam Big Picture on this PC before the game."
                    })), Math.max(0, ((toolsState.steam || {}).allowed || []).indexOf((toolsState.steam || {}).mode)))
                }
                Setting {
                    id: reset; objectName: "play-setup-reset"
                    explanation: "Reset this game's stream, face-button and launch choices. Other games and device audio stay unchanged."
                    label: "Reset"; value: custom ? "Use defaults" : "Defaults in use"
                    onClicked: {
                        setup.choiceOpened()
                        if (settingsProvider.reset(hostId, gameId)) prepare()
                        else error = "Couldn't reset these choices. Try again."
                    }
                }
                Copy {
                    Layout.fillWidth: true
                    text: (rows[focusedRow] || resolution).explanation
                    color: NovaTheme.secondary
                    font.pixelSize: 14 * unit * NovaTheme.fontScale
                }
                Copy {
                    Layout.fillWidth: true
                    visible: notice.length > 0 || !launchModeAllowed || !plan.playable || plan.adjustment.length > 0
                    text: (!plan.playable ? plan.reason : "") || (!launchModeAllowed
                        ? "Refresh this PC to verify your saved launch mode, or choose Host default." : "") || notice || plan.adjustment
                    color: NovaTheme.warning
                    font.pixelSize: 16 * unit * NovaTheme.fontScale
                }
            }
        }
    }
    Action {
        id: backButton
        objectName: "play-setup-back"
        text: setup.returnLabel
        anchors.left: parent.left
        anchors.bottom: parent.bottom
        anchors.margins: 32 * unit
        width: 240 * unit
        height: 60 * unit
        onClicked: backRequested()
        Keys.onRightPressed: focusPlayRequested()
        Keys.onUpPressed: focusSettings()
    }
    StreamProfileEditor {
        id: customEditor
        settingsProvider: setup.settingsProvider; editable: setup.editable; hostId: setup.hostId; gameId: setup.gameId; unit: setup.unit
        onSaved: { setup.reloadChoices(); setup.error = ""; setup.notice = "Saved for this game." }
    }
    Popup {
        id: picker
        objectName: "play-setup-picker"
        property string heading: ""
        property var choices: []
        property var returnFocus
        property int selectedIndex: 0
        property int currentIndex: 0
        anchors.centerIn: Overlay.overlay
        width: Math.min(640 * unit, (Overlay.overlay ? Overlay.overlay.width : 1280) - 32)
        height: Math.min(implicitHeight, (Overlay.overlay ? Overlay.overlay.height : 800) - 48)
        padding: 24 * unit
        modal: true
        focus: true
        enter: Transition { }
        exit: Transition { }
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        function choose(control, title, values, index) {
            setup.choiceOpened()
            returnFocus = control
            heading = title
            const options = values.slice()
            if (["resolution", "fps", "bitrateKbps"].indexOf(control.field) >= 0)
                options.push({ label: "Custom…", detail: "Type an exact value with the numeric pad.", customField: control.field })
            choices = options
            selectedIndex = index
            currentIndex = Math.max(0, Math.min(index, choices.length - 1))
            selectedIndex = currentIndex
            open()
        }
        function focusChoice(index) {
            currentIndex = Math.max(0, Math.min(index, choices.length - 1))
            choiceButtons.itemAt(currentIndex).forceActiveFocus()
        }
        // Reusing the delegates can briefly report the previously focused row.
        // Reopen from the saved choice, never that transient focus notification.
        onOpened: focusChoice(selectedIndex)
        onClosed: if (returnFocus) returnFocus.forceActiveFocus()
        background: Rectangle { color: NovaTheme.panel; radius: 12 * unit; border.color: NovaTheme.divider }
        contentItem: NovaScrollColumn {
            spacing: 14 * unit
            Copy { text: picker.heading; font.pixelSize: 28 * unit * NovaTheme.fontScale; font.bold: true }
            Repeater {
                id: choiceButtons
                model: picker.choices
                Action {
                    required property var modelData
                    required property int index
                    objectName: "play-setup-choice-" + index
                    Layout.fillWidth: true
                    text: modelData.label + (index === picker.selectedIndex ? "  ·  Current" : "")
                    Accessible.checkable: true
                    Accessible.checked: index === picker.selectedIndex
                    onActiveFocusChanged: if (activeFocus) picker.currentIndex = index
                    opacity: modelData.available === false && !activeFocus ? 0.55 : 1
                    onClicked: {
                        if (modelData.available === false) return
                        if (modelData.customField !== undefined) {
                            customEditor.field = modelData.customField; customEditor.returnFocus = picker.returnFocus
                            picker.close(); customEditor.open(); return
                        }
                        if (modelData.steamMode !== undefined) {
                            if (gameTools.setSteamMode(modelData.steamMode)) picker.close()
                            return
                        }
                        const values = {}
                        for (const key of ["width", "height", "fps", "bitrateKbps", "faceButtonLayout", "launchMode", "videoCodec", "profilePreference", "encoderBackend"])
                            if (modelData[key] !== undefined) values[key] = modelData[key]
                        if (save(values)) picker.close()
                    }
                    Keys.onUpPressed: picker.focusChoice(index - 1)
                    Keys.onDownPressed: index + 1 < picker.choices.length ? picker.focusChoice(index + 1) : choiceReset.visible ? choiceReset.forceActiveFocus() : pickerBack.forceActiveFocus()
                }
            }
            Copy {
                objectName: "play-setup-choice-description"
                Layout.fillWidth: true
                Layout.minimumHeight: implicitHeight
                Layout.preferredHeight: Math.max(60 * unit, implicitHeight)
                elide: Text.ElideNone
                text: (picker.choices[picker.currentIndex] || {}).detail || ""
                color: NovaTheme.secondary
                font.pixelSize: 18 * unit * NovaTheme.fontScale
            }
            Action {
                id: choiceReset
                objectName: "play-setup-choice-reset"
                visible: !!picker.returnFocus && picker.returnFocus !== steamLaunch
                Layout.fillWidth: true
                text: "Use default for this choice"
                Accessible.description: picker.returnFocus ? picker.returnFocus.defaultExplanation : ""
                onClicked: if (picker.returnFocus && resetChoice(picker.returnFocus.field)) picker.close()
                Keys.onUpPressed: picker.focusChoice(picker.choices.length - 1)
                Keys.onDownPressed: pickerBack.forceActiveFocus()
            }
            Copy {
                Layout.fillWidth: true
                visible: choiceReset.activeFocus
                text: picker.returnFocus ? picker.returnFocus.defaultExplanation : ""
                color: NovaTheme.secondary
                font.pixelSize: 15 * unit * NovaTheme.fontScale
            }
            Action {
                id: pickerBack
                objectName: "play-setup-choice-back"
                Layout.fillWidth: true
                text: "Back"
                onClicked: picker.close()
                Keys.onUpPressed: choiceReset.visible ? choiceReset.forceActiveFocus() : picker.focusChoice(picker.choices.length - 1)
            }
        }
    }
}

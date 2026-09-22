import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCore
import "LibraryQuery.js" as LibraryQuery
import "LibraryLayout.js" as LibraryLayout

// Android's library flow: host-first chrome, 2:3 posters, explicit details,
// and one focus owner. This surface never receives host transport credentials.
FocusScope {
    id: browser
    required property var games
    required property var host
    required property var refreshState
    property var libraryController: null
    property var hostSettingsController: null
    required property var settingsProvider
    property var hostPower: null
    property var gamepad: null
    property string settingsError: ""
    property bool sessionBusy: false
    property var visibleGames: []
    property var gameShortcuts: null
    property var gameTools: null
    property var selectedGame: ({ id: "game-empty-state", title: "" })
    property string selectedId: ""
    property bool detailOpen: false
    property bool detailUsesLogo: false
    property var logoPlacement: ({scale: 1, x: 0.5, y: 0.5})
    function reloadLogoPlacement() {
        if (settingsProvider && host) logoPlacement = settingsProvider.logoPlacement(host.id, selectedGame.id || "", selectedGame.logoTransform || {})
    }
    onSelectedGameChanged: reloadLogoPlacement()
    onHostChanged: reloadLogoPlacement()
    Connections {
        target: browser.settingsProvider
        function onLogoPlacementChanged() { browser.reloadLogoPlacement() }
    }
    readonly property string lastPlayedText: selectedGame.lastLaunched > 0
        ? "Last played " + Qt.formatDate(new Date(selectedGame.lastLaunched * 1000), "MMM d, yyyy") : ""
    readonly property var gameTime: selectedGame.gameTime || ({})
    readonly property var estimates: [
        { label: "Main story", seconds: gameTime.mainSeconds },
        { label: "Main + extras", seconds: gameTime.extrasSeconds },
        { label: "Completionist", seconds: gameTime.completionistSeconds }
    ].filter(entry => entry.seconds !== undefined && entry.seconds > 0)
    readonly property real longestEstimate: Math.max(0, ...estimates.map(entry => entry.seconds))
    function duration(seconds) {
        if (seconds === undefined) return ""
        if (seconds === 0) return "0 min"
        if (seconds < 60) return "Less than 1 min"
        const hours = Math.floor(seconds / 3600), minutes = Math.floor(seconds % 3600 / 60)
        return hours ? hours.toLocaleString(Qt.locale(), 'f', 0) + " h" + (minutes ? " " + minutes + " min" : "") : minutes + " min"
    }
    property bool applyingSnapshot: false
    property var savedView: ({})
    readonly property real unit: Math.max(0.85, Math.min(1.15, width / 1280))
    readonly property bool blocked: refreshState.busy && !refreshState.automatic
    readonly property bool launchEnabled: refreshState.destinationPlayable !== false && !refreshState.busy && !refreshState.failed && !sessionBusy
        && selectedId.length > 0 && selectedId !== "game-empty-state"
    readonly property bool browsing: grid.activeFocus || (grid.currentItem && grid.currentItem.activeFocus)
        || emptyState.activeFocus
    readonly property bool interactionPaused: detailOpen || destinations.opened || options.opened || systemMenu.opened
        || sourcePicker.opened || morePicker.opened || sortPicker.opened || faceDefaultPicker.opened
        || settingsHub.opened || polarisSync.opened || audioSettings.opened || rumbleSettings.opened || appearanceSettings.opened || powerSheet.opened || !browsing
    readonly property string layoutMode: preferences.layoutMode
    readonly property bool stageMode: layoutMode === "stage"
    signal selected(var game)
    signal chooseHost()
    signal refreshRequested()
    signal managePcs()
    signal playRequested(var game)

    Settings {
        id: preferences
        category: "Library"
        property string layoutMode: "grid"
        property string sortMode: "library"
        property string filterPrimary: "all"
        property string filterValue: ""
        property string filterHost: ""
    }
    readonly property bool constrained: search.text.trim().length > 0 || preferences.filterPrimary !== "all"
    readonly property var filterButtons: [allFilter, recentFilter, sourceFilter, hdrFilter, moreFilter]
    function focusFilter() {
        const primary = preferences.filterPrimary
        const index = primary === "recent" ? 1 : primary === "source" ? 2 : primary === "hdr" ? 3
            : primary === "category" || primary === "genre" ? 4 : 0
        filterButtons[index].forceActiveFocus()
    }
    function setFilter(primary, value) {
        const normalized = LibraryQuery.normalizedFilter(primary, value)
        preferences.filterPrimary = normalized.primary
        preferences.filterValue = normalized.value
        preferences.filterHost = host.id
        rebuild(selectedId, 0)
    }
    function clearConstraints() {
        search.clear()
        setFilter("all", "")
    }

    function rebuild(preferred, fallback) {
        const items = LibraryQuery.select(games, search.text, preferences.filterPrimary,
            preferences.filterValue, preferences.sortMode)
        visibleGames = items
        let index = items.findIndex(game => game.id === preferred)
        if (index < 0) index = Math.max(0, Math.min(fallback || 0, items.length - 1))
        selectIndex(index)
    }
    function selectIndex(index) {
        grid.currentIndex = visibleGames.length ? index : -1
        selectedGame = visibleGames.length ? visibleGames[index] : { id: "game-empty-state", title: "" }
        selectedId = selectedGame.id
        selected(selectedGame)
    }
    function focusGame() {
        if (detailOpen) { playButton.enabled ? playButton.forceActiveFocus() : detailBack.forceActiveFocus(); return }
        if (!visibleGames.length) { emptyState.forceActiveFocus(); return }
        grid.positionViewAtIndex(grid.currentIndex, GridView.Contain)
        grid.forceActiveFocus()
        if (grid.currentItem) grid.currentItem.forceActiveFocus()
    }
    function move(index, key) {
        if (stageMode) {
            if (key === Qt.Key_Up) { stageReview.forceActiveFocus(); return }
            if (key === Qt.Key_Down) return
            const next = index + (key === Qt.Key_Left ? -1 : 1)
            if (next >= 0 && next < visibleGames.length) { selectIndex(next); focusGame() }
            return
        }
        const columns = grid.layoutColumns
        if (key === Qt.Key_Left && index % columns === 0) { hostButton.forceActiveFocus(); return }
        if (key === Qt.Key_Up && index < columns) { focusFilter(); return }
        let next = index + (key === Qt.Key_Left ? -1 : key === Qt.Key_Right ? 1 : key === Qt.Key_Up ? -columns : columns)
        if (next < 0 || next >= visibleGames.length) return
        selectIndex(next)
        focusGame()
    }
    GameShortcut {
        id: shortcutSheet
        controller: browser.gameShortcuts
        game: browser.selectedGame
        onClosed: Qt.callLater(shortcutButton.forceActiveFocus)
    }
    ArtworkStudio {
        id: artworkStudio
        controller: browser.gameTools
        settingsProvider: browser.settingsProvider
        hostId: browser.host.id || ""
        game: browser.selectedGame
        onClosed: Qt.callLater(artworkButton.forceActiveFocus)
    }
    SpaceArtwork {
        id: spaceArtwork
        controller: browser.gameTools; settingsProvider: browser.settingsProvider
        hostId: browser.host.id; game: browser.selectedGame
        onClosed: Qt.callLater(artworkButton.forceActiveFocus)
    }
    function openGameLink(id) {
        clearConstraints()
        const index = visibleGames.findIndex(game => game.id === id)
        if (index < 0) return false
        openDetails(index)
        playRequested(selectedGame)
        return true
    }
    function openDetails(index) {
        if (blocked || !visibleGames.length) return
        selectIndex(index)
        // Keep the title stable for this visit. A logo arriving after the
        // overview opened is ready for the next visit, without shifting text.
        detailUsesLogo = detailLogo.status === Image.Ready && detailLogo.source.toString() === (selectedGame.logo || "")
        detailViewport.contentY = 0
        detailOpen = true
        Qt.callLater(() => playButton.enabled ? playButton.forceActiveFocus() : detailBack.forceActiveFocus())
    }
    function back() {
        if (settingsHub.opened) { settingsHub.back(); return }
        if (shortcutSheet.opened) { shortcutSheet.close(); return }
        if (artworkStudio.opened) { artworkStudio.leave(); return }
        if (spaceArtwork.opened) { spaceArtwork.leave(); return }
        if (destinations.opened) { destinations.close(); return }
        if (powerSheet.opened) { powerSheet.requestClose(); return }
        for (const picker of [sortPicker, sourcePicker, morePicker, faceDefaultPicker])
            if (picker.opened) { picker.close(); return }
        if (options.opened) { options.close(); return }
        if (polarisSync.opened) { polarisSync.close(); return }
        if (systemMenu.opened) { systemMenu.close(); return }
        if (detailOpen) { detailOpen = false; Qt.callLater(focusGame); return }
        if (search.activeFocus) { search.clear(); focusGame(); return }
        if (constrained) clearConstraints()
        focusGame()
    }
    function prepare(automatic) {
        applyingSnapshot = true
        savedView = { game: selectedId, index: grid.currentIndex, scroll: grid.contentY, scrollX: grid.contentX,
            automatic: automatic, focus: Window.window.activeFocusItem ? Window.window.activeFocusItem.objectName : "" }
    }
    function apply() {
        const saved = savedView
        if (!saved.automatic && host.id !== previousHostId) search.clear()
        const sameHost = host.id === previousHostId && (refreshState.destinationId || "desktop") === previousDestinationId
        if (!sameHost) {
            search.clear()
            preferences.filterPrimary = "all"
            preferences.filterValue = ""
            preferences.filterHost = host.id
        }
        previousHostId = host.id
        previousDestinationId = refreshState.destinationId || "desktop"
        rebuild(sameHost ? saved.game : "", sameHost ? saved.index : 0)
        if (!visibleGames.length || !sameHost || selectedId !== saved.game) detailOpen = false
        applyingSnapshot = false
        Qt.callLater(function() {
            grid.contentY = Math.max(0, Math.min(saved.scroll || 0, Math.max(0, grid.contentHeight - grid.height)))
            grid.contentX = Math.max(0, Math.min(saved.scrollX || 0, Math.max(0, grid.contentWidth - grid.width)))
            // Background completion must not steal focus from a menu, details,
            // or editing that began while the request was already in flight.
            if (destinations.opened || detailOpen || options.opened || systemMenu.opened || settingsHub.opened || sourcePicker.opened || morePicker.opened) return
            if (saved.automatic) {
                for (const control of [search, hostButton, destinationButton, optionsButton, systemButton, settingsButton, stageReview].concat(filterButtons))
                    if (control.visible && control.enabled && control.objectName === saved.focus) { control.forceActiveFocus(); return }
            }
            focusGame()
        })
    }
    property string previousHostId: ""
    property string previousDestinationId: ""
    function state() {
        return { game: selectedId, title: selectedGame.title, visibleGames: visibleGames.map(game => game.id),
            query: search.text, layout: preferences.layoutMode, sort: preferences.sortMode,
            filter: preferences.filterPrimary, filterValue: preferences.filterValue,
            filterChoicesOpen: sourcePicker.opened || morePicker.opened, sortChoicesOpen: sortPicker.opened,
            metadata: { source: selectedGame.source || "", category: selectedGame.category || "",
                genres: selectedGame.genres || [], hdrSupported: selectedGame.hdrSupported || false,
                lastLaunched: selectedGame.lastLaunched || 0, labels: selectedGame.sourceRuntimeLabel || "" },
            destination: destinations.observation(), destinationName: refreshState.destinationName || "Desktop",
            artworkOpen: artworkStudio.opened || spaceArtwork.opened, artworkStudioState: artworkStudio.studioState, spaceArtwork: spaceArtwork.state(), shortcutOpen: shortcutSheet.opened, detailOpen: detailOpen, optionsOpen: options.opened, systemOpen: systemMenu.opened,
            overview: { usesLogo: detailUsesLogo, played: playedValue.text,
                estimates: estimates.map(entry => entry.label + " " + duration(entry.seconds)),
                playSource: gameTime.playSource || "", matchedName: gameTime.matchedName || "",
                titleVisible: detailTitle.visible, contentFits: detailBody.implicitHeight <= detailViewport.height,
                scroll: detailViewport.contentY, scrollMaximum: Math.max(0, detailViewport.contentHeight - detailViewport.height),
                playY: playButton.mapToItem(browser, 0, 0).y, playHeight: playButton.height },
            defaultFaceButtonLayout: settingsProvider.defaultFaceButtonLayout, faceDefaultsOpen: faceDefaultPicker.opened,
            settingsHub: settingsHub.state(),
            audio: audioSettings.state(),
            rumble: rumbleSettings.state(),
            hostPowerUi: powerSheet.interactionState(), hostPowerOpen: powerSheet.opened, hostPower: hostPower ? hostPower.state : ({}), appearanceOpen: appearanceSettings.opened, polarisSync: polarisSync.state(), theme: NovaTheme.themeId, fontScale: NovaTheme.fontScale,
            cards: visibleGames.map((game, index) => {
                const tile = grid.itemAtIndex(index)
                if (!tile) return null
                const center = tile.mapToItem(browser, tile.width / 2, tile.height / 2)
                const viewport = libraryViewport.mapToItem(browser, 0, 0)
                return center.x > viewport.x && center.x < viewport.x + libraryViewport.width
                    && center.y > viewport.y && center.y < viewport.y + libraryViewport.height
                    ? { id: game.id, x: center.x, y: center.y } : null
            }).filter(card => card !== null),
            focusOutlineVisible: grid.currentItem ? grid.currentItem.outlineIsVisible() : false,
            systemCenter: { x: systemButton.mapToItem(browser,systemButton.width/2,systemButton.height/2).x,
                y: systemButton.mapToItem(browser,systemButton.width/2,systemButton.height/2).y },
            settingsCenter: { x: settingsButton.mapToItem(browser,settingsButton.width/2,settingsButton.height/2).x,
                y: settingsButton.mapToItem(browser,settingsButton.width/2,settingsButton.height/2).y },
            stageReviewCenter: { x: stageReview.mapToItem(browser, stageReview.width / 2, 0).x,
                y: stageReview.mapToItem(browser, 0, stageReview.height / 2).y },
            columns: grid.layoutColumns, rowsVisible: Math.floor(grid.height / grid.cellHeight),
            lastPlayedLabel: lastPlayedText, launchEnabled: launchEnabled, scroll: grid.contentY,
            launchPolicy: selectedGame.launchPolicy || { known: false, hostDefault: "", allowed: [] },
            streamCapabilities: selectedGame.streamCapabilities || {},
            displayPlanner: selectedGame.displayPlanner || {},
            scrollX: grid.contentX, stageTitle: stageTitle.text, stageVisible: stageHero.visible,
            artwork: { key: selectedGame.artworkKey || "", hero: selectedGame.hero || "", logo: selectedGame.logo || "", logoPlacement: logoPlacement,
                poster: selectedGame.poster || "", heroReady: cinematicHero.status === Image.Ready,
                logoReady: detailLogo.status === Image.Ready, posterReady: grid.currentItem ? grid.currentItem.posterReady : false,
                iconReady: stageIcon.status === Image.Ready },
            selectionVisible: grid.currentItem ? grid.currentItem.x >= grid.contentX - 1
                && grid.currentItem.x + grid.currentItem.width <= grid.contentX + grid.width + 1
                && grid.currentItem.y >= grid.contentY - 1
                && grid.currentItem.y + grid.currentItem.height <= grid.contentY + grid.height + 1 : false }
    }
    Component.onCompleted: {
        if (!visible) return
        previousHostId = host.id
        previousDestinationId = refreshState.destinationId || "desktop"
        if (!["grid", "compact", "stage"].includes(preferences.layoutMode)) preferences.layoutMode = "grid"
        if (!LibraryQuery.sortChoices.some(choice => choice.id === preferences.sortMode)) preferences.sortMode = "library"
        if (preferences.filterHost !== host.id) {
            preferences.filterPrimary = "all"
            preferences.filterValue = ""
        }
        const normalized = LibraryQuery.normalizedFilter(preferences.filterPrimary, preferences.filterValue)
        preferences.filterPrimary = normalized.primary
        preferences.filterValue = normalized.value
        rebuild("", 0)
        Qt.callLater(focusGame)
    }
    Keys.onEscapePressed: back()

    component ChromeButton: NovaButton {
        unit: browser.unit
        quiet: true
        implicitWidth: 128 * unit
        font.pixelSize: 16 * unit * NovaTheme.fontScale
        font.weight: Font.Medium
    }
    component FilterButton: ChromeButton {
        required property int position
        property bool chosen: false
        implicitWidth: Math.max(106 * unit, contentItem.implicitWidth + 32 * unit)
        Accessible.checkable: true
        Accessible.checked: chosen
        Keys.onLeftPressed: filterButtons[Math.max(0, position - 1)].forceActiveFocus()
        Keys.onRightPressed: {
            if (position === 4 && constrained) clearFilters.forceActiveFocus()
            else filterButtons[Math.min(4, position + 1)].forceActiveFocus()
        }
        Keys.onUpPressed: search.forceActiveFocus()
        Keys.onDownPressed: focusGame()
        Rectangle {
            anchors.bottom: parent.bottom
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottomMargin: 5 * unit
            width: 30 * unit; height: 3 * unit; radius: 1
            color: parent.activeFocus ? NovaTheme.focusText : NovaTheme.accent
            visible: parent.chosen
        }
    }
    component CopyLabel: Label {
        color: NovaTheme.text
        textFormat: Text.PlainText
        font.pixelSize: 18 * unit * NovaTheme.fontScale
        elide: Text.ElideRight
    }
    component ChoicePopup: Popup {
        id: chooser
        property string heading: ""
        property var choices: []
        property var returnFocus
        property string selectedValue: ""
        property string selectedPrimary: ""
        signal chosen(var choice)
        anchors.centerIn: Overlay.overlay
        width: 480 * unit
        height: Math.min(browser.height - 96 * unit, (190 + Math.max(1, choices.length) * 58) * unit)
        padding: 24 * unit
        modal: true
        focus: true
        enter: Transition { }
        exit: Transition { }
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        function focusChoice() {
            if (!choices.length) { chooserBack.forceActiveFocus(); return }
            choiceList.positionViewAtIndex(choiceList.currentIndex, ListView.Contain)
            if (choiceList.currentItem) choiceList.currentItem.forceActiveFocus()
        }
        function openChoice(value, primary) {
            selectedValue = value
            selectedPrimary = primary
            const index = choices.findIndex(choice => choice.id === value && (choice.primary || "sort") === primary)
            choiceList.currentIndex = Math.max(0, index)
            open()
        }
        onOpened: Qt.callLater(focusChoice)
        onClosed: if (returnFocus) returnFocus.forceActiveFocus()
        background: Rectangle { color: NovaTheme.panel; radius: 12 * unit; border.color: NovaTheme.divider }
        contentItem: ColumnLayout {
            spacing: 16 * unit
            CopyLabel { text: chooser.heading; font.pixelSize: 28 * unit * NovaTheme.fontScale; font.bold: true }
            CopyLabel {
                Layout.fillWidth: true
                visible: !chooser.choices.length
                text: "This PC hasn't supplied these library details."
                wrapMode: Text.WordWrap
                color: NovaTheme.secondary
            }
            ListView {
                id: choiceList
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumHeight: 0
                clip: true
                spacing: 6 * unit
                model: chooser.choices
                keyNavigationEnabled: false
                boundsBehavior: Flickable.StopAtBounds
                ScrollBar.vertical: ScrollBar { }
                delegate: ChromeButton {
                    required property int index
                    required property var modelData
                    width: choiceList.width
                    objectName: "library-choice-" + (modelData.primary || "sort") + "-" + index
                    text: (modelData.section ? modelData.section + ": " : "") + modelData.title
                    Accessible.checkable: true
                    Accessible.checked: modelData.id === chooser.selectedValue
                        && (modelData.primary || "sort") === chooser.selectedPrimary
                    onActiveFocusChanged: if (activeFocus) choiceList.currentIndex = index
                    onClicked: { chooser.chosen(modelData); chooser.close() }
                    Keys.onDownPressed: {
                        if (index + 1 === chooser.choices.length) chooserBack.forceActiveFocus()
                        else { choiceList.currentIndex = index + 1; chooser.focusChoice() }
                    }
                    Keys.onUpPressed: { choiceList.currentIndex = Math.max(0, index - 1); chooser.focusChoice() }
                    Rectangle {
                        anchors.bottom: parent.bottom
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.bottomMargin: 5 * unit
                        width: 30 * unit; height: 3 * unit
                        color: parent.activeFocus ? NovaTheme.focusText : NovaTheme.accent
                        visible: parent.Accessible.checked
                    }
                }
            }
            ChromeButton {
                id: chooserBack
                objectName: "library-choice-back"
                Layout.fillWidth: true
                text: "Back"
                onClicked: chooser.close()
                Keys.onUpPressed: chooser.focusChoice()
            }
        }
    }
    ChoicePopup {
        id: sourcePicker
        heading: "Sources"
        returnFocus: sourceFilter
        onChosen: choice => setFilter("source", choice.id)
    }
    ChoicePopup {
        id: morePicker
        heading: "Categories & genres"
        returnFocus: moreFilter
        onChosen: choice => setFilter(choice.primary, choice.id)
    }
    ChoicePopup {
        id: faceDefaultPicker
        heading: "Default face buttons"
        returnFocus: faceDefaultButton
        choices: [{ id: "labels", title: "Match labels" }, { id: "positions", title: "Match positions" }]
        onChosen: choice => {
            settingsError = settingsProvider.setDefaultFaceButtonLayout(choice.id)
                ? "" : "Couldn't save the default. Try again."
        }
    }
    ChoicePopup {
        id: sortPicker
        heading: "Sort games"
        choices: LibraryQuery.sortChoices
        returnFocus: sortButton
        onChosen: choice => { preferences.sortMode = choice.id; rebuild(selectedId, grid.currentIndex) }
    }

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0; color: NovaTheme.raised }
            GradientStop { position: 0.6; color: NovaTheme.window }
            GradientStop { position: 1; color: NovaTheme.window }
        }
    }
    // One cinematic backdrop for the library and details. Missing/failed heroes
    // leave the ambient gradient; a poster is never stretched behind the UI.
    Image {
        id: cinematicHero
        anchors.fill: parent
        source: visibleGames.length ? selectedGame.hero || "" : ""
        asynchronous: true
        fillMode: Image.PreserveAspectCrop
        visible: status === Image.Ready
        opacity: detailOpen || stageMode ? 1 : 0.42
    }
    Rectangle {
        anchors.fill: parent
        visible: cinematicHero.status === Image.Ready
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0; color: NovaTheme.alpha(NovaTheme.window, 0.749) }
            GradientStop { position: 0.48; color: NovaTheme.alpha(NovaTheme.window, 0.22) }
            GradientStop { position: 1; color: NovaTheme.alpha(NovaTheme.window, 0.0) }
        }
    }
    Rectangle {
        anchors.fill: parent
        visible: cinematicHero.status === Image.Ready
        gradient: Gradient {
            GradientStop { position: 0; color: NovaTheme.alpha(NovaTheme.window, 0.627) }
            GradientStop { position: 0.18; color: NovaTheme.alpha(NovaTheme.window, 0.18) }
            GradientStop { position: 0.66; color: NovaTheme.alpha(NovaTheme.window, 0.141) }
            GradientStop { position: 1; color: NovaTheme.alpha(NovaTheme.window, 0.78) }
        }
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 24 * unit
        spacing: 8 * unit
        visible: !detailOpen
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: Math.max(56, 60 * unit)
            radius: 10 * unit
            color: "transparent"
            RowLayout {
                anchors.fill: parent
                anchors.margins: 4 * unit
                spacing: 12 * unit
                ChromeButton {
                    id: hostButton
                    objectName: "library-host-button"
                    text: host.displayName || "Choose a PC"
                    Layout.preferredWidth: Math.min(320 * unit, browser.width * 0.36)
                    enabled: !refreshState.busy && !sessionBusy
                    onClicked: chooseHost()
                    Keys.onRightPressed: destinationButton.visible ? destinationButton.forceActiveFocus() : optionsButton.forceActiveFocus()
                    Keys.onDownPressed: focusGame()
                    Keys.onLeftPressed: systemButton.forceActiveFocus()
                }
                ChromeButton {
                    id: destinationButton
                    objectName: "library-destination"
                    visible: libraryController && refreshState.spaces && refreshState.spaces.supported
                    Layout.preferredWidth: Math.min(230 * unit, browser.width * 0.23)
                    text: refreshState.destinationName || "Where to play"
                    Accessible.description: "Choose Desktop or a permitted Space on this PC"
                    enabled: !refreshState.busy && !sessionBusy
                    onClicked: destinations.open()
                    Keys.onLeftPressed: hostButton.forceActiveFocus()
                    Keys.onRightPressed: optionsButton.forceActiveFocus()
                    Keys.onDownPressed: focusGame()
                }
                CopyLabel {
                    Layout.fillWidth: true
                    text: refreshState.busy ? "Updating games…" : ""
                    color: NovaTheme.secondary
                    font.pixelSize: 16 * unit * NovaTheme.fontScale
                }
                ChromeButton {
                    id: optionsButton
                    objectName: "library-options"
                    text: "Options"
                    enabled: !blocked
                    onClicked: options.open()
                    Keys.onLeftPressed: destinationButton.visible ? destinationButton.forceActiveFocus() : hostButton.forceActiveFocus()
                    Keys.onRightPressed: systemButton.forceActiveFocus()
                    Keys.onDownPressed: search.forceActiveFocus()
                }
                ChromeButton {
                    id: systemButton
                    objectName: "library-system"
                    text: "System"
                    enabled: !blocked
                    onClicked: systemMenu.open()
                    Keys.onLeftPressed: optionsButton.forceActiveFocus()
                    Keys.onRightPressed: settingsButton.forceActiveFocus()
                    Keys.onDownPressed: focusGame()
                }
                ChromeButton {
                    id: settingsButton
                    objectName: "library-settings"
                    text: "Settings"
                    enabled: !sessionBusy
                    onClicked: settingsHub.open()
                    Keys.onLeftPressed: systemButton.forceActiveFocus()
                    Keys.onDownPressed: focusGame()
                }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 20 * unit
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4 * unit
                CopyLabel {
                    text: search.text.length ? "Search results"
                        : LibraryQuery.filterTitle(preferences.filterPrimary, preferences.filterValue)
                    Layout.maximumWidth: 640 * unit
                    font.pixelSize: 24 * unit * NovaTheme.fontScale
                    font.weight: Font.DemiBold
                }
                CopyLabel {
                    text: visibleGames.length + (visibleGames.length === 1 ? " game" : " games")
                    color: NovaTheme.secondary
                    font.pixelSize: 15 * unit * NovaTheme.fontScale
                }
            }
            TextField {
                id: search
                objectName: "library-search"
                Layout.preferredWidth: Math.min(300 * unit, browser.width * 0.36)
                Layout.preferredHeight: Math.max(48, 48 * unit * NovaTheme.fontScale)
                placeholderText: "Search games"
                Accessible.name: "Search games"
                color: NovaTheme.text
                placeholderTextColor: NovaTheme.secondary
                font.pixelSize: 16 * unit * NovaTheme.fontScale
                leftPadding: 16 * unit
                selectByMouse: true
                background: Rectangle {
                    radius: 8 * unit
                    color: NovaTheme.alpha(NovaTheme.input, 0.65)
                    border.color: search.activeFocus ? NovaTheme.focus : NovaTheme.divider
                    border.width: search.activeFocus ? 3 * unit : 1
                }
                onTextChanged: if (!applyingSnapshot) rebuild(selectedId, 0)
                onAccepted: focusGame()
                Keys.onDownPressed: focusFilter()
                Keys.onUpPressed: optionsButton.forceActiveFocus()
                Keys.onEscapePressed: { clear(); focusGame() }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 10 * unit
            FilterButton {
                id: allFilter; objectName: "library-filter-all"; position: 0
                text: "All"; chosen: preferences.filterPrimary === "all"
                onClicked: setFilter("all", "")
            }
            FilterButton {
                id: recentFilter; objectName: "library-filter-recent"; position: 1
                text: "Recent"; chosen: preferences.filterPrimary === "recent"
                onClicked: setFilter("recent", "")
            }
            FilterButton {
                id: sourceFilter; objectName: "library-filter-source"; position: 2
                text: "Sources"; chosen: preferences.filterPrimary === "source"
                onClicked: {
                    sourcePicker.choices = LibraryQuery.sources(games)
                    sourcePicker.openChoice(preferences.filterValue, "source")
                }
            }
            FilterButton {
                id: hdrFilter; objectName: "library-filter-hdr"; position: 3
                text: "HDR"; chosen: preferences.filterPrimary === "hdr"
                onClicked: setFilter("hdr", "")
            }
            FilterButton {
                id: moreFilter; objectName: "library-filter-more"; position: 4
                text: "More"; chosen: preferences.filterPrimary === "category" || preferences.filterPrimary === "genre"
                onClicked: {
                    morePicker.choices = LibraryQuery.moreFilters(games)
                    morePicker.openChoice(preferences.filterValue, preferences.filterPrimary)
                }
            }
            Item { Layout.fillWidth: true }
            ChromeButton {
                id: clearFilters
                objectName: "library-clear-filters"
                visible: constrained
                text: "Clear filters"
                onClicked: { clearConstraints(); focusFilter() }
                Keys.onLeftPressed: moreFilter.forceActiveFocus()
                Keys.onDownPressed: focusGame()
                Keys.onUpPressed: search.forceActiveFocus()
            }
        }
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.minimumHeight: 0
            spacing: 12 * unit
            Item {
                id: stageHero
                Layout.fillWidth: true
                Layout.preferredHeight: 146 * unit
                visible: stageMode && visibleGames.length > 0
                ColumnLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 8 * unit
                    spacing: 10 * unit
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12 * unit
                        Image {
                            id: stageIcon
                            Layout.preferredWidth: 40 * unit
                            Layout.preferredHeight: 40 * unit
                            source: stageMode && !detailOpen ? selectedGame.icon || "" : ""
                            asynchronous: true
                            fillMode: Image.PreserveAspectFit
                            visible: status === Image.Ready
                        }
                        CopyLabel {
                            id: stageTitle
                            Layout.fillWidth: true
                            text: selectedGame.title || ""
                            font.pixelSize: 36 * unit * NovaTheme.fontScale
                            font.weight: Font.Bold
                            maximumLineCount: 1
                        }
                    }
                    CopyLabel {
                        Layout.fillWidth: true
                        text: [LibraryQuery.sourceLabel(selectedGame.source || "gamestream"),
                            LibraryQuery.categoryLabel(selectedGame.category),
                            selectedGame.hdrSupported ? "HDR" : "", selectedGame.lastLaunched > 0 ? "Recent" : ""]
                            .filter(value => value).join(" · ").toUpperCase()
                        color: NovaTheme.secondary
                        font.pixelSize: 16 * unit * NovaTheme.fontScale
                    }
                    ChromeButton {
                        id: stageReview
                        objectName: "library-stage-review"
                        text: "Review and launch"
                        Layout.preferredWidth: 240 * unit
                        enabled: !blocked && visibleGames.length > 0
                        onClicked: openDetails(grid.currentIndex)
                        Keys.onUpPressed: focusFilter()
                        Keys.onDownPressed: focusGame()
                        Keys.onLeftPressed: hostButton.forceActiveFocus()
                        Keys.onRightPressed: focusGame()
                    }
                }
            }
            Item {
                id: libraryViewport
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumHeight: 0
                clip: true
                GridView {
                    id: grid
                    objectName: "android-library-grid"
                    anchors.fill: parent
                    anchors.topMargin: 26 * unit
                    anchors.bottomMargin: 4 * unit
                    anchors.leftMargin: stageMode ? 6 * unit : 0
                    anchors.rightMargin: stageMode ? 6 * unit : 0
                    // Leave room for the lifted first row and enlarged Stage edges.
                    clip: false
                    model: visibleGames
                    enabled: !blocked
                    opacity: blocked ? 0.55 : 1
                    flow: stageMode ? GridView.FlowTopToBottom : GridView.FlowLeftToRight
                    readonly property int layoutColumns: LibraryLayout.columns(width, height, preferences.layoutMode, unit)
                    cellWidth: stageMode ? Math.max(80 * unit, (height - 20 * unit) / 1.5 + 24 * unit)
                        : width / layoutColumns
                    cellHeight: stageMode ? height : (cellWidth - 24 * unit) * 1.5 + 20 * unit
                    onWidthChanged: Qt.callLater(() => grid.positionViewAtIndex(grid.currentIndex, GridView.Contain))
                    onHeightChanged: Qt.callLater(() => grid.positionViewAtIndex(grid.currentIndex, GridView.Contain))
                    boundsBehavior: Flickable.StopAtBounds
                    keyNavigationEnabled: false
                    ScrollBar.vertical: ScrollBar { policy: stageMode ? ScrollBar.AlwaysOff : ScrollBar.AsNeeded }
                    ScrollBar.horizontal: ScrollBar { policy: stageMode ? ScrollBar.AsNeeded : ScrollBar.AlwaysOff }
                    delegate: FocusScope {
                        id: tile
                        required property var modelData
                        required property int index
                        readonly property bool posterReady: poster.status === Image.Ready
                        function outlineIsVisible() {
                            const topLeft = gameButton.mapToItem(libraryViewport, -3 * unit, -3 * unit)
                            const bottomRight = gameButton.mapToItem(libraryViewport, gameButton.width + 3 * unit, gameButton.height + 3 * unit)
                            return topLeft.x >= -1 && topLeft.y >= -1
                                && bottomRight.x <= libraryViewport.width + 1 && bottomRight.y <= libraryViewport.height + 1
                        }
                        width: grid.cellWidth
                        height: grid.cellHeight
                        z: gameButton.activeFocus ? 1 : 0
                        Button {
                            id: gameButton
                            focus: true
                            objectName: modelData.id
                            x: 12 * unit
                            y: (gameButton.activeFocus ? -2 : 8) * unit
                            width: parent.width - 24 * unit
                            height: parent.height - 20 * unit
                            scale: activeFocus ? (stageMode ? 1.10 : preferences.layoutMode === "compact" ? 1.06 : 1.08) : 1
                            opacity: activeFocus ? 1 : stageMode ? 0.76 : preferences.layoutMode === "compact" ? 0.82 : 0.84
                            Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
                            Behavior on y { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
                            Behavior on opacity { NumberAnimation { duration: 180 } }
                            padding: 0
                            Accessible.name: modelData.title + ". Open details"
                            onActiveFocusChanged: if (activeFocus) selectIndex(index)
                            onClicked: openDetails(index)
                            Keys.onReturnPressed: event => { if (!event.isAutoRepeat) openDetails(index) }
                            Keys.onEnterPressed: event => { if (!event.isAutoRepeat) openDetails(index) }
                            Keys.onLeftPressed: move(index, Qt.Key_Left)
                            Keys.onRightPressed: move(index, Qt.Key_Right)
                            Keys.onUpPressed: move(index, Qt.Key_Up)
                            Keys.onDownPressed: move(index, Qt.Key_Down)
                            background: Rectangle {
                                radius: 8 * unit
                                color: "transparent"
                            }
                            contentItem: Item {
                                Rectangle {
                                    anchors.fill: parent
                                    radius: 5 * unit
                                    clip: true
                                    gradient: Gradient {
                                        GradientStop { position: 0; color: NovaTheme.raised }
                                        GradientStop { position: 1; color: NovaTheme.window }
                                    }
                                    Image {
                                        id: poster
                                        anchors.fill: parent
                                        source: modelData.poster || ""
                                        asynchronous: true
                                        fillMode: Image.PreserveAspectCrop
                                        sourceSize.width: 400
                                        sourceSize.height: 600
                                        visible: true
                                    }
                                    CopyLabel {
                                        anchors.centerIn: parent
                                        width: parent.width - 30 * unit
                                        visible: poster.status !== Image.Ready
                                        text: modelData.title
                                        font.pixelSize: (stageMode ? 20 : preferences.layoutMode === "compact" ? 16 : 23) * unit * NovaTheme.fontScale
                                        font.weight: Font.DemiBold
                                        horizontalAlignment: Text.AlignHCenter
                                        wrapMode: Text.WordWrap
                                        maximumLineCount: 5
                                    }
                                }
                                Rectangle {
                                    anchors.fill: parent
                                    anchors.margins: -3 * unit
                                    radius: 7 * unit
                                    color: "transparent"
                                    border.width: gameButton.activeFocus ? 3 * unit : 0
                                    border.color: NovaTheme.focus
                                }
                            }
                        }
                    }
                }
                FocusScope {
                    id: emptyState
                    objectName: "game-empty-state"
                    readonly property bool destinationUnavailable: refreshState.spaces && refreshState.spaces.supported
                        && !refreshState.destinationId && !refreshState.failed
                    anchors.fill: parent
                    visible: !visibleGames.length
                    Keys.onLeftPressed: hostButton.forceActiveFocus()
                    Keys.onUpPressed: focusFilter()
                    Keys.onReturnPressed: activate()
                    function activate() {
                        if (destinationUnavailable) destinations.open()
                        else if (constrained) { clearConstraints(); focusGame() }
                        else systemMenu.open()
                    }
                    Rectangle {
                        anchors.fill: parent
                        anchors.margins: 8 * unit
                        color: NovaTheme.alpha(NovaTheme.window, 0.188)
                        radius: 10 * unit
                        border.color: emptyState.activeFocus ? NovaTheme.focus : "transparent"
                        border.width: 3 * unit
                    }
                    Column {
                        anchors.centerIn: parent
                        width: parent.width - 80 * unit
                        spacing: 12 * unit
                        CopyLabel {
                            width: parent.width
                            horizontalAlignment: Text.AlignHCenter
                            text: refreshState.failed ? "Can't load games" : emptyState.destinationUnavailable ? "No destination available"
                                : search.text.length ? "No matching games"
                                : preferences.filterPrimary === "recent" ? "No recent games"
                                : preferences.filterPrimary === "source" ? "No games from this source"
                                : constrained ? "No matching games" : "No games available"
                            font.pixelSize: 30 * unit * NovaTheme.fontScale
                        }
                        CopyLabel {
                            width: parent.width
                            horizontalAlignment: Text.AlignHCenter
                            wrapMode: Text.WordWrap
                            text: refreshState.failed ? refreshState.copy : emptyState.destinationUnavailable ? refreshState.spaces.caption
                                : preferences.filterPrimary === "recent" && !search.text.length
                                ? "This PC hasn't reported any played games yet. Show all games to keep browsing."
                                : constrained ? "Clear search or filters to browse all games on this PC."
                                : "Use System to refresh this PC or manage your saved PCs."
                            color: NovaTheme.secondary
                        }
                        ChromeButton {
                            anchors.horizontalCenter: parent.horizontalCenter
                            text: emptyState.destinationUnavailable ? "Where to play" : constrained ? "Show all games" : "System"
                            onClicked: emptyState.activate()
                        }
                    }
                }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            CopyLabel {
                Layout.fillWidth: true
                text: selectedGame.title || "Your library"
                font.weight: Font.DemiBold
            }
            CopyLabel {
                text: search.activeFocus ? "A  Games     B  Clear search"
                    : (browsing && visibleGames.length ? "A  Details" : "A  Select") + "     B  Back     D-pad  Navigate"
                color: NovaTheme.secondary
                font.pixelSize: 16 * unit * NovaTheme.fontScale
            }
        }
    }

    DestinationPicker {
        id: destinations
        controller: browser.libraryController
        unit: browser.unit
        onClosed: Qt.callLater(() => destinationButton.forceActiveFocus())
    }

    Item {
        anchors.fill: parent
        visible: detailOpen
        ChromeButton {
            id: detailBack
            objectName: "game-detail-back"
            anchors.left: parent.left
            anchors.top: parent.top
            anchors.margins: 28 * unit
            text: "‹  Library"
            onClicked: back()
            Keys.onDownPressed: playButton.forceActiveFocus()
            Keys.onRightPressed: playButton.forceActiveFocus()
        }
        CopyLabel {
            anchors.right: parent.right
            anchors.verticalCenter: detailBack.verticalCenter
            anchors.rightMargin: 40 * unit
            width: Math.max(0, parent.width - detailBack.width - 120 * unit)
            horizontalAlignment: Text.AlignRight
            text: (host.displayName || "") + (refreshState.spaces && refreshState.spaces.supported ? "  ·  " + refreshState.destinationName : "")
            color: NovaTheme.secondary
            font.pixelSize: 14 * unit * NovaTheme.fontScale
        }
        Flickable {
            id: detailViewport
            objectName: "game-detail-reading"
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: detailBack.bottom
            anchors.bottom: detailActions.top
            anchors.leftMargin: 48 * unit
            anchors.rightMargin: 48 * unit
            anchors.topMargin: 24 * unit
            anchors.bottomMargin: 24 * unit
            contentWidth: width
            contentHeight: detailBody.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            flickableDirection: Flickable.VerticalFlick
            ScrollBar.vertical: ScrollBar { }
            onHeightChanged: contentY = Math.max(0, Math.min(contentY, contentHeight - height))
            Keys.onUpPressed: {
                if (contentY <= 0) detailBack.forceActiveFocus()
                else contentY = Math.max(0, contentY - 80 * unit)
            }
            Keys.onDownPressed: {
                if (contentY >= contentHeight - height) playButton.forceActiveFocus()
                else contentY = Math.min(contentHeight - height, contentY + 80 * unit)
            }
            Keys.onEscapePressed: back()
            ColumnLayout {
                id: detailBody
                width: Math.min(parent.width, 900 * unit)
                y: Math.max(0, detailViewport.height - implicitHeight)
                spacing: 16 * unit
                Item {
                    Layout.fillWidth: true
                    Layout.preferredHeight: detailUsesLogo ? 180 * unit : detailTitle.implicitHeight
                    LogoArtwork {
                        id: detailLogo
                        anchors.left: parent.left
                        anchors.top: parent.top
                        width: Math.min(parent.width, 680 * unit)
                        height: 180 * unit
                        // Prefetch the selected logo while browsing. Opening
                        // details decides once whether it or the title is used.
                        source: selectedGame.logo || ""
                        placement: browser.logoPlacement
                        visible: detailUsesLogo && status === Image.Ready
                        description: selectedGame.title || ""
                    }
                    CopyLabel {
                        id: detailTitle
                        width: parent.width
                        visible: !detailUsesLogo || detailLogo.status !== Image.Ready
                        text: selectedGame.title || ""
                        font.pixelSize: 44 * unit * NovaTheme.fontScale
                        font.weight: Font.Bold
                        font.letterSpacing: -1 * unit
                        wrapMode: Text.WordWrap
                        maximumLineCount: 2
                    }
                }
                CopyLabel {
                    Layout.fillWidth: true
                    text: [selectedGame.sourceRuntimeLabel || "GameStream", lastPlayedText].filter(value => value).join("  ·  ")
                    font.pixelSize: 14 * unit * NovaTheme.fontScale
                    color: NovaTheme.secondary
                    wrapMode: Text.WordWrap
                    maximumLineCount: 2
                }
                Rectangle {
                    Layout.preferredWidth: Math.min(detailBody.width, 440 * unit)
                    Layout.preferredHeight: 1
                    gradient: Gradient {
                        orientation: Gradient.Horizontal
                        GradientStop { position: 0; color: NovaTheme.accent }
                        GradientStop { position: 1; color: "transparent" }
                    }
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    visible: gameTime.playedSeconds !== undefined || estimates.length > 0
                    spacing: 12 * unit
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 28 * unit
                        ColumnLayout {
                            visible: gameTime.playedSeconds !== undefined
                            Layout.fillWidth: true
                            spacing: 4 * unit
                            CopyLabel {
                                Layout.fillWidth: true
                                text: "TIME PLAYED" + (gameTime.playSource ? " · " + LibraryQuery.sourceLabel(gameTime.playSource).toUpperCase() : "")
                                font.pixelSize: 12 * unit * NovaTheme.fontScale
                                color: NovaTheme.secondary
                            }
                            CopyLabel {
                                id: playedValue
                                Layout.fillWidth: true
                                text: duration(gameTime.playedSeconds)
                                font.pixelSize: 30 * unit * NovaTheme.fontScale
                                font.weight: Font.DemiBold
                            }
                        }
                        Item { Layout.fillWidth: true }
                    }
                    Rectangle {
                        Layout.preferredWidth: Math.min(detailBody.width, 550 * unit)
                        Layout.preferredHeight: 5 * unit
                        visible: gameTime.playedSeconds !== undefined && longestEstimate > 0
                        radius: height / 2
                        color: NovaTheme.alpha(NovaTheme.text, 0.18)
                        Rectangle {
                            width: parent.width * Math.min(1, (gameTime.playedSeconds || 0) / Math.max(1, longestEstimate))
                            height: parent.height
                            radius: height / 2
                            color: NovaTheme.accent
                        }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 28 * unit
                        Repeater {
                            model: estimates
                            ColumnLayout {
                                required property var modelData
                                Layout.maximumWidth: (detailBody.width - 56 * unit) / Math.max(1, estimates.length)
                                spacing: 4 * unit
                                CopyLabel {
                                    Layout.fillWidth: true
                                    text: modelData.label
                                    font.pixelSize: 13 * unit * NovaTheme.fontScale
                                    color: NovaTheme.secondary
                                }
                                CopyLabel {
                                    Layout.fillWidth: true
                                    text: duration(modelData.seconds)
                                    font.pixelSize: 20 * unit * NovaTheme.fontScale
                                    font.weight: Font.Medium
                                }
                            }
                        }
                        Item { Layout.fillWidth: true }
                    }
                    CopyLabel {
                        Layout.fillWidth: true
                        visible: estimates.length > 0
                        text: "Estimated length" + (gameTime.matchedName && gameTime.matchedName !== selectedGame.title
                            ? " · " + gameTime.matchedName : "")
                        color: NovaTheme.muted
                        font.pixelSize: 12 * unit * NovaTheme.fontScale
                        wrapMode: Text.WordWrap
                        maximumLineCount: 2
                    }
                }
                CopyLabel {
                    Layout.fillWidth: true
                    text: [LibraryQuery.categoryLabel(selectedGame.category)].concat(selectedGame.genres || [])
                        .filter((value, index, all) => value && all.indexOf(value) === index).join(" · ")
                    visible: text.length > 0
                    color: NovaTheme.secondary
                    font.pixelSize: 14 * unit * NovaTheme.fontScale
                    wrapMode: Text.WordWrap
                    maximumLineCount: 2
                }
                CopyLabel {
                    Layout.fillWidth: true
                    text: [selectedGame.installedLabel || "", selectedGame.hdrSupported ? "HDR supported by this game" : ""].filter(value => value).join(" · ")
                    visible: text.length > 0
                    color: NovaTheme.secondary
                    font.pixelSize: 14 * unit * NovaTheme.fontScale
                    wrapMode: Text.WordWrap
                }
            }
        }
        Rectangle {
            anchors.fill: detailViewport
            anchors.margins: -5 * unit
            visible: detailViewport.activeFocus
            color: "transparent"
            radius: 5 * unit
            border.color: NovaTheme.focus
            border.width: 2
        }
        RowLayout {
            id: detailActions
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.leftMargin: 48 * unit
            anchors.rightMargin: 48 * unit
            anchors.bottomMargin: 74 * unit
            spacing: 24 * unit
            ChromeButton {
                id: playButton
                objectName: "game-detail-play"
                text: refreshState.busy ? "Updating…" : refreshState.destinationPlayable === false ? refreshState.destinationPlayLabel : "Play"
                primary: true
                Layout.preferredWidth: 190 * unit
                Layout.preferredHeight: Math.max(52, 56 * unit * NovaTheme.fontScale)
                enabled: launchEnabled
                onClicked: playRequested(selectedGame)
                Keys.onUpPressed: {
                    if (detailViewport.contentHeight > detailViewport.height) {
                        detailViewport.contentY = Math.max(0, detailViewport.contentHeight - detailViewport.height)
                        detailViewport.forceActiveFocus()
                    } else detailBack.forceActiveFocus()
                }
                Keys.onLeftPressed: detailBack.forceActiveFocus()
                Keys.onRightPressed: artworkButton.forceActiveFocus()
            }
            ChromeButton {
                id: artworkButton; objectName: "game-detail-artwork"
                text: "Artwork"
                visible: !!gameTools
                enabled: !sessionBusy && !refreshState.busy
                Layout.preferredHeight: Math.max(52, 56 * unit * NovaTheme.fontScale)
                onClicked: (selectedGame.spaceId || (selectedGame.id || "").startsWith("space.")) ? spaceArtwork.open() : artworkStudio.open()
                Keys.onLeftPressed: playButton.forceActiveFocus()
                Keys.onRightPressed: shortcutButton.forceActiveFocus()
                Keys.onUpPressed: detailBack.forceActiveFocus()
            }
            ChromeButton {
                id: shortcutButton; objectName: "game-detail-shortcut"; text: "Add to Steam"
                visible: !!gameShortcuts; enabled: !sessionBusy && !refreshState.busy
                Layout.preferredHeight: Math.max(52, 56 * unit * NovaTheme.fontScale)
                onClicked: shortcutSheet.open()
                Keys.onLeftPressed: artworkButton.forceActiveFocus()
                Keys.onRightPressed: detailBack.forceActiveFocus()
                Keys.onUpPressed: detailBack.forceActiveFocus()
            }
            CopyLabel {
                Layout.fillWidth: true
                text: "Review your stream settings before playing."
                color: NovaTheme.secondary
                font.pixelSize: 14 * unit * NovaTheme.fontScale
                wrapMode: Text.WordWrap
            }
        }
        RowLayout {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.margins: 32 * unit
            CopyLabel {
                text: "A  Select     B  Library"
                color: NovaTheme.secondary
                font.pixelSize: 14 * unit * NovaTheme.fontScale
            }
            Item { Layout.fillWidth: true }
            CopyLabel {
                text: "POLARIS"
                color: NovaTheme.muted
                font.pixelSize: 11 * unit * NovaTheme.fontScale
                font.letterSpacing: 2 * unit
            }
        }
    }

    SettingsHub {
        id: settingsHub
        settingsProvider: browser.settingsProvider
        hostController: browser.hostSettingsController
        libraryPreferences: preferences
        hostAvailable: !!browser.hostSettingsController && !browser.sessionBusy && !browser.refreshState.busy
        onClosed: settingsButton.forceActiveFocus()
    }
    Connections {
        target: preferences
        function onLayoutModeChanged() {
            grid.contentX = 0; grid.contentY = 0
            Qt.callLater(() => grid.positionViewAtIndex(grid.currentIndex, GridView.Contain))
        }
    }
    onSessionBusyChanged: if (sessionBusy) settingsHub.close()
    HostPower {
        id: powerSheet
        controller: browser.hostPower
        gamepad: browser.gamepad
        unit: browser.unit
        onClosed: { if (browser.hostPower) browser.hostPower.cancel(); sleepButton.forceActiveFocus() }
    }
    AppearanceSettings {
        id: appearanceSettings
        unit: browser.unit
        onClosed: appearanceButton.forceActiveFocus()
    }
    Popup {
        id: options
        objectName: "library-options-popup"
        anchors.centerIn: Overlay.overlay
        width: Math.min(520 * unit, browser.width - 32)
        height: Math.min(implicitHeight, browser.height - 48)
        padding: 28 * unit
        modal: true
        focus: true
        enter: Transition { }
        exit: Transition { }
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        onAboutToShow: layoutButton.forceActiveFocus()
        onOpened: layoutButton.forceActiveFocus()
        onClosed: optionsButton.forceActiveFocus()
        background: Rectangle { color: NovaTheme.panel; radius: 12 * unit; border.color: NovaTheme.divider }
        contentItem: NovaScrollColumn {
            spacing: 16 * unit
            CopyLabel { text: "Library options"; font.pixelSize: 28 * unit * NovaTheme.fontScale; font.bold: true }
            CopyLabel { text: "Display"; color: NovaTheme.secondary }
            ChromeButton {
                id: layoutButton
                objectName: "library-layout-option"
                Layout.fillWidth: true
                text: stageMode ? "Layout: Stage" : preferences.layoutMode === "compact" ? "Layout: Compact" : "Layout: Grid"
                onClicked: {
                    preferences.layoutMode = preferences.layoutMode === "grid" ? "compact"
                        : preferences.layoutMode === "compact" ? "stage" : "grid"
                    grid.contentX = 0
                    grid.contentY = 0
                    grid.positionViewAtIndex(grid.currentIndex, GridView.Contain)
                }
                Keys.onDownPressed: sortButton.forceActiveFocus()
            }
            ChromeButton {
                id: sortButton
                objectName: "library-sort-option"
                Layout.fillWidth: true
                text: "Sort: " + (LibraryQuery.sortChoices.find(choice => choice.id === preferences.sortMode) || LibraryQuery.sortChoices[0]).title
                onClicked: sortPicker.openChoice(preferences.sortMode, "sort")
                Keys.onUpPressed: layoutButton.forceActiveFocus()
                Keys.onDownPressed: optionsDone.forceActiveFocus()
            }
            ChromeButton {
                id: optionsDone
                Layout.fillWidth: true
                text: "Done"
                onClicked: { options.close(); focusGame() }
                Keys.onUpPressed: sortButton.forceActiveFocus()
            }
        }
    }
    HostDefaults {
        id: polarisSync
        objectName: "library-polaris-sync-view"
        controller: browser.hostSettingsController
        settingsProvider: browser.settingsProvider
        syncView: true
        backLabel: "Back"
        onClosed: if (systemMenu.opened) syncButton.forceActiveFocus()
    }
    AudioSettings {
        id: audioSettings
        settingsProvider: browser.settingsProvider
        unit: browser.unit
        onClosed: audioButton.forceActiveFocus()
    }
    RumbleSettings {
        id: rumbleSettings
        settingsProvider: browser.settingsProvider
        unit: browser.unit
        onClosed: rumbleButton.forceActiveFocus()
    }
    Popup {
        id: systemMenu
        objectName: "library-system-popup"
        anchors.centerIn: Overlay.overlay
        width: Math.min(520 * unit, browser.width - 32)
        height: Math.min(implicitHeight, browser.height - 48)
        padding: 28 * unit
        modal: true
        focus: true
        enter: Transition { }
        exit: Transition { }
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        onAboutToShow: refreshButton.enabled ? refreshButton.forceActiveFocus() : systemDone.forceActiveFocus()
        onOpened: refreshButton.enabled ? refreshButton.forceActiveFocus() : systemDone.forceActiveFocus()
        onClosed: systemButton.forceActiveFocus()
        background: Rectangle { color: NovaTheme.panel; radius: 12 * unit; border.color: NovaTheme.divider }
        contentItem: NovaScrollColumn {
            spacing: 16 * unit
            CopyLabel { text: "System"; font.pixelSize: 28 * unit * NovaTheme.fontScale; font.bold: true }
            CopyLabel { Layout.fillWidth: true; text: host.displayName || "Saved PCs"; color: NovaTheme.secondary }
            ChromeButton {
                id: refreshButton
                objectName: "library-refresh-button"
                Layout.fillWidth: true
                text: refreshState.busy ? "Updating…" : "Refresh games"
                enabled: !refreshState.busy && !sessionBusy
                onClicked: { systemMenu.close(); refreshRequested() }
                Keys.onDownPressed: pcsButton.forceActiveFocus()
            }
            ChromeButton {
                id: pcsButton
                objectName: "manage-pcs"
                Layout.fillWidth: true
                text: "Saved PCs"
                enabled: !refreshState.busy && !sessionBusy
                onClicked: { systemMenu.close(); managePcs() }
                Keys.onUpPressed: refreshButton.forceActiveFocus()
                Keys.onDownPressed: syncButton.enabled ? syncButton.forceActiveFocus() : faceDefaultButton.forceActiveFocus()
            }
            ChromeButton {
                id: syncButton
                objectName: "library-polaris-sync"
                Layout.fillWidth: true
                text: "Polaris Sync"
                enabled: browser.hostSettingsController !== null && !sessionBusy && !refreshState.busy
                onClicked: polarisSync.open()
                Keys.onUpPressed: pcsButton.forceActiveFocus()
                Keys.onDownPressed: faceDefaultButton.forceActiveFocus()
            }
            ChromeButton {
                id: faceDefaultButton
                objectName: "library-face-default"
                Layout.fillWidth: true
                text: "Face buttons: " + (settingsProvider.defaultFaceButtonLayout === "positions" ? "Match positions" : "Match labels")
                onClicked: faceDefaultPicker.openChoice(settingsProvider.defaultFaceButtonLayout, "sort")
                Keys.onUpPressed: syncButton.enabled ? syncButton.forceActiveFocus() : pcsButton.forceActiveFocus()
                Keys.onDownPressed: audioButton.forceActiveFocus()
            }
            CopyLabel {
                Layout.fillWidth: true
                text: settingsError || "Default for games without an override. Match positions swaps A/B and X/Y in games."
                wrapMode: Text.WordWrap
                color: settingsError ? NovaTheme.warning : NovaTheme.secondary
                font.pixelSize: 16 * unit * NovaTheme.fontScale
            }
            ChromeButton {
                id: audioButton
                objectName: "library-audio-settings"
                Layout.fillWidth: true
                text: "Audio"
                onClicked: audioSettings.open()
                Keys.onUpPressed: faceDefaultButton.forceActiveFocus()
                Keys.onDownPressed: rumbleButton.forceActiveFocus()
            }
            ChromeButton {
                id: rumbleButton
                objectName: "library-rumble-settings"
                Layout.fillWidth: true
                text: "Controller rumble"
                onClicked: rumbleSettings.open()
                Keys.onUpPressed: audioButton.forceActiveFocus()
                Keys.onDownPressed: appearanceButton.forceActiveFocus()
            }
            ChromeButton {
                id: appearanceButton
                objectName: "library-appearance"
                Layout.fillWidth: true
                text: "Appearance"
                onClicked: appearanceSettings.open()
                Keys.onUpPressed: rumbleButton.forceActiveFocus()
                Keys.onDownPressed: sleepButton.forceActiveFocus()
            }
            ChromeButton {
                id: sleepButton
                objectName: "library-sleep-host"
                Layout.fillWidth: true
                text: "Sleep Host"
                enabled: browser.hostPower !== null && !sessionBusy && !refreshState.busy
                onClicked: powerSheet.open()
                Keys.onUpPressed: appearanceButton.forceActiveFocus()
                Keys.onDownPressed: systemDone.forceActiveFocus()
            }
            ChromeButton {
                id: systemDone
                Layout.fillWidth: true
                text: "Back to games"
                onClicked: { systemMenu.close(); focusGame() }
                Keys.onUpPressed: sleepButton.forceActiveFocus()
            }
        }
    }
}

package com.papi.nova.ui

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import com.papi.nova.R
import com.papi.nova.api.PolarisArtworkChoice
import com.papi.nova.api.PolarisArtworkMatchCandidate
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaField
import com.papi.nova.ui.panel.NovaFieldKind
import com.papi.nova.ui.panel.NovaMenuHeader
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.NovaProblemBack
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaTone
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fixture data for the visual gate. The strings are realistic and long on purpose (a long game
 * title, a long host name, captions of the length Nova's own run to), so a surface that cuts,
 * ellipsizes or crowds text shows it. Where Nova draws a resource, the fixture draws the same
 * resource, so a run under another locale reads its translations. Nothing here reaches a host.
 */
internal object NovaVisualFixtures {
    const val LongHost = "living-room-gaming-pc.papi.miami"
    const val HostAddress = "10.0.0.232"
    const val LongGame = "Control Ultimate Edition: The Foundation and AWE Expansions"
    const val LongGameTwo = "The Legend of Heroes: Trails through Daybreak II Deluxe Edition"
    const val ShortGame = "Animal Well"
    const val HostUuid = "8d2f5e7a-visual-gate-fixture"

    /** A page an owner draws itself, for surfaces the gate composes from the panel's parts. */
    class OwnerPage(override val key: String, override val title: String) : NovaPage

    // The Command Center.

    fun commandCenterState(context: Context): NovaQuickMenuUiState {
        val status = PolarisSessionStatus(
            state = "streaming",
            streamingActive = true,
            game = LongGame,
            gameUuid = "game-1",
            ownedByClient = true,
            controls = PolarisSessionStatus.ControlsStatus(hostTuningAllowed = true, quitAllowed = true),
            displayMode = PolarisSessionStatus.DisplayModeStatus(effectiveHeadless = true, requested = "headless"),
            syncStatus = PolarisSessionStatus.SyncStatus(available = true, state = "synced"),
            tuning = PolarisSessionStatus.TuningStatus(adaptiveBitrateEnabled = true, aiOptimizerEnabled = true),
            health = PolarisSessionStatus.HealthStatus(grade = "good"),
        )
        return NovaQuickMenuUiState.from(
            context = context,
            status = status,
            apiAvailable = true,
            hostStateUnavailable = false,
            adaptiveSupported = true,
            aiSupported = true,
            adaptiveEnabled = true,
            aiEnabled = true,
            mangoHudEnabled = false,
            stabilityApplied = false,
            advancedExpanded = false,
            profileClearInProgress = false,
            currentGameName = LongGame,
            currentGameUuid = "game-1",
            profilePreference = "auto",
            hudShowing = true,
            hudOpacityPercent = NovaHudPreferences.DEFAULT_OPACITY_PERCENT,
            menuOpacityPercent = NovaMenuPreferences.DEFAULT_OPACITY_PERCENT,
            perfOverlayEnabled = false,
            onscreenControllerEnabled = false,
            keyboardVisible = false,
            mouseModeLabel = context.getString(R.string.nova_quick_menu_direct),
            allowChangeMouseMode = true,
            isOnExternalDisplay = false,
            fallbackBitrateKbps = 80_000,
            fallbackTargetFps = 120.0,
        )
    }

    fun mouseModePage(context: Context) = NovaMouseModeChoices.page(
        title = context.getString(R.string.nova_cc_mouse_mode),
        options = NovaMouseModeChoices.options(
            modeNames = context.resources.getStringArray(R.array.mouse_mode_names).toList(),
            onExternalDisplay = false,
            externalModes = emptySet(),
            localCursorLabel = context.getString(R.string.toggle_local_mouse_cursor),
        ),
        current = 3,
        onChoose = {},
    )

    /** The Keys page as the Command Center builds it, with three imported shortcuts whose names run long. */
    fun keysPage(context: Context): CommandCenterPage.Keys {
        fun rows(keys: List<NovaCommandCenterKey>) = keys.map { key ->
            if (key.key == NovaCommandCenterKeys.CLOSE_APP_KEY) {
                NovaMenuItem.Destructive(
                    key = key.key,
                    label = key.label,
                    confirmLabel = context.getString(R.string.nova_cc_close_app),
                    consequence = context.getString(R.string.nova_cc_alt_f4_consequence),
                    onConfirm = {},
                )
            } else {
                NovaMenuItem.Action(key = key.key, label = key.label, onClick = {})
            }
        }
        val custom = listOf(
            "Toggle Photo Mode and Hide the Whole HUD",
            "Quick Save to the Next Free Slot",
            "Open the Steam Overlay Screenshot Manager",
        ).mapIndexed { index, name -> NovaCommandCenterKey("custom-$index", name, shortArrayOf()) }
        return CommandCenterPage.Keys(
            context.getString(R.string.nova_quick_menu_special_keys),
            listOf(
                CommandCenterSection(null, rows(NovaCommandCenterKeys.defaults(context))),
                CommandCenterSection(context.getString(R.string.nova_cc_custom_keys), rows(custom)),
            ),
        )
    }

    /** More Controls as the Command Center builds it, with a controller that offers mouse emulation. */
    fun moreControlsPage(context: Context): CommandCenterPage.MoreControls {
        fun switch(key: String, label: Int, current: Boolean) = NovaMenuItem.Value(
            key = key,
            label = context.getString(label),
            options = listOf(
                NovaOption(false, context.getString(R.string.nova_cc_off)),
                NovaOption(true, context.getString(R.string.nova_cc_on)),
            ),
            current = current,
            onChange = {},
        )
        val host = CommandCenterSection(
            context.getString(R.string.nova_cc_host_section),
            listOf(
                NovaMenuItem.Action(
                    key = "server-commands",
                    label = context.getString(R.string.game_menu_server_cmd),
                    disabledReason = context.getString(R.string.game_dialog_message_server_cmd_empty),
                    onClick = {},
                ),
                NovaMenuItem.Action(
                    key = "fetch-clipboard",
                    label = context.getString(R.string.nova_cc_fetch_clipboard),
                    caption = context.getString(R.string.nova_cc_fetch_clipboard_caption),
                    onClick = {},
                ),
                NovaMenuItem.Action(
                    key = "task-manager",
                    label = context.getString(R.string.nova_cc_task_manager),
                    caption = context.getString(R.string.nova_cc_task_manager_caption),
                    onClick = {},
                ),
            ),
        )
        val touch = CommandCenterSection(
            context.getString(R.string.nova_cc_touch_section),
            listOf(
                NovaMenuItem.Action(
                    key = "android-keyboard",
                    label = context.getString(R.string.nova_cc_android_keyboard),
                    caption = context.getString(R.string.nova_cc_android_keyboard_caption),
                    onClick = {},
                ),
                switch("zoom", R.string.nova_cc_zoom, false),
                switch("floating-button", R.string.nova_cc_floating_button, true),
                switch("special-keys-layout", R.string.nova_cc_special_keys_layout, false),
                switch("touch-sensitivity", R.string.nova_cc_touch_sensitivity, true),
            ),
        )
        val controller = CommandCenterSection(
            context.getString(R.string.nova_cc_controller_section),
            listOf(switch("controller-mouse-1", R.string.nova_cc_controller_mouse, false)),
        )
        return CommandCenterPage.MoreControls(context.getString(R.string.nova_cc_more_controls), listOf(host, touch, controller))
    }

    // Hosts and apps.

    fun host(): ComputerDetails = ComputerDetails().apply {
        name = LongHost
        state = ComputerDetails.State.ONLINE
        pairState = PairingManager.PairState.PAIRED
        libraryState = ComputerDetails.LibraryState.AVAILABLE
        runningGameId = 42
    }

    val hostActions = object : NovaHostMenuActions {
        override fun wake() = Unit
        override fun sendWakeOnLan() = Unit
        override fun pair() = Unit
        override fun otpPairPage(): NovaPage = formPage()
        override fun scanQr() = Unit
        override fun hostConsolePage(): NovaPage = formPage()
        override fun openLibrary() = Unit
        override fun checkLibrary() = Unit
        override fun watch() = Unit
        override fun resume() = Unit
        override fun endSession() = Unit
        override fun sleep() = Unit
        override fun appList() = Unit
        override fun testNetwork() = Unit
        override fun delete() = Unit
    }

    fun hostMenu(context: Context): NovaCommonPage.Menu {
        val details = host()
        return NovaCommonPage.Menu(
            key = "host",
            title = context.getString(R.string.hosts_panel_host_title),
            items = novaHostMenuItems(context, details, needsPairing = false, sleepOffered = true, actions = hostActions),
            header = novaHostMenuHeader(context, details),
            // As PcView opens it: two to a line on a landscape handheld.
            width = NovaPanelWidth.Grid,
        )
    }

    /** An app's menu from the App list grid: a right-edge panel whose header names a long title. */
    fun appMenu(context: Context) = NovaCommonPage.Menu(
        key = "app",
        // As AppView titles it: the panel says what it is, and the header under it names the game.
        title = context.getString(R.string.hosts_panel_app_title),
        header = NovaMenuHeader(
            title = LongGameTwo,
            status = "Running on $LongHost",
            tone = NovaTone.Active,
            hint = "Resume goes straight back in; Quit Game closes it on the host.",
        ),
        items = listOf(
            NovaMenuItem.Action(key = "resume", label = "Resume Stream", caption = "Back to where the game is, as it was", emphasis = true, onClick = {}),
            NovaMenuItem.Action(
                key = "virtual",
                label = "Start with Virtual Display",
                caption = "The host adds a screen the size of this one for this game only",
                onClick = {},
            ),
            NovaMenuItem.Opens(key = "setup", label = "Play Setup", value = "Private Stream", page = { choicePage() }),
            NovaMenuItem.Value(
                key = "buttons",
                label = "Face Buttons",
                options = listOf(NovaOption("printed", "As Printed"), NovaOption("swapped", "Swapped")),
                current = "printed",
                onChange = {},
            ),
            NovaMenuItem.Action(
                key = "hide",
                label = "Hide Game",
                disabledReason = "A running game stays in the list until it is quit on the host.",
                onClick = {},
            ),
            NovaMenuItem.Destructive(
                key = "quit",
                label = "Quit Game",
                confirmLabel = "Quit Game",
                consequence = "Closes the game on the host; anything not saved is lost.",
                onConfirm = {},
            ),
        ),
    )

    // The common pages, under a menu that pushes them.

    fun streamOptions() = NovaCommonPage.Menu(
        key = "stream-options",
        title = "Stream Options",
        items = listOf(
            NovaMenuItem.Opens(key = "codec", label = "Video Codec", caption = "How the host packs every frame", value = "HEVC", page = { choicePage() }),
            NovaMenuItem.Opens(key = "sources", label = "Sources", value = "4 of 6", page = { multiChoicePage() }),
            NovaMenuItem.Opens(key = "sensitivity", label = "Trackpad Sensitivity", value = "-40%", page = { sliderPage() }),
            NovaMenuItem.Opens(key = "address", label = "Add PC Manually", page = { formPage() }),
        ),
    )

    fun choicePage() = NovaCommonPage.Choice(
        key = "codec",
        title = "Video Codec",
        options = listOf(
            NovaOption("h264", "H.264", caption = "Works with every host and decoder; the most bitrate for the same picture"),
            NovaOption("hevc", "HEVC", caption = "Sharper at the same bitrate, decoded in hardware on this device"),
            NovaOption(
                "av1",
                "AV1",
                caption = "The sharpest at low bitrates",
                disabledReason = "This device has no hardware AV1 decoder, and decoding it in software would drop frames",
            ),
            NovaOption(
                "pyrowave",
                "PyroWave",
                caption = "Almost no encode time on the host, for four to ten times the bitrate of HEVC on a wired or 6 GHz network",
            ),
        ),
        current = "hevc",
        onChoose = {},
    )

    fun multiChoicePage() = NovaCommonPage.MultiChoice(
        key = "sources",
        title = "Sources",
        options = listOf(
            NovaOption("steam", "Steam", caption = "312 games, 41 installed on $LongHost"),
            NovaOption("heroic", "Heroic Games Launcher (Epic Games Store and GOG)", caption = "58 games"),
            NovaOption("lutris", "Lutris", caption = "12 games"),
            NovaOption("emulators", "Emulators (RetroArch and standalone cores)", caption = "1,204 ROMs in 9 systems"),
            NovaOption("desktop", "Desktop Apps", caption = "Anything added by hand in Polaris"),
            NovaOption("xbox", "Xbox App", disabledReason = "The Xbox app runs on Windows hosts only"),
        ),
        selected = setOf("steam", "heroic", "emulators", "desktop"),
        doneLabel = "Show These Sources",
        onDone = {},
    )

    fun confirmPage() = NovaCommonPage.Confirm(
        key = "end-session",
        title = "End Session",
        message = AnnotatedString(
            "$LongGame is still running on $LongHost. Ending the session closes it on the host, and anything not saved is lost.",
        ),
        stayLabel = "Stay",
        actionLabel = "End Session",
        destructive = true,
        onConfirm = {},
    )

    fun noticePage() = NovaCommonPage.Notice(
        key = "update",
        title = "Nova 1.4.14 Beta 1 Is Ready",
        message = "This beta moves every menu, list and confirm into panels that open where you are, adds PyroWave on " +
            "Android, and fixes the stream staying black after $LongHost wakes from sleep. Installing it keeps your hosts, " +
            "pairings and settings.",
        primary = NovaAction("Download and Install") {},
        closeLabel = "Later",
        help = NovaAction("Release Notes") {},
    )

    fun formPage() = NovaCommonPage.Form(
        key = "add-pc",
        title = "Add PC Manually",
        fields = listOf(
            NovaField(
                key = "address",
                label = "Host Name or Address",
                initial = LongHost,
                kind = NovaFieldKind.Url,
                hint = "A name such as $LongHost, or an address such as $HostAddress",
            ),
            NovaField(key = "port", label = "Port", initial = "47989", kind = NovaFieldKind.Number, maxLength = 5),
        ),
        submitLabel = "Add PC",
        warning = "Nova finds hosts on this network by itself; a host on another network needs its address and the port Polaris listens on.",
        onSubmit = { null },
    )

    fun sliderPage() = NovaCommonPage.Slider(
        key = "sensitivity",
        title = "Trackpad Sensitivity",
        value = -40,
        range = -200..200,
        step = 10,
        format = { "$it%" },
        onSave = {},
    )

    fun busyPage() = NovaCommonPage.Busy(
        key = "network-test",
        title = "Testing the Network",
        message = MutableStateFlow("Sending 60 packets to $LongHost and timing every round trip over 5 GHz Wi-Fi"),
        cancel = NovaAction("Cancel") {},
    )

    // State pages.

    fun problemState() = NovaStatePage.Problem(
        key = "connection-lost",
        title = "Connection Lost",
        message = "Nova stopped hearing from $LongHost while $LongGame was streaming. The game is still running there.",
        primary = NovaAction("Reconnect") {},
        back = NovaProblemBack.Close(NovaAction("Back to Library") {}),
        secondary = listOf(NovaAction("Back to Library") {}),
        eyebrow = "Stream Ended",
        detail = "Error -1: the host closed the video port after 10 seconds without an acknowledgement from this device.",
        help = NovaAction("Help") {},
    )

    fun busyState() = NovaStatePage.Busy(
        key = "reconnecting",
        title = "Reconnecting to $LongHost",
        message = MutableStateFlow("Attempt 2 of 5. $LongGame keeps running on the host while Nova finds it again."),
        cancel = NovaAction("Disconnect") {},
    )

    fun codeState() = NovaStatePage.Code(
        key = "pairing",
        title = "Pair with $LongHost",
        code = "4829",
        message = "Type this PIN into Polaris on $LongHost, under Pairing, within 90 seconds.",
        close = NovaAction("Close") {},
    )

    // The library.

    fun optionsUi(context: Context, narrowed: String? = null) = NovaLibraryOptionsUi(
        resultCount = if (narrowed == null) 1_586 else 58,
        searchQuery = "",
        filter = if (narrowed == null) NovaLibraryPrimaryFilter.ALL else NovaLibraryPrimaryFilter.SOURCES,
        filterCaption = context.getString(R.string.nova_library_panel_filter_counts, 1_586, 14, 212),
        narrowedLabel = narrowed,
        sourceValue = narrowed ?: context.getString(R.string.nova_library_filter_all_sources),
        moreValue = context.getString(R.string.nova_library_panel_more_none),
        clearable = narrowed != null,
        sortLabel = "Recently Played",
        layoutMode = NovaLibraryLayoutMode.GRID,
        layoutCaption = "Large posters in rows, for browsing a big library at a glance.",
        showPosterTitles = true,
        artwork = NovaArtworkLibraryUpdateUiState.Idle,
    )

    val optionsActions = NovaLibraryOptionsActions(
        onFilter = {},
        searchPage = { OwnerPage("search", "Search") },
        sourcesPage = { multiChoicePage() },
        morePage = { choicePage() },
        sortPage = { choicePage() },
        onClearFilters = {},
        onLayoutMode = {},
        onPosterTitles = {},
        onRefresh = {},
        onStartArtwork = {},
        onCancelArtwork = {},
        onRetryArtwork = {},
    )

    fun systemUi(context: Context) = NovaLibrarySystemUi(
        hostLabel = context.getString(R.string.nova_system_menu_host_named_format, HostAddress, LongHost),
        status = context.getString(R.string.nova_system_menu_status_polaris_ready),
        ready = true,
        mode = "Private Stream on a Virtual Display",
    )

    fun systemActions(polarisSync: () -> NovaPage) = NovaLibrarySystemActions(
        onSwitchHost = {},
        onSettings = {},
        polarisSyncPage = polarisSync,
        hostConsolePage = {
            NovaCommonPage.Notice(key = "host-console", title = "Host Console", message = "", closeLabel = "Close")
        },
        onHelp = {},
        aboutPage = {
            NovaCommonPage.Notice(key = "about", title = "About Nova", message = "Version 1.4.14", closeLabel = "Close")
        },
        onMatrix = {},
        onSponsor = {},
    )

    /** What the host would say about itself, with every display mode Polaris offers and two it cannot. */
    fun polarisSettings(): PolarisClientSettings {
        fun mode(value: String, label: String, group: String, reason: String = "", unavailable: String = "") =
            PolarisClientSettings.ModeOption(
                value = value,
                label = label,
                available = unavailable.isEmpty(),
                reason = reason,
                group = group,
                unavailableReason = unavailable,
            )
        return PolarisClientSettings(
            revision = "visual-gate",
            desired = PolarisClientSettings.Desired(
                streamDisplayMode = PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY,
                streamDisplayModeLabel = "Virtual Display",
                displayMode = "2560x1440x120",
                virtualDisplayMode = "2560x1440x120",
                virtualDisplayScale = 1.25,
                targetBitrateKbps = 80_000,
                adaptiveBitrateEnabled = true,
            ),
            effective = PolarisClientSettings.Effective(
                streamDisplayMode = PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY,
                streamDisplayModeLabel = "Virtual Display",
                displayMode = "2560x1440x120",
                targetBitrateKbps = 80_000,
                adaptiveBitrateEnabled = true,
                capturePath = "kms",
                captureGpuNative = true,
            ),
            capabilities = PolarisClientSettings.Capabilities(
                modes = listOf(
                    mode(PolarisClientSettings.MODE_HEADLESS_STREAM, "Headless Stream", "private", "The host's own screens stay dark and untouched"),
                    mode(PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY, "Virtual Display", "private", "A screen the size of this device, added for the stream"),
                    mode(PolarisClientSettings.MODE_DESKTOP_DISPLAY, "Mirror Desktop", "host", "What the host's main screen shows"),
                    mode(PolarisClientSettings.MODE_DESKTOP_TAKEOVER, "Primary Display", "host", "The host's main screen takes this device's size"),
                    mode(PolarisClientSettings.MODE_HEADLESS_DONGLE, "Headless Dongle", "host", "A dummy plug stands in for a monitor"),
                    mode(PolarisClientSettings.MODE_GAMESCOPE_STREAM, "Gamescope Session", "private", unavailable = "gamescope is not installed on this host"),
                    mode(PolarisClientSettings.MODE_GPU_NATIVE_TEST, "Windowed Stream", "private", unavailable = "labwc and wlr-randr are not installed"),
                ),
                displayModeOverride = true,
                targetBitrateOverride = true,
                adaptiveBitrateControl = true,
                aiOptimizerControl = true,
            ),
        )
    }

    // Play Setup, as the detail's panel draws it.

    val plan = NovaPlaySetupPlan(
        mode = "Private Stream on a Virtual Display",
        lines = listOf(
            "2560\u00d71440 at 120 FPS \u00b7 80 Mbps \u00b7 PyroWave",
            "Nothing outside this game changes: the host's own screens stay as they are.",
        ),
        facts = listOf(
            NovaPlaySetupFact(key = "Last Session", value = "Smooth, 119 FPS held for 2 hr 14 min", tone = NovaPlaySetupTone.GOOD),
            NovaPlaySetupFact(
                key = "Limited By",
                value = "Host Render",
                detail = "Frames left the GPU late in 6% of the last session; the network and the decoder were clear.",
                tone = NovaPlaySetupTone.WARN,
            ),
            NovaPlaySetupFact(key = "Asked / Granted", value = "2560\u00d71440 at 120", detail = "Granted: 2560\u00d71440 at 120 \u00b7 Held by History Safe Profile"),
            NovaPlaySetupFact(key = "Host Default", value = "Virtual Display", detail = "This game follows the host's Default Display."),
        ),
    )

    /**
     * Resolution as the planner offers it to a 1920x1080 handheld streaming PyroWave: the device's
     * own size recommended and current, two larger ones (the second past the bitrate), and one too
     * big to decode, greyed with its reason.
     */
    private fun resolutions(context: Context): List<NovaPlaySetupOption> {
        fun size(width: Int, height: Int) = "$width\u00d7$height"
        fun preview(size: String, limit: String = "") = NovaPlaySetupPreview(NovaPlaySetupPreviewPart.SIZE, size, limit)
        return listOf(
            NovaPlaySetupOption(
                label = "This Device",
                value = size(1920, 1080),
                consequence = context.getString(R.string.nova_play_setup_resolution_matches_screen),
                current = true,
                recommended = true,
                onSelect = {},
                preview = preview(size(1920, 1080)),
            ),
            NovaPlaySetupOption(
                label = "1.5x",
                value = size(2880, 1620),
                consequence = "Sharper edges, more bandwidth",
                onSelect = {},
                preview = preview(size(2880, 1620)),
            ),
            NovaPlaySetupOption(
                label = "2x",
                value = size(3840, 2160),
                consequence = context.getString(R.string.nova_play_setup_resolution_pyrowave_need, 800),
                warning = true,
                onSelect = {},
                preview = preview(size(3840, 2160), context.getString(R.string.nova_play_setup_limited_by_bitrate)),
            ),
            NovaPlaySetupOption(
                label = "3x",
                value = size(5760, 3240),
                consequence = context.getString(R.string.nova_play_setup_resolution_cannot_decode),
                enabled = false,
                onSelect = {},
                preview = preview(size(5760, 3240)),
            ),
        )
    }

    private val modes = listOf(
        "Headless", "Virtual Display", "Mirror Desktop", "Primary Display", "KMS Capture", "Private Space", "Desktop",
    ).mapIndexed { index, label ->
        NovaPlaySetupOption(
            label = label,
            consequence = "What $label does to the host's screens, said whole in one sentence of ordinary length.",
            current = index == 1,
            onSelect = {},
        )
    }

    private fun row(row: NovaPlaySetupRow, label: String, caption: String, options: List<NovaPlaySetupOption>, perRow: Int = Int.MAX_VALUE) =
        NovaPlaySetupRowState(
            row = row,
            label = label,
            caption = caption,
            value = options.firstOrNull { it.current }?.label.orEmpty(),
            options = options,
        )

    /**
     * This Game's rows as the detail builds them for a Steam game on a host with Spaces and a mode
     * catalog: where it opens and Where It Runs (both open its page; the root draws only Where It
     * Runs), Resolution, the Frame Rate strip and Video Codec set for this game, Face Buttons,
     * Launch Preset and Steam Launch.
     */
    fun gameRows(context: Context): List<NovaPlaySetupRowState> {
        val where = context.getString(R.string.nova_game_detail_where_it_runs)
        val standard = context.getString(R.string.nova_play_setup_codec_standard_detail)
        val pyroWave = "PyroWave (Experimental)"
        val direct = context.getString(R.string.nova_steam_launch_direct)
        return listOf(
            NovaPlaySetupRowState(
                row = NovaPlaySetupRow.PLAY_IN,
                label = where,
                caption = "",
                value = places.first { it.current }.label,
                options = places,
                opensPage = true,
            ),
            NovaPlaySetupRowState(
                row = NovaPlaySetupRow.WHERE_IT_RUNS,
                label = where,
                caption = context.getString(R.string.nova_play_setup_where_caption),
                value = context.getString(R.string.nova_play_setup_fact_host_default),
                options = emptyList(),
                opensPage = true,
            ),
            NovaPlaySetupRowState(
                row = NovaPlaySetupRow.RESOLUTION,
                label = context.getString(R.string.nova_play_setup_resolution),
                caption = context.getString(R.string.nova_play_setup_resolution_caption) + " \u00b7 1920\u00d71080",
                value = "This Device",
                options = resolutions(context),
                opensPage = true,
            ),
            NovaPlaySetupRowState(
                row = NovaPlaySetupRow.FRAME_RATE,
                label = context.getString(R.string.nova_play_setup_frame_rate),
                caption = context.getString(R.string.nova_play_setup_applies_at_launch),
                value = context.getString(R.string.nova_play_setup_frame_rate_fps_format, 120),
                options = listOf(
                    NovaPlaySetupOption(
                        label = context.getString(R.string.nova_play_setup_frame_rate_auto),
                        consequence = context.getString(R.string.nova_play_setup_frame_rate_auto_consequence),
                        onSelect = {},
                    ),
                ) + listOf(30, 60, 90, 120).map { fps ->
                    NovaPlaySetupOption(
                        label = context.getString(R.string.nova_play_setup_frame_rate_fps_format, fps),
                        consequence = "",
                        current = fps == 120,
                        onSelect = {},
                        short = fps.toString(),
                    )
                },
                overridden = true,
                ordered = true,
                unit = context.getString(R.string.nova_play_setup_fps_unit),
            ),
            NovaPlaySetupRowState(
                row = NovaPlaySetupRow.VIDEO_CODEC,
                label = context.getString(R.string.nova_play_setup_video_codec),
                caption = context.getString(R.string.nova_play_setup_codec_caption),
                value = pyroWave,
                options = listOf(
                    NovaPlaySetupOption(
                        context.getString(R.string.nova_play_setup_codec_app_setting),
                        context.getString(R.string.nova_play_setup_codec_inherit_detail),
                        onSelect = {},
                    ),
                ) + listOf("H.264", "HEVC", "AV1").map { NovaPlaySetupOption(it, standard, onSelect = {}) } +
                    NovaPlaySetupOption(pyroWave, context.getString(R.string.nova_play_setup_codec_pyrowave_detail), current = true, onSelect = {}),
                overridden = true,
                opensPage = true,
            ),
            NovaPlaySetupRowState(
                row = NovaPlaySetupRow.FACE_BUTTONS,
                label = context.getString(R.string.nova_play_setup_face_buttons),
                caption = context.getString(R.string.nova_play_setup_face_buttons_app_setting_caption),
                value = context.getString(R.string.nova_play_setup_face_buttons_app_setting),
                options = listOf(
                    R.string.nova_play_setup_face_buttons_app_setting to R.string.nova_play_setup_face_buttons_app_setting_consequence,
                    R.string.nova_play_setup_face_buttons_labels to R.string.nova_play_setup_face_buttons_labels_consequence,
                    R.string.nova_play_setup_face_buttons_positions to R.string.nova_play_setup_face_buttons_positions_consequence,
                ).mapIndexed { index, (label, consequence) ->
                    NovaPlaySetupOption(context.getString(label), context.getString(consequence), current = index == 0, onSelect = {})
                },
            ),
            NovaPlaySetupRowState(
                row = NovaPlaySetupRow.TUNING,
                label = context.getString(R.string.nova_play_setup_tuning),
                caption = context.getString(novaProfilePreferenceConsequenceRes("auto")),
                value = context.getString(AutoQualityProfilePreferences.shortLabelRes("auto")),
                options = AutoQualityProfilePreferences.values().map { value ->
                    NovaPlaySetupOption(
                        label = context.getString(AutoQualityProfilePreferences.shortLabelRes(value)),
                        consequence = context.getString(novaProfilePreferenceConsequenceRes(value)),
                        current = value == "auto",
                        onSelect = {},
                    )
                },
            ),
            NovaPlaySetupRowState(
                row = NovaPlaySetupRow.STEAM_LAUNCH,
                label = context.getString(R.string.nova_steam_launch_detail_label),
                caption = context.getString(novaSteamLaunchConsequenceRes("direct")),
                value = direct,
                options = listOf(
                    NovaPlaySetupOption(direct, context.getString(novaSteamLaunchConsequenceRes("direct")), current = true, onSelect = {}),
                    NovaPlaySetupOption(
                        context.getString(R.string.nova_steam_launch_big_picture),
                        context.getString(novaSteamLaunchConsequenceRes("big-picture")),
                        onSelect = {},
                    ),
                ),
            ),
        )
    }

    /**
     * Where It Runs' modes for this game as the detail builds them: the host catalog with Host
     * default pinned first and current, Virtual Display the host's own pick, Headless Dongle a
     * host default only, and two modes the host cannot offer, each with its reason.
     */
    fun gameModePicker(context: Context): NovaPlaySetupModePickerState {
        val hostPick = PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY
        fun mode(value: String, label: String, group: String, unavailable: String = "") = NovaPolarisModeUiState(
            mode = value,
            label = label,
            selected = value == hostPick,
            selectedDesired = value == hostPick,
            selectedEffective = value == hostPick,
            enabled = unavailable.isEmpty(),
            available = unavailable.isEmpty(),
            reason = "",
            restartRequired = false,
            statusLabel = "",
            group = group,
            unavailableReason = unavailable,
        )
        val modes = listOf(
            mode(PolarisClientSettings.MODE_HEADLESS_STREAM, "Headless Stream", "private"),
            mode(PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY, "Virtual Display", "private"),
            mode(PolarisClientSettings.MODE_GAMESCOPE_STREAM, "Gamescope Session", "private", unavailable = "gamescope is not installed on this host"),
            mode(PolarisClientSettings.MODE_GPU_NATIVE_TEST, "Windowed Stream", "private", unavailable = "labwc and wlr-randr are not installed"),
            mode(PolarisClientSettings.MODE_DESKTOP_DISPLAY, "Mirror Desktop", "host"),
            mode(PolarisClientSettings.MODE_DESKTOP_TAKEOVER, "Primary Display", "host"),
            mode(PolarisClientSettings.MODE_HEADLESS_DONGLE, "Headless Dongle", "host"),
        )
        return buildGameModePickerState(
            modes = modes,
            allowedModes = emptyList(),
            playMode = hostPick,
            hasExplicitOverride = false,
            title = context.getString(R.string.nova_game_detail_where_it_runs),
            hostDefaultLabel = context.getString(R.string.nova_play_setup_host_default_entry_detail, "Virtual Display"),
            hostDefaultOnlyDetail = context.getString(R.string.nova_play_setup_mode_host_default_only),
            plainModeDetails = mapOf(
                PolarisClientSettings.MODE_HEADLESS_STREAM to context.getString(R.string.nova_play_setup_mode_private_detail),
                PolarisClientSettings.MODE_GPU_NATIVE_TEST to context.getString(R.string.nova_play_setup_mode_gpu_detail),
                PolarisClientSettings.MODE_GAMESCOPE_STREAM to context.getString(R.string.nova_play_setup_mode_gamescope_detail),
                PolarisClientSettings.MODE_HOST_VIRTUAL_DISPLAY to context.getString(R.string.nova_play_setup_mode_virtual_detail),
                PolarisClientSettings.MODE_HEADLESS_DONGLE to context.getString(R.string.nova_play_setup_mode_dongle_detail),
                PolarisClientSettings.MODE_DESKTOP_DISPLAY to context.getString(R.string.nova_play_setup_mode_mirror_detail),
                PolarisClientSettings.MODE_DESKTOP_TAKEOVER to context.getString(R.string.nova_play_setup_mode_takeover_detail),
            ),
        )
    }

    val hostRows = listOf(
        row(NovaPlaySetupRow.HOST_DEFAULT_DISPLAY, "Default Display", "Every game without its own choice", modes, perRow = 3),
        row(NovaPlaySetupRow.HOST_SCREEN_SCALE, "Screen Scale", "Matches this device", listOf("100%", "125%", "150%").mapIndexed { i, s ->
            NovaPlaySetupOption(s, "The added screen at $s.", current = i == 0, onSelect = {})
        }),
    )

    val places = listOf(
        NovaPlaySetupOption("Desktop", "Uses the host\u2019s usual games and settings.", current = true, onSelect = {}),
        NovaPlaySetupOption("Living Room Television", "Not installed there yet. Change Space to install it.", onSelect = {}),
        NovaPlaySetupOption("Handheld", "Installed. Opens in its own session.", onSelect = {}),
    )

    // Artwork Studio.

    fun artworkState(): NovaArtworkStudioState {
        val candidate = PolarisArtworkMatchCandidate(
            provider = "steamgriddb",
            providerGameId = "1",
            title = LongGame,
            releaseYear = 2020,
        )
        val choices = (1..7).map { PolarisArtworkChoice(NovaArtworkKinds.POSTER, "token-$it", "", 0L) }
        return NovaArtworkStudioState(
            selectedCandidate = candidate,
            currentMatchTitle = LongGame,
            currentMatchSource = "SteamGridDB",
            activeKind = NovaArtworkKinds.POSTER,
            choicesByKind = mapOf(NovaArtworkKinds.POSTER to choices),
            loadedKinds = setOf(NovaArtworkKinds.POSTER),
            selections = mapOf(NovaArtworkKinds.POSTER to choices[1]),
        )
    }

    // The library hero.

    val hero = NovaLibraryHeroState(
        game = null,
        title = LongGame,
        subtitle = "Running on $LongHost in a Virtual Display",
        caption = "Resume this stream, or end it if the game on the host has stopped answering.",
        eyebrow = "Resume your stream",
        actionLabel = "Resume Stream",
        badges = listOf("HDR", "120 FPS", "Virtual Display", "HEVC Main 10"),
        reason = NovaLibraryHeroReason.ACTIVE_SESSION,
        primaryAction = NovaLibraryHeroPrimaryAction.RESUME,
        supportingLine = "",
        artworkFallbackTitle = LongGame,
        artworkFallbackSubtitle = "Remedy Entertainment",
        secondaryActionLabel = "End Session",
        secondaryAction = NovaLibraryHeroSecondaryAction.END_SESSION,
    )

    // The companion deck.

    fun deckState() = NovaCompanionCommandDeckState.from(
        hud = NovaHudUiState.preview(NovaHudMode.DEBUG),
        sessionState = "streaming",
        displayRole = "Companion",
        unavailableLabel = "Unavailable",
    ).withActionSelections(
        androidKeyboardVisible = true,
        novaKeyboardVisible = false,
        novaHudVisible = true,
        zoomPanEnabled = false,
    )
}

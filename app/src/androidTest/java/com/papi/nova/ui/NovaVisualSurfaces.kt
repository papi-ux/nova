package com.papi.nova.ui

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.preferences.NovaAppVersion
import com.papi.nova.preferences.NovaSettingDefinitions
import com.papi.nova.preferences.NovaSettingValue
import com.papi.nova.preferences.NovaSettingsAvailability
import com.papi.nova.preferences.NovaSettingsContent
import com.papi.nova.preferences.NovaSettingsFeatureFlags
import com.papi.nova.preferences.NovaSettingsUiStateFactory
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaRowTrailing
import com.papi.nova.ui.panel.NovaStepperRow
import com.papi.nova.ui.panel.NovaValueRow
import com.papi.nova.ui.panel.NovaValueStyle
import com.papi.nova.ui.panel.novaScrollEdgeFade
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One surface of the visual gate: its stable [name] (the screenshot's and the results' name),
 * what it [about] shows, how many [panels] it puts on screen at rest, and how the stage [open]s it.
 */
internal class NovaVisualSurface(
    val name: String,
    val about: String,
    val panels: Int,
    val open: NovaVisualStage.() -> Unit,
)

/**
 * Every surface spec 9.4 asks for, in the order the walk visits them: the four containers on
 * their first page, every common page, every state page, the value rows at rest and focused, an
 * armed split, the Settings pane with a list pushed, Play Setup with its Frame Rate strip focused
 * and its Resolution and Where It Runs pages, Polaris Sync as a Wide page,
 * Artwork Studio, the library hero, strip and filter row, the Command Center's Mouse Mode, Keys
 * and More Controls pages, and the companion deck on its own display and on a second one.
 */
internal object NovaVisualSurfaces {
    val all: List<NovaVisualSurface> = listOf(
        NovaVisualSurface("container-command-center", "The Command Center over the stream, on its root page", 1) { commandCenter() },
        NovaVisualSurface("container-library-options", "Library Options at the start edge, on its first page", 1) { libraryOptions() },
        NovaVisualSurface("container-library-system", "System at the end edge, on its first page", 1) { librarySystem() },
        NovaVisualSurface("container-right-edge-host-menu", "The right-edge panel: a host's menu from the Hosts grid", 1) {
            backdrop()
            openPanel(NovaVisualFixtures.hostMenu(activity))
        },
        NovaVisualSurface("page-choice-current", "A Choice page open on its current value, with captions and a disabled option", 1) {
            commonPage { NovaVisualFixtures.choicePage() }
        },
        NovaVisualSurface("page-multichoice", "A MultiChoice page with long labels and a disabled option", 1) {
            commonPage { NovaVisualFixtures.multiChoicePage() }
        },
        NovaVisualSurface("page-menu", "A Menu page: an app's menu from the App list, under a header with a long title", 1) {
            backdrop()
            openPanel(NovaVisualFixtures.appMenu(activity))
        },
        NovaVisualSurface("page-confirm", "The Confirm page, for a caller with no button to split, on Stay", 1) {
            backdrop()
            openPanel(NovaVisualFixtures.confirmPage())
        },
        NovaVisualSurface("page-notice", "A Notice page with a primary action, a close and a help action", 1) {
            backdrop()
            openPanel(NovaVisualFixtures.noticePage())
        },
        NovaVisualSurface("page-form", "A Form page, keyboard hidden, with two fields and a warning", 1) {
            commonPage { NovaVisualFixtures.formPage() }
        },
        NovaVisualSurface("page-slider", "A Slider page for a range that goes below zero", 1) {
            commonPage { NovaVisualFixtures.sliderPage() }
        },
        NovaVisualSurface("page-busy", "A Busy page inside a panel, with Cancel", 1) {
            commonPage { NovaVisualFixtures.busyPage() }
        },
        NovaVisualSurface("state-problem", "A Problem state page with its recovery focused, details and help", 0) {
            backdrop()
            showState(NovaVisualFixtures.problemState())
        },
        NovaVisualSurface("state-busy", "A Busy state page with Cancel", 0) {
            backdrop()
            showState(NovaVisualFixtures.busyState())
        },
        NovaVisualSurface("state-code", "The Code state page for a pairing PIN", 0) {
            backdrop()
            showState(NovaVisualFixtures.codeState())
        },
        NovaVisualSurface("value-rows-at-rest", "Segmented, cycler, switch and stepper rows at rest", 1) { valueRows(focus = null) },
        NovaVisualSurface("value-row-segmented-focused", "The segmented row focused", 1) { valueRows(focus = "Face Buttons") },
        NovaVisualSurface("value-row-cycler-focused", "The cycler row focused", 1) { valueRows(focus = "Frame Rate") },
        NovaVisualSurface("value-row-switch-focused", "The switch row focused", 1) { valueRows(focus = "HDR") },
        NovaVisualSurface("value-row-stepper-focused", "The stepper row focused", 1) { valueRows(focus = "Bitrate") },
        NovaVisualSurface("split-delete-pc-armed", "Delete PC armed in the host menu: Keep focused, the consequence under it", 1) {
            backdrop()
            val menu = NovaVisualFixtures.hostMenu(activity)
            openPanel(menu)
            val delete = menu.items.filterIsInstance<NovaMenuItem.Destructive>().last()
            // Down the menu as a pad goes: Delete PC sits last, past what a lazy list has composed.
            val target = hasText(delete.label) and isFocusable()
            repeat(24) { if (!isFocused(target)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
            check(isFocused(target)) { "a pad could not reach ${delete.label}" }
            press(KeyEvent.KEYCODE_DPAD_CENTER)
        },
        NovaVisualSurface("cc-end-session-armed", "End Session armed in the Command Center header", 1) {
            commandCenter { _, state ->
                focus(hasText(state.value.endAction.label) and isFocusable())
                press(KeyEvent.KEYCODE_DPAD_CENTER)
            }
        },
        NovaVisualSurface("settings-rows", "Settings: the rail, the quick strip and the first category's rows", 0) { settings(pushCodec = false) },
        NovaVisualSurface("settings-list-page", "Settings with the Video Codec list pushed into the pane", 0) { settings(pushCodec = true) },
        NovaVisualSurface("play-setup-game", "Play Setup's root for one game, on its first row", 1) {
            playSetup(NovaVisualFixtures.gameRows(activity))
        },
        NovaVisualSurface("play-setup-frame-rate-focused", "Play Setup's Frame Rate strip focused, set for this game", 1) {
            playSetup(NovaVisualFixtures.gameRows(activity))
            focus(hasText(string(R.string.nova_play_setup_frame_rate)) and isFocusable())
        },
        NovaVisualSurface("play-setup-resolution", "Play Setup's Resolution page, opened with A, on the current size", 1) {
            playSetup(NovaVisualFixtures.gameRows(activity))
            openRow(string(R.string.nova_play_setup_resolution))
        },
        NovaVisualSurface("play-setup-resolution-preview", "The Resolution page with 2x focused, the plan card previewing it", 1) {
            playSetup(NovaVisualFixtures.gameRows(activity))
            openRow(string(R.string.nova_play_setup_resolution))
            focus(hasText("2x") and isFocusable())
        },
        NovaVisualSurface("play-setup-where-it-runs", "Play Setup's Where It Runs page: where it opens, then the modes", 1) {
            playSetup(NovaVisualFixtures.gameRows(activity))
            openRow(string(R.string.nova_game_detail_where_it_runs))
        },
        NovaVisualSurface("play-setup-every-game", "Play Setup for every game on the host", 1) {
            playSetup(NovaVisualFixtures.hostRows, NovaPlaySetupScope.EVERY_GAME)
        },
        NovaVisualSurface("play-setup-plan", "Play Setup's Plan page", 1) { playSetupPlan() },
        NovaVisualSurface("polaris-sync-wide", "Polaris Sync pushed from System as a Wide page", 1) { polarisSync() },
        NovaVisualSurface("artwork-studio", "Artwork Studio with a long match title and seven choices", 0) {
            artworkStudio()
            firstPress()
        },
        NovaVisualSurface("library-hero", "The library home hero with a running session and its badges", 0) {
            libraryHero()
            firstPress()
        },
        NovaVisualSurface("library-strip", "The landscape library strip with a long and a short title", 0) {
            libraryStrip()
            firstPress()
        },
        NovaVisualSurface("library-filter-narrowed", "Library Options with a long source narrowing the grid", 1) {
            libraryOptions(narrowed = "Heroic Games Launcher (Epic Games Store and GOG)")
        },
        NovaVisualSurface("cc-mouse-mode", "The Command Center's Mouse Mode page, open on the current mode", 1) {
            commandCenter { panel, _ -> push(NovaVisualFixtures.mouseModePage(activity), panel) }
        },
        NovaVisualSurface("cc-keys", "The Command Center's Keys page, with imported shortcuts", 1) {
            commandCenter { panel, _ -> push(NovaVisualFixtures.keysPage(activity), panel) }
        },
        NovaVisualSurface("cc-more-controls", "The Command Center's More Controls page", 1) {
            commandCenter { panel, _ -> push(NovaVisualFixtures.moreControlsPage(activity), panel) }
        },
        NovaVisualSurface("companion-deck", "The companion command deck, drawn on this display", 0) { companionDeck() },
        NovaVisualSurface("companion-deck-display2", "The companion command deck on a second display, where there is one", 0) {
            companionDeckOnSecondDisplay()
        },
    )

    val names: List<String> get() = all.map { it.name }

    fun named(name: String): NovaVisualSurface = all.first { it.name == name }
}

// The Command Center, drawn as the stream draws it.

private fun NovaVisualStage.commandCenter(
    then: NovaVisualStage.(NovaPanelState, MutableStateFlow<NovaQuickMenuUiState>) -> Unit = { _, _ -> },
) {
    val state = MutableStateFlow(NovaVisualFixtures.commandCenterState(activity))
    val panel = NovaPanelState()
    // Over the stream the Command Center keeps its solid floor, as the in-game one asks for it.
    panel.open(CommandCenterPage.Root(state.value.title), NovaEdge.Start, overStream = true)
    streamPanel(panel) { page ->
        when (page) {
            is CommandCenterPage.Root -> NovaQuickMenuContent(state = state, callbacks = NovaQuickMenuCallbacks())
            is CommandCenterPage.Listing -> CommandCenterListingPage(page)
            is CommandCenterPage.MouseMode -> CommandCenterMouseModePage(page)
            else -> Unit
        }
    }
    then(panel, state)
}

// The library's panels, in the panel window over a stand-in for the grid.

private fun NovaVisualStage.libraryHints() = listOf(
    NovaControllerHint(
        key = string(R.string.nova_controller_hint_lb_rb),
        label = string(R.string.nova_controller_hint_library_system),
    ),
)

private fun NovaVisualStage.libraryOptions(narrowed: String? = null) {
    backdrop()
    val root = LibraryPage.Options(string(R.string.nova_library_options_title))
    openPanel(root, root.edge, hints = libraryHints(), onShoulder = {}) { page ->
        if (page is LibraryPage.Options) {
            NovaLibraryOptionsPage(ui = NovaVisualFixtures.optionsUi(activity, narrowed), actions = NovaVisualFixtures.optionsActions)
        }
    }
}

private fun NovaVisualStage.librarySystem() {
    backdrop()
    val root = LibraryPage.System(string(R.string.nova_system_menu_title))
    openPanel(root, root.edge, hints = libraryHints(), onShoulder = {}) { page ->
        if (page is LibraryPage.System) {
            NovaLibrarySystemPage(
                ui = NovaVisualFixtures.systemUi(activity),
                actions = NovaVisualFixtures.systemActions { LibraryPage.PolarisSync(string(R.string.nova_polaris_sync_title)) },
            )
        }
    }
}

private fun NovaVisualStage.polarisSync() {
    backdrop()
    val scope = MainScope()
    // A client for a port nothing listens on: the page shows the fixture's settings, and every
    // refresh it asks for fails at once and leaves them as they are.
    val controller = NovaPolarisSyncController(
        context = activity,
        apiClient = PolarisApiClient(activity, "127.0.0.1", 1),
        serverUuid = NovaVisualFixtures.HostUuid,
        scope = scope,
        onSettingsChanged = {},
    )
    onClose {
        controller.close()
        scope.cancel()
    }
    val syncPage = LibraryPage.PolarisSync(string(R.string.nova_polaris_sync_title))
    val root = LibraryPage.System(string(R.string.nova_system_menu_title))
    openPanel(root, root.edge, hints = libraryHints(), onShoulder = {}) { page ->
        when (page) {
            is LibraryPage.System -> NovaLibrarySystemPage(
                ui = NovaVisualFixtures.systemUi(activity),
                actions = NovaVisualFixtures.systemActions { syncPage },
            )
            is LibraryPage.PolarisSync -> NovaPolarisSyncPage(
                controller = controller,
                serverName = NovaVisualFixtures.LongHost,
                serverUuid = NovaVisualFixtures.HostUuid,
                display = @Suppress("DEPRECATION") activity.windowManager.defaultDisplay,
                playInPage = { picker -> PlaySetupPage.PlayIn(title = picker.title, picker = { picker }, onPick = {}) },
                profilePage = { PlaySetupPage.Options(title = "Profile", row = NovaPlaySetupRow.HOST_PROFILE, bands = { emptyList() }) },
            )
            is PlaySetupPage.Plan -> NovaPlaySetupPlanPage(page)
            is PlaySetupPage.Options -> NovaPlaySetupOptionsPage(page)
            else -> Unit
        }
    }
    rule.runOnUiThread { controller.open(NovaVisualFixtures.polarisSettings()) }
    push(syncPage)
}

// The common pages, pushed from a menu so they carry the pushed page's header.

private fun NovaVisualStage.commonPage(page: () -> com.papi.nova.ui.panel.NovaPage) {
    backdrop()
    openPanel(NovaVisualFixtures.streamOptions())
    push(page())
}

// The value rows, on a page of their own in the panel window.

private fun NovaVisualStage.valueRows(focus: String?) {
    backdrop()
    openPanel(NovaVisualFixtures.OwnerPage("value-rows", "Stream Options")) { _ ->
        var buttons by remember { mutableStateOf("printed") }
        var fps by remember { mutableStateOf(120) }
        var hdr by remember { mutableStateOf(true) }
        var bitrate by remember { mutableIntStateOf(80_000) }
        // As an owner page's list is drawn: its edges faded, so it comes to rest on whole rows.
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
            modifier = Modifier.fillMaxWidth().novaScrollEdgeFade(listState),
        ) {
            item {
                NovaRow(
                    title = "Stream Profile",
                    caption = "Balanced for ${NovaVisualFixtures.LongHost} over 5 GHz Wi-Fi",
                    trailing = NovaRowTrailing.Opens,
                    onClick = {},
                    modifier = Modifier.novaInitialFocus(),
                )
            }
            item {
                NovaValueRow(
                    title = "Face Buttons",
                    options = listOf(NovaOption("printed", "As Printed"), NovaOption("swapped", "Swapped"), NovaOption("nintendo", "Nintendo")),
                    current = buttons,
                    onChange = { buttons = it },
                    caption = "Which button confirms in games that read the layout",
                    style = NovaValueStyle.Segmented,
                )
            }
            item {
                NovaValueRow(
                    title = "Frame Rate",
                    options = listOf(30, 40, 45, 60, 90, 120, 144, 165, 240).map { NovaOption(it, "$it FPS") },
                    current = fps,
                    onChange = { fps = it },
                    caption = "Paced to this device's 120 Hz panel",
                    style = NovaValueStyle.Cycler,
                    ordered = true,
                )
            }
            item {
                NovaValueRow(
                    title = "HDR",
                    options = listOf(NovaOption(false, "Off"), NovaOption(true, "On")),
                    current = hdr,
                    onChange = { hdr = it },
                    caption = "Ten bits a colour when the host's display and the game both offer it",
                    style = NovaValueStyle.Switch,
                )
            }
            item {
                NovaStepperRow(
                    title = "Bitrate",
                    value = bitrate,
                    range = 500..300_000,
                    step = 500,
                    format = { "${it / 1000}.${(it % 1000) / 100} Mbps" },
                    onChange = { bitrate = it },
                    caption = "Hold Left or Right to move faster; A types an exact value",
                    onExact = {},
                )
            }
        }
    }
    if (focus == null) return
    // A row past the fold is not composed until the list scrolls to it, so a pad walks down first.
    val target = hasText(focus) and isFocusable()
    repeat(8) {
        if (rule.onAllNodes(target).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()) {
            press(KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }
    focus(target)
}

// Settings, as StreamSettings draws it.

private fun NovaVisualStage.settings(pushCodec: Boolean) {
    val definitions = NovaSettingsAvailability.filter(activity, NovaSettingDefinitions.load(activity)).let { filtered ->
        filtered.copy(settings = filtered.settings.filterNot { it.key == NovaSettingsFeatureFlags.COMPOSE_SETTINGS_KEY })
    }
    val category = definitions.categories.first().key
    var values by mutableStateOf(emptyMap<String, NovaSettingValue>())
    var selected by mutableStateOf(category)
    screen {
        NovaSettingsContent(
            state = NovaSettingsUiStateFactory.build(definitions, values, selected, ""),
            title = string(R.string.pcview_quick_settings),
            subtitle = string(R.string.nova_settings_subtitle_with_version, NovaAppVersion.current()),
            onBack = {},
            onOpenLegacy = {},
            onSearch = {},
            onClearSearch = {},
            onCategory = { selected = it },
            headerActions = emptyList(),
            onResetSetting = {},
            onValue = { definition, value, done ->
                values = values + (definition.key to value)
                done()
            },
            onSetting = {},
        )
    }
    settle(NOVA_FIRST_FOCUS_SETTLE_MS + SettingsSettleMillis)
    if (!pushCodec) return
    // Into the rows as a pad goes: Right from the rail in the wide layout, Down from the
    // categories in the narrow one, then down to the codec, whose list opens as a page.
    val wide = activity.resources.configuration.screenWidthDp >= 720
    press(if (wide) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN)
    val codec = hasTestTag("nova-settings-row-video_format")
    repeat(40) { if (!isFocused(codec)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
    check(isFocused(codec)) { "a pad could not reach the Video Codec row" }
    press(KeyEvent.KEYCODE_DPAD_CENTER)
}

private const val SettingsSettleMillis = 500L

// Play Setup, in the detail's own tree.

/**
 * Play Setup as the detail draws it: the plan card over the root's rows (This Game's in their fixed
 * order, or Every Game's host rows), and the pages A opens from them, pushed inside the panel:
 * Where It Runs, and a row's options with the plan card pinned above them, previewing the option
 * under the cursor.
 */
private fun NovaVisualStage.playSetup(rows: List<NovaPlaySetupRowState>, scope: NovaPlaySetupScope = NovaPlaySetupScope.THIS_GAME) {
    val panel = NovaPanelState()
    panel.open(PlaySetupPage.Root(string(R.string.nova_play_setup_title)))
    val hints = listOf(NovaControllerHint(string(R.string.nova_controller_hint_y), string(R.string.nova_play_setup_panel_scope_hint)))
    val everyGame = scope == NovaPlaySetupScope.EVERY_GAME
    val plan = NovaVisualFixtures.plan
    val planTitle = string(if (everyGame) R.string.nova_play_setup_host_read_title else R.string.nova_play_setup_what_will_happen)
    // Where the game opens joins the mode when the host has Spaces to open it in.
    val place = rows.firstOrNull { it.row == NovaPlaySetupRow.PLAY_IN }?.value
    val planValue = if (everyGame || place.isNullOrBlank()) plan.mode else listOf(place, plan.mode).joinToString(" \u00b7 ")
    val planLine = novaPlaySetupPlanSummary(plan).orEmpty()
    val baseLine = plan.lines.firstOrNull().orEmpty()
    val setHereNote = string(R.string.nova_play_setup_set_for_game)
    val pinnedCard: @Composable (NovaPlaySetupOption?) -> Unit = { focused ->
        val preview = focused?.takeIf { !it.current && it.enabled }?.preview
        if (preview != null) {
            NovaPlaySetupPlanCard(
                title = string(R.string.nova_play_setup_if_you_choose, focused.label),
                value = planValue,
                line = novaPlaySetupPreviewLine(baseLine, preview),
                accentPart = preview.changed,
                limit = preview.limit,
            )
        } else {
            NovaPlaySetupPlanCard(title = planTitle, value = planValue, line = planLine)
        }
    }
    fun whereItRuns() = PlaySetupPage.PlayIn(
        title = string(R.string.nova_game_detail_where_it_runs),
        picker = { NovaVisualFixtures.gameModePicker(activity) },
        onPick = {},
        onPickHostDefault = {},
        places = { rows.firstOrNull { it.row == NovaPlaySetupRow.PLAY_IN } },
    )
    fun options(state: NovaPlaySetupRowState) = PlaySetupPage.Options(
        title = state.label,
        row = state.row,
        bands = { listOf(NovaPlaySetupBand(null, state.options)) },
        footer = if (state.row == NovaPlaySetupRow.RESOLUTION) string(R.string.nova_play_setup_resolution_footer) else "",
    )
    // As the detail does: a row whose value carries a › opens its page; the rest step in place.
    val onAdvance: (NovaPlaySetupRow) -> Unit = { row ->
        val state = rows.firstOrNull { it.row == row }
        when {
            state == null || !state.opensPage -> Unit
            row == NovaPlaySetupRow.WHERE_IT_RUNS || row == NovaPlaySetupRow.PLAY_IN -> panel.push(whereItRuns())
            else -> panel.push(options(state))
        }
    }
    // The game detail's root is not inset as a legacy screen's is: the panel pads for the bars
    // itself, and inset here too it sat a status bar lower than on the device.
    screen(inset = false) {
        NovaPlaySetupPanel(
            panel = panel,
            onClose = {},
            hints = hints,
            headerEnd = { NovaPlaySetupScopePill(scope = scope, onSelected = {}) },
        ) { page ->
            when (page) {
                is PlaySetupPage.PlayIn -> NovaPlayInPage(page, card = { pinnedCard(null) })
                is PlaySetupPage.Options -> NovaPlaySetupOptionsPage(page, card = pinnedCard)
                is PlaySetupPage.Plan -> NovaPlaySetupPlanPage(page)
                else -> NovaPlaySetupBody(
                    card = {
                        NovaPlaySetupPlanCard(
                            title = planTitle,
                            value = planValue,
                            line = planLine,
                            onOpen = { if (isTop) panel.push(PlaySetupPage.Plan(planTitle, plan)) },
                            modifier = Modifier.novaRestorableFocus("plan"),
                        )
                    },
                ) {
                    if (everyGame) {
                        NovaHostSetupRowList(
                            rows = rows,
                            onAdvance = onAdvance,
                            rowModifier = { row, first ->
                                (if (first) Modifier.novaInitialFocus() else Modifier).novaRestorableFocus(row.name)
                            },
                        )
                    } else {
                        novaPlaySetupRootRows(rows).forEachIndexed { index, state ->
                            NovaPlaySetupSettingRow(
                                state = state,
                                onAdvance = onAdvance,
                                setHereNote = setHereNote,
                                modifier = (if (index == 0) Modifier.novaInitialFocus() else Modifier)
                                    .novaRestorableFocus(state.row.name),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A pad's first press on a screen that opened with nothing focused: Down takes focus onto the first
 * control, as it does on the device, so the shot shows where the ring lands.
 */
private fun NovaVisualStage.firstPress() {
    press(KeyEvent.KEYCODE_DPAD_DOWN)
    settle()
}

/** Moves focus to the row named [label] and presses A on it, as a pad opens the row's page. */
private fun NovaVisualStage.openRow(label: String) {
    focus(hasText(label) and isFocusable())
    press(KeyEvent.KEYCODE_DPAD_CENTER)
    settle()
}

/** The plan's page as a pad reaches it: pushed from the root, Up to the plan card and A. */
private fun NovaVisualStage.playSetupPlan() {
    playSetup(NovaVisualFixtures.gameRows(activity))
    focus(hasTestTag(NOVA_PLAY_SETUP_PLAN_CARD_TAG))
    press(KeyEvent.KEYCODE_DPAD_CENTER)
    settle()
}

// Artwork Studio and the library's own pieces, drawn as their screens draw them.

private fun NovaVisualStage.artworkStudio() {
    val state = NovaVisualFixtures.artworkState()
    screen {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            val height = maxHeight
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NovaArtworkStudio(
                    state = state,
                    initialQuery = "Control",
                    onRefresh = {},
                    onSearch = {},
                    onIdentitySelected = {},
                    onChangeIdentity = {},
                    onKindSelected = {},
                    onChoiceSelected = {},
                    onReset = {},
                    onApply = { _, _ -> },
                    onCancel = {},
                    onClear = {},
                    onTransform = { _, _, _ -> },
                    candidatePreviewLoader = { _, _ -> },
                    choicePreviewLoader = { _, _ -> },
                    currentArtworkPresentationKey = { "" },
                    currentArtworkLoader = { _, _ -> },
                    initiallyExpanded = true,
                    fillsDestination = true,
                    fitHeight = height,
                )
            }
        }
    }
}

private fun NovaVisualStage.libraryHero() {
    screen {
        // The home hero stands above the grid in portrait; on a landscape display this is how a
        // tablet in portrait draws it, and on a phone how the phone does.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
            NovaLibraryHeroCard(
                hero = NovaVisualFixtures.hero,
                compact = false,
                apiClient = PolarisApiClient(activity, ""),
                onPrimaryAction = {},
                onSecondaryAction = {},
                onGameFocused = {},
            )
        }
    }
}

private fun NovaVisualStage.libraryStrip() {
    if (!landscape) throw NovaVisualSkip("the strip is the landscape library's; a portrait library draws the home hero")
    val hero = NovaVisualFixtures.hero
    val short = hero.copy(title = NovaVisualFixtures.ShortGame)
    screen {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            listOf(hero, short).forEachIndexed { index, shown ->
                Box(Modifier.padding(top = if (index == 0) 0.dp else 12.dp)) {
                    NovaLibraryLandscapeShowcaseStripContent(
                        hostLabel = NovaVisualFixtures.LongHost,
                        polarisReady = true,
                        onOpenOptions = {},
                        onOpenSystemMenu = {},
                        continueCard = shown.topBarContinue(),
                        continueSlot = { fit ->
                            NovaLibraryStripContinue(
                                hero = shown,
                                apiClient = PolarisApiClient(activity, ""),
                                fit = fit,
                                onPrimaryAction = {},
                                onSecondaryAction = {},
                            )
                        },
                    )
                }
            }
        }
    }
}

// The companion deck: Views, with a Compose End tile.

private fun NovaVisualStage.companionDeck() {
    screen {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> NovaCompanionCommandDeckView(context) { }.apply { render(NovaVisualFixtures.deckState()) } },
        )
    }
}

/**
 * The deck where it lives, on a second display: the Thor's lower screen, or an emulator given
 * one. Skipped where there is none. On API 34 and later its window is shot directly; before
 * that, screencap is asked for the second display.
 */
private fun NovaVisualStage.companionDeckOnSecondDisplay() {
    val second = activity.getSystemService(DisplayManager::class.java).displays
        .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        ?: throw NovaVisualSkip("this device has no second display")
    backdrop()
    val intent = Intent(activity, ComponentActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(second.displayId).toBundle()
    val scenario = ActivityScenario.launch<ComponentActivity>(intent, options)
    otherActivities += scenario
    var deckActivity: ComponentActivity? = null
    scenario.onActivity { other ->
        deckActivity = other
        val deck = NovaCompanionCommandDeckView(other, composeOwner = other) { }
        other.setContentView(deck)
        deck.render(NovaVisualFixtures.deckState())
    }
    settle()
    Thread.sleep(NovaVisualStage.WindowSettleMillis)
    instrumentation.waitForIdleSync()
    settle()
    val window = deckActivity?.window
    val shot: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && window != null) {
        instrumentation.uiAutomation.takeScreenshot(window)
    } else {
        null
    }
    shot?.let { extraShots["second-display"] = it }
}

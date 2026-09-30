package com.papi.nova.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.papi.nova.R
import com.papi.nova.computers.HostForget
import com.papi.nova.grid.NovaHostPlaySurface
import com.papi.nova.grid.novaHostInUse
import com.papi.nova.grid.novaHostPlaySurface
import com.papi.nova.grid.novaWatchRate
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaMenuHeader
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaTone

/** How a host's state reads at a glance: ready to play, wants something from you, or nothing to say. */
internal enum class NovaHostSheetTone { READY, ATTENTION, QUIET }

/** The panel tone a host's state reads in: ready in the accent, wanting something in warning. */
internal val NovaHostSheetTone.panelTone: NovaTone
    get() = when (this) {
        NovaHostSheetTone.READY -> NovaTone.Active
        NovaHostSheetTone.ATTENTION -> NovaTone.Warning
        NovaHostSheetTone.QUIET -> NovaTone.Neutral
    }

/** One thing a host's menu can do, with the sentence that says what it does. */
internal data class NovaHostSheetAction(
    val key: String,
    val label: String,
    val caption: String,
    @param:DrawableRes val iconRes: Int,
)

/** The header's words for a host, as string resources so the screen can fill in the address. */
internal data class NovaHostSheetCopy(
    @param:StringRes val statusRes: Int,
    @param:StringRes val hintRes: Int,
    val tone: NovaHostSheetTone,
    /** What the status is about when that is not the host's address: the device whose game is open. */
    val statusArg: String? = null,
)

/**
 * What the menu's header says about a host: the reading of its state its card gives, in the
 * card's own words, so the two never disagree about the same machine.
 */
internal fun novaHostSheetCopy(details: ComputerDetails): NovaHostSheetCopy {
    if (details.state == ComputerDetails.State.OFFLINE) {
        return NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_offline,
            hintRes = if (details.wakeMacAddress != null) {
                R.string.pcview_card_hint_wake
            } else {
                R.string.pcview_card_hint_offline_no_wake
            },
            tone = NovaHostSheetTone.QUIET,
        )
    }
    if (details.state != ComputerDetails.State.ONLINE) {
        return NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_connecting,
            hintRes = R.string.pcview_card_hint_refreshing,
            tone = NovaHostSheetTone.QUIET,
        )
    }
    val pairedWithoutItsCertificate =
        details.pairState == PairingManager.PairState.PAIRED && details.serverCert == null
    return when {
        pairedWithoutItsCertificate -> NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_repair_pair,
            hintRes = R.string.pcview_card_hint_pair_repair,
            tone = NovaHostSheetTone.ATTENTION,
        )
        details.pairState == PairingManager.PairState.NOT_PAIRED -> NovaHostSheetCopy(
            statusRes = if (details.serverCert == null) {
                R.string.pcview_card_status_pair_required
            } else {
                R.string.pcview_card_status_repair_pair
            },
            hintRes = R.string.pcview_card_hint_pair,
            tone = NovaHostSheetTone.ATTENTION,
        )
        details.runningGameId != 0 -> if (
            novaHostPlaySurface(true, details.currentGameOwnedByClient, details.libraryState, details.currentGameWatchable)
                .let { it != NovaHostPlaySurface.RESUME && it != NovaHostPlaySurface.WATCH }
        ) {
            val inUse = novaHostInUse(details.currentGameOwnerDeviceName, details.currentGameWatchable)
            NovaHostSheetCopy(
                statusRes = inUse.statusRes,
                // The card's advice points at Manage, and this is Manage.
                hintRes = inUse.sheetHintRes,
                tone = NovaHostSheetTone.READY,
                statusArg = inUse.owner,
            )
        } else {
            NovaHostSheetCopy(
                statusRes = R.string.pcview_card_status_streaming,
                hintRes = R.string.pcview_card_hint_streaming,
                tone = NovaHostSheetTone.READY,
            )
        }
        details.libraryState == ComputerDetails.LibraryState.AVAILABLE -> NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_library_ready_format,
            hintRes = if (details.spacesAvailable) {
                R.string.pcview_card_hint_spaces
            } else {
                R.string.pcview_card_hint_open_library
            },
            tone = NovaHostSheetTone.READY,
        )
        details.libraryState == ComputerDetails.LibraryState.UNKNOWN -> NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_checking_library,
            hintRes = R.string.pcview_card_hint_checking_library,
            tone = NovaHostSheetTone.READY,
        )
        else -> NovaHostSheetCopy(
            statusRes = R.string.pcview_card_status_compatibility_format,
            hintRes = R.string.pcview_card_hint_open_apps,
            tone = NovaHostSheetTone.READY,
        )
    }
}

/**
 * Who a host is and how it is, above its menu: its name, the reading its card gives in the card's
 * own words and tone, and the advice that goes with it.
 */
internal fun novaHostMenuHeader(context: Context, details: ComputerDetails): NovaMenuHeader {
    val copy = novaHostSheetCopy(details)
    val address = details.activeAddress?.address?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.pcview_card_status_local_network)
    return NovaMenuHeader(
        title = details.name.orEmpty(),
        status = novaBreakAtDots(context.getString(copy.statusRes, copy.statusArg ?: address)),
        tone = copy.tone.panelTone,
        hint = context.getString(copy.hintRes).takeIf { it.isNotBlank() },
        icon = R.drawable.ic_computer,
    )
}

/** What each row of a host's menu does. The screen answers them; the menu decides which it offers. */
internal interface NovaHostMenuActions {
    fun wake()
    fun sendWakeOnLan()
    fun pair()
    fun otpPairPage(): NovaPage
    fun scanQr()

    /** The host's console, a page pushed in this panel (N6). */
    fun hostConsolePage(): NovaPage
    fun openLibrary()
    fun checkLibrary()
    fun watch()
    fun resume()
    fun endSession()
    fun sleep()
    fun appList()
    fun testNetwork()
    fun editWakeAddress()
    fun delete()
}

/**
 * Collects what a host's menu offers, in the order the screen decides it, as one column of rows.
 *
 * The menu was a flat list under two headings, so the one thing a host is opened for, its library
 * or the stream it is running, sat in a row like Test Network Connection. The first thing offered
 * under play is the primary: the first row, filled with the accent. Anything else, played or
 * managed, follows it, and removing the host stands apart as the last row.
 *
 * A row that leaves the menu for what it does runs [leave] first, once the panel has closed and
 * before its action, so the screen can pick up what it paused while the menu was open. A split
 * closes the panel through [closePanel] once it is confirmed. Rows that push a page of their own
 * keep the panel open.
 */
internal class NovaHostSheetMenu(
    private val closePanel: () -> Unit = {},
    private val leave: () -> Unit = {},
) {
    private var primary: NovaMenuItem? = null
    private val rows = mutableListOf<NovaMenuItem>()
    private var removal: NovaMenuItem? = null

    /** The rows, top to bottom: the primary, what else there is to play and manage, then removal. */
    val items: List<NovaMenuItem> get() = listOfNotNull(primary) + rows + listOfNotNull(removal)

    /** Something to play. The first one offered is the primary. */
    fun play(action: NovaHostSheetAction, run: () -> Unit) {
        if (primary == null) primary = action.row(emphasis = true, run = run) else rows += action.row(run = run)
    }

    /** Something that manages the host, run once the panel has closed. */
    fun manage(action: NovaHostSheetAction, run: () -> Unit) {
        rows += action.row(run = run)
    }

    /** Runs with the panel still open, for an action that pushes a page of its own. */
    fun inPlace(action: NovaHostSheetAction, run: () -> Unit) {
        rows += NovaMenuItem.Action(
            key = action.key,
            label = action.label,
            caption = action.caption,
            icon = action.iconRes,
            closesPanel = false,
            onClick = run,
        )
    }

    /** Pushes the page [page] builds. */
    fun opens(action: NovaHostSheetAction, page: () -> NovaPage) {
        rows += NovaMenuItem.Opens(
            key = action.key,
            label = action.label,
            caption = action.caption,
            icon = action.iconRes,
            page = page,
        )
    }

    /**
     * Splits in its own row into [stayLabel], Stay unless it has a better word, and [confirmLabel],
     * with [consequence] under the pair.
     */
    fun destructive(
        action: NovaHostSheetAction,
        confirmLabel: String,
        consequence: String,
        stayLabel: String? = null,
        run: () -> Unit,
    ) {
        rows += action.split(confirmLabel, consequence, stayLabel, run)
    }

    /** Removing the host: a split in the last row, apart from what manages it, whose safe half is [stayLabel]. */
    fun remove(action: NovaHostSheetAction, confirmLabel: String, consequence: String, stayLabel: String, run: () -> Unit) {
        removal = action.split(confirmLabel, consequence, stayLabel, run)
    }

    private fun NovaHostSheetAction.row(emphasis: Boolean = false, run: () -> Unit) = NovaMenuItem.Action(
        key = key,
        label = label,
        caption = caption,
        icon = iconRes,
        emphasis = emphasis,
        onClick = {
            leave()
            run()
        },
    )

    private fun NovaHostSheetAction.split(
        confirmLabel: String,
        consequence: String,
        stayLabel: String? = null,
        run: () -> Unit,
    ) =
        NovaMenuItem.Destructive(
            key = key,
            label = label,
            confirmLabel = confirmLabel,
            consequence = consequence,
            stayLabel = stayLabel,
            icon = iconRes,
            onConfirm = {
                // The panel leaves first, so what the confirmed action opens is not opened under it.
                closePanel()
                leave()
                run()
            },
        )
}

/**
 * The rows of [details]'s menu. [needsPairing] is whether the host still wants pairing, and
 * [sleepOffered] whether a hold on the dashboard's power control would put this host to sleep: the
 * same host, and its own word that this device may.
 */
internal fun novaHostMenuItems(
    context: Context,
    details: ComputerDetails,
    needsPairing: Boolean,
    sleepOffered: Boolean,
    actions: NovaHostMenuActions,
    closePanel: () -> Unit = {},
    leave: () -> Unit = {},
): List<NovaMenuItem> {
    val menu = NovaHostSheetMenu(closePanel, leave)
    fun action(key: String, @StringRes label: Int, @StringRes caption: Int, @DrawableRes icon: Int) =
        NovaHostSheetAction(key, context.getString(label), context.getString(caption), icon)
    val serverConfig = action(
        "server_config",
        R.string.pcview_menu_open_management_page,
        R.string.pcview_sheet_caption_server_config,
        R.drawable.ic_settings,
    )

    if (details.state == ComputerDetails.State.OFFLINE ||
        details.state == ComputerDetails.State.UNKNOWN
    ) {
        if (!needsPairing) {
            menu.play(action("wake", R.string.pcview_menu_start_polaris, R.string.pcview_sheet_caption_wake, R.drawable.ic_eye_open)) {
                actions.wake()
            }
        }
        menu.play(action("send_wol", R.string.pcview_menu_send_wol, R.string.pcview_sheet_caption_send_wol, R.drawable.ic_eye_open)) {
            actions.sendWakeOnLan()
        }
    } else if (needsPairing) {
        menu.play(action("pair", R.string.pcview_menu_pair_pc, R.string.pcview_sheet_caption_pair, R.drawable.ic_lock)) {
            actions.pair()
        }
        menu.opens(action("pair_otp", R.string.pcview_menu_pair_pc_otp, R.string.pcview_sheet_caption_pair_otp, R.drawable.ic_lock)) {
            actions.otpPairPage()
        }
        menu.manage(action("scan_qr", R.string.pcview_menu_scan_qr, R.string.pcview_sheet_caption_scan_qr, R.drawable.ic_qr_scan)) {
            actions.scanQr()
        }
        // The host's console, where its pairing code comes from. With no certificate from pairing
        // Nova cannot check it, and the console's page says so before it opens anything (N6).
        if (!details.nvidiaServer) {
            menu.opens(serverConfig) { actions.hostConsolePage() }
        }
    } else {
        val libraryFirst = novaHostPlaySurface(
            runningGame = details.runningGameId != 0,
            ownedByThisDevice = details.currentGameOwnedByClient,
            library = details.libraryState,
            watchable = details.currentGameWatchable,
        ).let { it != NovaHostPlaySurface.RESUME && it != NovaHostPlaySurface.WATCH }
        val offerLibrary = {
            if (details.libraryState == ComputerDetails.LibraryState.AVAILABLE) {
                menu.play(action("open_library", R.string.pcview_menu_nova_library, R.string.pcview_sheet_caption_open_library, R.drawable.ic_play)) {
                    actions.openLibrary()
                }
            } else if (details.libraryState == ComputerDetails.LibraryState.UNKNOWN) {
                menu.play(action("checking_library", R.string.pcview_library_checking, R.string.pcview_sheet_caption_checking_library, R.drawable.ic_update)) {
                    actions.checkLibrary()
                }
            }
        }
        // The first thing offered is the menu's primary, and it is the one the card leads to.
        if (libraryFirst) offerLibrary()
        if (details.runningGameId != 0) {
            if (details.currentGameOwnedByClient == false) {
                // A host that says nobody is streaming the game has nothing to watch, and a row
                // that can only answer "there is nothing to watch" is not an offer.
                if (novaHostInUse(details.currentGameOwnerDeviceName, details.currentGameWatchable).offersWatch) {
                    val mode = details.currentGameWatchProfile
                    val caption = if (mode != null) {
                        context.getString(R.string.pcview_sheet_caption_watch_mode, mode.width, mode.height, novaWatchRate(mode.fps))
                    } else {
                        context.getString(R.string.pcview_sheet_caption_watch)
                    }
                    menu.play(NovaHostSheetAction("watch", context.getString(R.string.applist_menu_watch), caption, R.drawable.ic_eye_open)) {
                        actions.watch()
                    }
                }
            } else {
                menu.play(action("resume", R.string.applist_menu_resume, R.string.pcview_sheet_caption_resume, R.drawable.ic_play)) {
                    actions.resume()
                }
                menu.destructive(
                    action("end_session", R.string.applist_menu_quit, R.string.pcview_sheet_caption_end_session, R.drawable.ic_close),
                    confirmLabel = context.getString(R.string.game_dialog_action_end_session),
                    consequence = context.getString(R.string.nova_panel_end_session_message),
                ) { actions.endSession() }
            }
        }
        if (!libraryFirst) offerLibrary()

        // Only where a hold on the dashboard would work. An awake host has nothing to be woken
        // for, so the row that used to say Wake Host here is gone rather than renamed. One A
        // never puts a host to sleep: the row splits, as ending a session does, with Keep Awake
        // focused, and the split is the confirm (M12).
        if (sleepOffered) {
            menu.destructive(
                action("sleep", R.string.pcview_quick_sleep_host, R.string.pcview_sheet_caption_sleep, R.drawable.ic_eye_closed),
                confirmLabel = context.getString(R.string.pcview_quick_sleep_host),
                consequence = context.getString(R.string.pcview_sleep_consequence),
                stayLabel = context.getString(R.string.pcview_sleep_keep_awake),
            ) { actions.sleep() }
        }
        menu.manage(action("app_list", R.string.pcview_menu_app_list, R.string.pcview_sheet_caption_app_list, R.drawable.ic_menu)) {
            actions.appList()
        }
        if (!details.nvidiaServer) {
            menu.opens(serverConfig) { actions.hostConsolePage() }
        }
    }

    // The network test pushes a Busy page, then its result, in this panel.
    menu.inPlace(action("test_network", R.string.pcview_menu_test_network, R.string.pcview_sheet_caption_test_network, R.drawable.ic_language)) {
        actions.testNetwork()
    }
    menu.inPlace(action("wake_address", R.string.wol_address_title, R.string.wol_address_caption, R.drawable.ic_edit)) {
        actions.editWakeAddress()
    }
    menu.opens(action("details", R.string.pcview_menu_details, R.string.pcview_sheet_caption_details, R.drawable.ic_help)) {
        NovaCommonPage.Notice(
            key = "details",
            title = context.getString(R.string.title_details),
            message = novaHostDetailsText(context, details),
            closeLabel = context.getString(R.string.nova_panel_close),
        )
    }
    menu.remove(
        action("delete", R.string.pcview_menu_delete_pc, R.string.pcview_sheet_caption_delete, R.drawable.ic_delete),
        confirmLabel = context.getString(R.string.pcview_menu_delete_pc),
        consequence = context.getString(novaHostDeleteConsequence(details)),
        stayLabel = context.getString(R.string.nova_panel_keep),
    ) { actions.delete() }
    return menu.items
}

/** What deleting [details] does beyond this device, the same reading the delete used to ask with. */
@StringRes
internal fun novaHostDeleteConsequence(details: ComputerDetails): Int = when {
    HostForget.mayCloseRunningGame(details) -> R.string.hosts_delete_consequence_running
    HostForget.canAsk(details) -> R.string.hosts_delete_consequence_paired
    else -> R.string.hosts_delete_consequence
}

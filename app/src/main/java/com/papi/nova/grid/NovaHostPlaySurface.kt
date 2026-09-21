package com.papi.nova.grid

import androidx.annotation.StringRes
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails

/** Where a press on a host leads: its card, its primary pill and its sheet all ask this one question. */
internal enum class NovaHostPlaySurface { RESUME, WATCH, LIBRARY, CHECK_LIBRARY, APP_LIST }

/**
 * What a host is opened for, given what is running on it and whose it is.
 *
 * A running game used to win outright, so a game another device had left open turned every other
 * device's card into Watch Stream. papi, 2026-09-21, with Alan Wake 2 open on his Deck and no
 * stream attached: "i also am stuck at Watch Stream it wont just go to the library". This device's
 * own game still comes first, because going back to it is what its owner wants. Someone else's
 * game does not stand between this device and its library; watching it is offered from the sheet.
 *
 * That game had no stream at all, and Watch was offered anyway, because a host said only that it
 * was busy. A host that says nobody is streaming the game ([watchable] false) has nothing to
 * watch. One that says nothing about it is an older host, and watching stays the offer it was.
 */
internal fun novaHostPlaySurface(
    runningGame: Boolean,
    ownedByThisDevice: Boolean?,
    library: ComputerDetails.LibraryState?,
    watchable: Boolean? = null,
): NovaHostPlaySurface {
    val someoneElses = runningGame && ownedByThisDevice == false
    if (runningGame && !someoneElses) {
        return NovaHostPlaySurface.RESUME
    }
    val watch = someoneElses && watchable != false
    return when (library) {
        ComputerDetails.LibraryState.AVAILABLE -> NovaHostPlaySurface.LIBRARY
        ComputerDetails.LibraryState.UNKNOWN ->
            if (watch) NovaHostPlaySurface.WATCH else NovaHostPlaySurface.CHECK_LIBRARY
        else -> if (watch) NovaHostPlaySurface.WATCH else NovaHostPlaySurface.APP_LIST
    }
}

/** How someone else's game on a host reads: whose it is, and whether there is a stream of it. */
internal data class NovaHostInUse(
    @param:StringRes val statusRes: Int,
    /** The owner's device, for a status that names it; null for one that says "another device". */
    val owner: String?,
    @param:StringRes val cardHintRes: Int,
    @param:StringRes val sheetHintRes: Int,
    val offersWatch: Boolean,
)

/**
 * The card and the sheet both said "Another device has a game open" and pointed at watching it,
 * whether or not anything was being streamed and whoever the device was. They say whose game it
 * is when the host names the device, say it is being streamed only when the host says so, and
 * stop pointing at a watch there is no stream for.
 */
internal fun novaHostInUse(ownerDeviceName: String?, watchable: Boolean?): NovaHostInUse {
    val owner = novaHostOwnerLabel(ownerDeviceName)
    val streaming = watchable == true
    return NovaHostInUse(
        statusRes = when {
            streaming && owner != null -> R.string.pcview_card_status_watchable_named
            streaming -> R.string.pcview_card_status_watchable
            owner != null -> R.string.pcview_card_status_in_use_named
            else -> R.string.pcview_card_status_in_use
        },
        owner = owner,
        cardHintRes = if (watchable == false) R.string.pcview_card_hint_in_use_idle else R.string.pcview_card_hint_in_use,
        sheetHintRes = if (watchable == false) R.string.pcview_sheet_hint_in_use_idle else R.string.pcview_sheet_hint_in_use,
        offersWatch = watchable != false,
    )
}

/**
 * The pill's words when a press leads past someone else's game to this device's own way in. They
 * are the words the same surface wears on an idle host, so the pill never says one thing and
 * does another.
 */
@StringRes
internal fun novaHostOwnWayInLabel(surface: NovaHostPlaySurface): Int = when (surface) {
    NovaHostPlaySurface.CHECK_LIBRARY -> R.string.pcview_card_action_checking_library
    NovaHostPlaySurface.APP_LIST -> R.string.pcview_card_action_open_apps
    else -> R.string.pcview_card_action_open_library
}

private val NOVA_HOST_OWNER_ID = Regex("[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}")

/**
 * A device name fit to put on screen, or null. A host's older owner field is an id on a Desktop
 * session, and an id is never shown to anybody as if it were a name.
 */
internal fun novaHostOwnerLabel(raw: String?): String? {
    val name = raw?.filterNot { it.isISOControl() }?.trim().orEmpty()
    if (name.isEmpty() || NOVA_HOST_OWNER_ID.matches(name)) {
        return null
    }
    return if (name.length > 40) name.take(39).trimEnd() + "\u2026" else name
}

/** A rate the way a person says it: 90, or 59.94, never 90.0. */
internal fun novaWatchRate(fps: Float): String {
    val hundredths = Math.round(fps * 100f)
    return if (hundredths % 100 == 0) (hundredths / 100).toString() else String.format(java.util.Locale.US, "%.2f", hundredths / 100f)
}

/** Which way a D-pad press moves between a host's row and the Manage pill inside it. */
internal enum class NovaHostRowFocusMove { TO_MANAGE, TO_ROW, NONE }

/**
 * The Manage pill sits inside the row that holds focus, and a focus search never looks inside the
 * thing it is leaving, so no direction reached it: a controller's only way to a host's sheet was
 * a long press. Right from the row goes in to Manage and Left from Manage comes back out.
 */
internal fun novaHostRowFocusMove(right: Boolean, left: Boolean, onRow: Boolean, onManage: Boolean): NovaHostRowFocusMove =
    when {
        right && onRow -> NovaHostRowFocusMove.TO_MANAGE
        left && onManage -> NovaHostRowFocusMove.TO_ROW
        else -> NovaHostRowFocusMove.NONE
    }

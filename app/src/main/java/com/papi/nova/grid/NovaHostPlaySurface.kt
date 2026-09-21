package com.papi.nova.grid

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
 */
internal fun novaHostPlaySurface(
    runningGame: Boolean,
    ownedByThisDevice: Boolean?,
    library: ComputerDetails.LibraryState?,
): NovaHostPlaySurface {
    val someoneElses = runningGame && ownedByThisDevice == false
    if (runningGame && !someoneElses) {
        return NovaHostPlaySurface.RESUME
    }
    return when (library) {
        ComputerDetails.LibraryState.AVAILABLE -> NovaHostPlaySurface.LIBRARY
        ComputerDetails.LibraryState.UNKNOWN ->
            if (someoneElses) NovaHostPlaySurface.WATCH else NovaHostPlaySurface.CHECK_LIBRARY
        else -> if (someoneElses) NovaHostPlaySurface.WATCH else NovaHostPlaySurface.APP_LIST
    }
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

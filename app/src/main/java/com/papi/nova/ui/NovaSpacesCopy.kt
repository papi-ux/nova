package com.papi.nova.ui

import androidx.annotation.StringRes
import com.papi.nova.R
import com.papi.nova.api.PolarisSpace
import com.papi.nova.api.PolarisSpaces

/** The Space control's words, as resources, so the rules are testable without Compose. */
internal data class NovaEnvironmentLabel(
    @StringRes val caption: Int,
    /** A fixed name (Desktop, unavailable, none assigned), or null when [spaceName] is the name. */
    @StringRes val nameRes: Int?,
    val spaceName: String?,
    @StringRes val status: Int?,
    /**
     * More than one place to play. The control opens the chooser either way; this only says
     * whether the chooser has another place to offer.
     */
    val offersChoice: Boolean,
)

/** The chooser's Desktop row: whether it can be chosen now, and the caption that says so or why not. */
internal data class NovaDesktopChoice(
    val enabled: Boolean,
    @StringRes val caption: Int,
)

/**
 * One vocabulary for Spaces, keyed on the words the host sends, so the library, the chooser,
 * Play Setup and the host card describe the same Space with the same words.
 */
internal object NovaSpacesCopy {
    /** One word per state of /polaris/v1/spaces; anything else, or no answer yet, is "Status unknown". */
    @StringRes
    fun stateLabel(state: String?): Int = when (state) {
        "ready" -> R.string.nova_space_state_ready
        "starting" -> R.string.nova_space_state_starting
        "running" -> R.string.nova_space_state_running
        "stopping" -> R.string.nova_space_state_stopping
        "in_use" -> R.string.nova_space_state_in_use
        "unavailable" -> R.string.nova_space_state_unavailable
        else -> R.string.nova_space_state_unknown
    }

    /**
     * The name a player knows a launcher by. These are product names, so they are not translated.
     * A word this build has never seen is shown as nothing: a raw wire token on a row helps nobody.
     */
    fun launcherName(launcher: String?): String? = when (launcher) {
        "steam" -> "Steam"
        "heroic" -> "Heroic"
        "lutris" -> "Lutris"
        else -> null
    }

    /**
     * What sits under a Space's name in the chooser: the launcher first, so the eye finds it in the
     * same place on every row, then whatever else the row has to say.
     */
    fun chooserCaption(launcher: String?, note: String?): String =
        listOfNotNull(launcherName(launcher), note?.takeIf { it.isNotBlank() }).joinToString(" \u00B7 ")

    /** Why the host cannot offer Spaces, or null while it can. */
    @StringRes
    fun unavailableReason(snapshot: PolarisSpaces): Int? {
        if (snapshot.available) return null
        return when (snapshot.unavailableReason) {
            "no_space_assigned" -> R.string.nova_space_unavailable_no_space_assigned
            "stopping" -> R.string.nova_space_unavailable_stopping
            "reconfiguring" -> R.string.nova_space_unavailable_reconfiguring
            "admin_failed" -> R.string.nova_space_unavailable_admin_failed
            "selection_failed" -> R.string.nova_space_unavailable_selection_failed
            "controller_missing" -> R.string.nova_space_unavailable_controller_missing
            else -> R.string.nova_space_unavailable_generic
        }
    }

    /**
     * What the library's Space control shows, from the host's words only: a caption, the
     * current name, an optional status and whether there is anything to choose.
     *
     * Desktop is not a Space, so its caption names the computer instead of calling it "Your
     * Space". While a change is on the wire the caption says so in place of the usual word.
     */
    fun environmentLabel(snapshot: PolarisSpaces, statusKnown: Boolean = true, changing: Boolean = false): NovaEnvironmentLabel {
        val unavailable = unavailableReason(snapshot)
        val selected = snapshot.selected
        val caption = when {
            changing -> R.string.nova_space_changing
            unavailable != null -> R.string.nova_space_bar_spaces
            snapshot.desktopSelected -> R.string.nova_space_bar_desktop
            else -> R.string.nova_space_bar_your_space
        }
        val nameRes = when {
            unavailable != null -> R.string.nova_space_bar_unavailable
            snapshot.desktopSelected -> R.string.nova_space_desktop
            selected != null -> null
            else -> R.string.nova_space_bar_none
        }
        val status = when {
            changing || unavailable != null || selected == null -> null
            !statusKnown -> R.string.nova_space_state_unknown
            selected.state == "ready" -> null
            else -> stateLabel(selected.state)
        }
        return NovaEnvironmentLabel(
            caption = caption,
            nameRes = nameRes,
            spaceName = if (nameRes == null) selected?.name else null,
            status = status,
            offersChoice = snapshot.spaces.size + (if (snapshot.desktopAllowed) 1 else 0) > 1,
        )
    }

    /**
     * The chooser always lists Desktop. Without Desktop Access the row is off and says where to
     * turn it on, so a device that cannot leave its Space learns why instead of finding nothing to
     * press.
     */
    fun desktopChoice(snapshot: PolarisSpaces): NovaDesktopChoice = when {
        !snapshot.desktopAllowed -> NovaDesktopChoice(false, R.string.nova_space_desktop_access_off)
        snapshot.selectedId == "desktop" -> NovaDesktopChoice(snapshot.canSwitch, R.string.nova_space_current)
        else -> NovaDesktopChoice(snapshot.canSwitch, R.string.nova_space_desktop_caption)
    }

    /** The one Space this device may use when it has no other Space and no Desktop Access; null otherwise. */
    fun onlyPlace(snapshot: PolarisSpaces): PolarisSpace? =
        snapshot.spaces.singleOrNull()?.takeIf { !snapshot.desktopAllowed }

    /** A device with nothing to stream: the host says so, or lists no Space and allows no Desktop. */
    fun noSpaceAssigned(snapshot: PolarisSpaces): Boolean = snapshot.enabled &&
        (snapshot.unavailableReason == "no_space_assigned" || (snapshot.spaces.isEmpty() && !snapshot.desktopAllowed))

    /** Why this device cannot change Space right now, or null while it can. */
    @StringRes
    fun switchBlockedReason(snapshot: PolarisSpaces): Int? {
        if (snapshot.canSwitch) return null
        unavailableReason(snapshot)?.let { return it }
        return when (snapshot.switchBlockedReason) {
            "your_stream" -> R.string.nova_space_switch_blocked_your_stream
            "desktop_stream" -> R.string.nova_space_switch_blocked_desktop_stream
            else -> R.string.nova_space_switch_blocked_generic
        }
    }

    /** Why a Space cannot be opened now, or null when it can be opened or resumed. */
    @StringRes
    fun openBlockedReason(space: PolarisSpace): Int? = openBlockedReason(space.state, space.openable, space.blockedReason)

    @StringRes
    fun openBlockedReason(state: String, openable: Boolean, blockedReason: String?): Int? = when {
        openable -> null
        blockedReason == "at_capacity" -> R.string.nova_space_blocked_at_capacity
        state == "starting" -> R.string.nova_space_blocked_starting
        state == "stopping" -> R.string.nova_space_blocked_stopping
        state == "in_use" -> R.string.nova_space_in_use
        state == "unavailable" -> R.string.nova_space_blocked_unavailable
        else -> R.string.nova_space_blocked_generic
    }

    /** The short form of [openBlockedReason], for a Play button that cannot act yet. */
    @StringRes
    fun playBlockedLabel(state: String, blockedReason: String?): Int = when {
        blockedReason == "at_capacity" -> R.string.nova_space_play_blocked_at_capacity
        state == "starting" -> R.string.nova_space_play_blocked_starting
        state == "stopping" -> R.string.nova_space_play_blocked_stopping
        state == "in_use" -> R.string.nova_space_play_blocked_in_use
        state == "unavailable" -> R.string.nova_space_play_blocked_unavailable
        else -> R.string.nova_space_checking
    }

    enum class EmptyLibraryCase { NO_SPACE_ASSIGNED, HOST_UNAVAILABLE }

    /**
     * Whether an empty library is explained by Spaces, and how. Null when it is empty for an
     * ordinary reason, so the usual "No games yet" stands. Before this, a device with no
     * Space assigned read "No games yet. Manage Library", a diagnosis of the wrong thing.
     */
    fun emptyLibraryCase(snapshot: PolarisSpaces): EmptyLibraryCase? = when {
        !snapshot.enabled -> null
        noSpaceAssigned(snapshot) -> EmptyLibraryCase.NO_SPACE_ASSIGNED
        !snapshot.available -> EmptyLibraryCase.HOST_UNAVAILABLE
        else -> null
    }
}

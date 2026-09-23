package com.papi.nova.manager

import androidx.annotation.StringRes
import com.papi.nova.R

/**
 * Why Nova refused to start a launch.
 *
 * Every refusal used to show the same line: update Polaris before launching. That is true for one of
 * these and wrong for the rest, and it sent people to update a host that was already current and
 * already right, while the thing that actually stopped them went unnamed. A refusal that cannot say
 * what happened costs more than one that says nothing, because it sends the person somewhere else.
 */
enum class LaunchRefusalReason {
    /** The host never answered, so nothing about it is known. */
    HOST_UNREACHABLE,

    /** The host answered, and it is not a Polaris that resolves launches deterministically. */
    HOST_TOO_OLD,

    /** It answered without a resolved profile that it can be held to on /launch. */
    PROFILE_NOT_DETERMINISTIC,

    /** It refused in its own words, which are shown instead of any of these. */
    HOST_REFUSED,

    /** A Space resolved outside the contract this launch was built from. */
    SPACE_PROFILE,

    /** It resolved a display topology this launch did not ask for. */
    TOPOLOGY,

    /** It could not give this launch the HDR it asked for. */
    HDR,

    /** It could not give this launch the encoder it asked for. */
    ENCODER,

    /** It resolved a resolution or frame rate outside what this launch locked. */
    DISPLAY_MODE,

    /** It resolved a bitrate outside what this launch allows. */
    BITRATE;

    @StringRes
    fun messageRes(): Int = when (this) {
        HOST_UNREACHABLE -> R.string.nova_launch_host_unreachable
        HOST_TOO_OLD, PROFILE_NOT_DETERMINISTIC -> R.string.nova_launch_deterministic_host_required
        HOST_REFUSED, SPACE_PROFILE -> R.string.nova_launch_retry
        TOPOLOGY -> R.string.nova_launch_envelope_topology
        HDR -> R.string.nova_launch_envelope_hdr
        ENCODER -> R.string.nova_launch_envelope_encoder
        DISPLAY_MODE -> R.string.nova_launch_envelope_display
        BITRATE -> R.string.nova_launch_envelope_bitrate
    }
}

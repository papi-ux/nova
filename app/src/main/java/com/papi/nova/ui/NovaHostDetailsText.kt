package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager

/**
 * View Details for a host, as labelled lines a person reads: what it is called, whether it is
 * there, where Nova reaches it, and whether it is paired and playing. It was ComputerDetails'
 * developer dump in monospace, "Manual Address: null" and permission bits included.
 */
internal fun novaHostDetailsText(context: Context, details: ComputerDetails): String {
    fun yesNo(value: Boolean) = context.getString(if (value) R.string.nova_host_details_yes else R.string.nova_host_details_no)
    val status = when (details.state) {
        ComputerDetails.State.ONLINE -> R.string.nova_host_details_online
        ComputerDetails.State.OFFLINE -> R.string.nova_host_details_offline
        else -> R.string.nova_host_details_checking
    }
    val active = details.activeAddress?.toString()
    val others = listOfNotNull(details.localAddress, details.remoteAddress, details.ipv6Address, details.manualAddress)
        .map { it.toString() }
        .filter { it != active }
        .distinct()
    val playing = details.runningGameId != 0
    return buildList {
        details.name.takeIf { it.isNotBlank() }?.let { add(context.getString(R.string.nova_host_details_name, it)) }
        add(context.getString(R.string.nova_host_details_status, context.getString(status)))
        active?.let { add(context.getString(R.string.nova_host_details_address, it)) }
        if (others.isNotEmpty()) add(context.getString(R.string.nova_host_details_other_addresses, others.joinToString(", ")))
        details.macAddress?.takeIf { it.isNotBlank() }?.let { add(context.getString(R.string.nova_host_details_mac, it)) }
        details.pairState?.let { add(context.getString(R.string.nova_host_details_paired, yesNo(it == PairingManager.PairState.PAIRED))) }
        if (playing) {
            val who = details.currentGameOwnerDeviceName?.takeIf { it.isNotBlank() }
                ?: details.currentGameOwnerName?.takeIf { it.isNotBlank() }
            add(context.getString(R.string.nova_host_details_playing, who ?: yesNo(true)))
        }
        if (details.httpsPort > 0) add(context.getString(R.string.nova_host_details_https_port, details.httpsPort))
        details.uuid.takeIf { it.isNotBlank() }?.let { add(context.getString(R.string.nova_host_details_id, it)) }
    }.joinToString("\n")
}

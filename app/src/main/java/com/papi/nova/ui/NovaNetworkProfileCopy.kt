package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.network.HostConnectionCondition
import com.papi.nova.network.HostConnectionEvidence
import com.papi.nova.network.NetworkProfileRule
import com.papi.nova.network.TransportCondition
import com.papi.nova.network.YesNoCondition

/**
 * The words for host connection evidence and network rules. Only fixed resources are used: no
 * address, hostname, tailnet or error text from the host ever reaches the screen through here.
 */
internal object NovaNetworkProfileCopy {
    /** The Host connection value; null evidence means the answer has not arrived yet. */
    fun hostConnectionValue(context: Context, evidence: HostConnectionEvidence?): String = context.getString(
        when (evidence?.status) {
            null -> R.string.nova_host_connection_checking
            HostConnectionEvidence.Status.UNKNOWN -> R.string.nova_host_connection_unknown
            HostConnectionEvidence.Status.NOT_TAILSCALE -> R.string.nova_host_connection_not_tailscale
            HostConnectionEvidence.Status.TAILSCALE -> when (evidence.transport) {
                HostConnectionEvidence.Transport.DIRECT -> R.string.nova_host_connection_tailscale_direct
                HostConnectionEvidence.Transport.DERP -> R.string.nova_host_connection_tailscale_derp
                HostConnectionEvidence.Transport.PEER_RELAY -> R.string.nova_host_connection_tailscale_peer_relay
                HostConnectionEvidence.Transport.UNKNOWN -> R.string.nova_host_connection_tailscale_route_unknown
            }
        },
    )

    /** Why it is unknown, then what the answer covers; informational, never a fault. */
    fun hostConnectionDetail(context: Context, evidence: HostConnectionEvidence?): String {
        val scope = context.getString(R.string.nova_host_connection_scope)
        if (evidence == null || evidence.status != HostConnectionEvidence.Status.UNKNOWN) return scope
        val why = when (evidence.reason) {
            HostConnectionEvidence.Reason.HOST_UNSUPPORTED,
            HostConnectionEvidence.Reason.UNSUPPORTED_PLATFORM -> R.string.nova_host_connection_unknown_unsupported
            HostConnectionEvidence.Reason.LOCALAPI_UNAVAILABLE,
            HostConnectionEvidence.Reason.REQUEST_FAILED -> R.string.nova_host_connection_unknown_unavailable
            HostConnectionEvidence.Reason.STALE,
            HostConnectionEvidence.Reason.FROM_THE_FUTURE -> R.string.nova_host_connection_unknown_stale
            HostConnectionEvidence.Reason.UNSUPPORTED_SCHEMA,
            HostConnectionEvidence.Reason.INCONSISTENT,
            HostConnectionEvidence.Reason.MALFORMED_STATUS,
            HostConnectionEvidence.Reason.UNRECOGNIZED -> R.string.nova_host_connection_unknown_unreadable
            HostConnectionEvidence.Reason.PEER_NOT_CONFIRMED,
            HostConnectionEvidence.Reason.PEER_IDLE,
            HostConnectionEvidence.Reason.TRANSPORT_UNKNOWN,
            HostConnectionEvidence.Reason.OBSERVED -> R.string.nova_host_connection_unknown_unconfirmed
        }
        return context.getString(why) + " " + scope
    }

    fun transportLabel(context: Context, value: TransportCondition): String = context.getString(
        when (value) {
            TransportCondition.ANY -> R.string.nova_network_rules_any
            TransportCondition.WIFI -> R.string.nova_network_rules_wifi
            TransportCondition.ETHERNET -> R.string.nova_network_rules_ethernet
            TransportCondition.CELLULAR -> R.string.nova_network_rules_cellular
        },
    )

    fun yesNoLabel(context: Context, value: YesNoCondition): String = context.getString(
        when (value) {
            YesNoCondition.ANY -> R.string.nova_network_rules_any
            YesNoCondition.YES -> R.string.nova_network_rules_yes
            YesNoCondition.NO -> R.string.nova_network_rules_no
        },
    )

    fun hostConnectionLabel(context: Context, value: HostConnectionCondition): String = context.getString(
        when (value) {
            HostConnectionCondition.ANY -> R.string.nova_network_rules_any
            HostConnectionCondition.TAILSCALE_CONFIRMED -> R.string.nova_network_rules_tailscale_confirmed
        },
    )

    /** "Wi-Fi · not metered · Tailscale (host confirmed)", or "any network" when nothing is set. */
    fun ruleSummary(context: Context, rule: NetworkProfileRule): String = buildList {
        if (rule.transport != TransportCondition.ANY) add(transportLabel(context, rule.transport))
        when (rule.metered) {
            YesNoCondition.YES -> add(context.getString(R.string.nova_network_rules_summary_metered))
            YesNoCondition.NO -> add(context.getString(R.string.nova_network_rules_summary_unmetered))
            YesNoCondition.ANY -> Unit
        }
        when (rule.novaVpn) {
            YesNoCondition.YES -> add(context.getString(R.string.nova_network_rules_summary_vpn))
            YesNoCondition.NO -> add(context.getString(R.string.nova_network_rules_summary_no_vpn))
            YesNoCondition.ANY -> Unit
        }
        if (rule.hostConnection != HostConnectionCondition.ANY) add(hostConnectionLabel(context, rule.hostConnection))
    }.ifEmpty { listOf(context.getString(R.string.nova_network_rules_summary_any)) }.joinToString(" · ")
}

package com.papi.nova.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * The network facts a rule may test, as Android reports them for Nova's own traffic. Every
 * field is nullable: null means unknown, and an unknown fact never satisfies a specific
 * condition. Nothing here identifies a network (no SSID, address, hostname or tailnet name).
 */
data class NetworkSnapshot(
    val transport: LinkTransport?,
    val metered: Boolean?,
    /** Nova's own default network is a VPN; it says nothing about which VPN. */
    val novaVpn: Boolean?,
    val hostConnection: HostConnectionEvidence.Status,
) {
    companion object {
        @JvmStatic
        fun read(context: Context, hostConnection: HostConnectionEvidence.Status): NetworkSnapshot {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return NetworkSnapshot(null, null, null, hostConnection)
            }
            val caps = runCatching { cm.activeNetwork?.let(cm::getNetworkCapabilities) }.getOrNull()
                ?: return NetworkSnapshot(null, null, null, hostConnection)
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            // A VPN may or may not declare the transport underneath it; when it is silent or names
            // more than one, the transport is unknown rather than guessed.
            val underlying = buildList {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(LinkTransport.WIFI)
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(LinkTransport.ETHERNET)
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(LinkTransport.CELLULAR)
            }
            val metered = runCatching { cm.isActiveNetworkMetered }.getOrNull()
            return NetworkSnapshot(underlying.singleOrNull(), metered, vpn, hostConnection)
        }
    }
}

enum class LinkTransport { WIFI, ETHERNET, CELLULAR }

enum class TransportCondition(val wire: String) {
    ANY("any"), WIFI("wifi"), ETHERNET("ethernet"), CELLULAR("cellular");

    fun matches(value: LinkTransport?): Boolean = when (this) {
        ANY -> true
        WIFI -> value == LinkTransport.WIFI
        ETHERNET -> value == LinkTransport.ETHERNET
        CELLULAR -> value == LinkTransport.CELLULAR
    }

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value }
    }
}

enum class YesNoCondition(val wire: String) {
    ANY("any"), YES("yes"), NO("no");

    fun matches(value: Boolean?): Boolean = when (this) {
        ANY -> true
        YES -> value == true
        NO -> value == false
    }

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Only the host's confirmed answer can be a condition (the contract allows profile conditions on
 * confirmed=true alone), so "not Tailscale" is shown but never matched on. Relay or direct
 * transport is deliberately not a condition either: it is informational, and must not choose a
 * different bitrate.
 */
enum class HostConnectionCondition(val wire: String) {
    ANY("any"), TAILSCALE_CONFIRMED("tailscale_confirmed");

    fun matches(value: HostConnectionEvidence.Status): Boolean = when (this) {
        ANY -> true
        TAILSCALE_CONFIRMED -> value == HostConnectionEvidence.Status.TAILSCALE
    }

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value }
    }
}

/** One rule: when every condition holds, launch with the saved profile [profileUuid]. */
data class NetworkProfileRule(
    val profileUuid: String,
    val transport: TransportCondition = TransportCondition.ANY,
    val metered: YesNoCondition = YesNoCondition.ANY,
    val novaVpn: YesNoCondition = YesNoCondition.ANY,
    val hostConnection: HostConnectionCondition = HostConnectionCondition.ANY,
) {
    fun matches(snapshot: NetworkSnapshot): Boolean =
        transport.matches(snapshot.transport) &&
            metered.matches(snapshot.metered) &&
            novaVpn.matches(snapshot.novaVpn) &&
            hostConnection.matches(snapshot.hostConnection)
}

/** One host's rules, in the priority order the user sees. Off unless the user turns them on. */
data class HostNetworkRules(
    val enabled: Boolean = false,
    val rules: List<NetworkProfileRule> = emptyList(),
) {
    companion object {
        val NONE = HostNetworkRules()
    }
}

/** The first rule that matched, by its visible position (0-based), and the profile it names. */
data class NetworkRuleMatch(val index: Int, val rule: NetworkProfileRule)

object NetworkProfileRuleEngine {
    /**
     * First match wins. Disabled rule sets never match, and a rule whose profile no longer
     * exists is skipped rather than launching with settings the user can't see.
     */
    @JvmStatic
    fun evaluate(
        rules: HostNetworkRules,
        snapshot: NetworkSnapshot,
        profileExists: (String) -> Boolean,
    ): NetworkRuleMatch? {
        if (!rules.enabled) return null
        rules.rules.forEachIndexed { index, rule ->
            if (profileExists(rule.profileUuid) && rule.matches(snapshot)) return NetworkRuleMatch(index, rule)
        }
        return null
    }
}

/**
 * Rules are stored per host UUID, holding only saved-profile UUIDs and condition enums: no
 * network names, addresses or anything learned from the host.
 */
class NetworkProfileRuleStore(private val prefs: android.content.SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    fun load(hostUuid: String?): HostNetworkRules {
        if (hostUuid.isNullOrBlank()) return HostNetworkRules.NONE
        return decode(prefs.getString(key(hostUuid), null))
    }

    fun save(hostUuid: String, rules: HostNetworkRules) {
        require(hostUuid.isNotBlank())
        if (!rules.enabled && rules.rules.isEmpty()) {
            prefs.edit().remove(key(hostUuid)).apply()
        } else {
            prefs.edit().putString(key(hostUuid), encode(rules)).apply()
        }
    }

    companion object {
        const val PREFS = "nova_network_profile_rules"
        const val MAX_RULES = 8
        private const val VERSION = 1

        private fun key(hostUuid: String) = "host:$hostUuid"

        @JvmStatic
        fun encode(rules: HostNetworkRules): String = JSONObject()
            .put("v", VERSION)
            .put("enabled", rules.enabled)
            .put("rules", JSONArray().apply {
                rules.rules.take(MAX_RULES).forEach { rule ->
                    put(JSONObject()
                        .put("profile", rule.profileUuid)
                        .put("transport", rule.transport.wire)
                        .put("metered", rule.metered.wire)
                        .put("vpn", rule.novaVpn.wire)
                        .put("host_connection", rule.hostConnection.wire))
                }
            })
            .toString()

        /** Anything unreadable falls back to no rules (off); an unreadable rule is dropped. */
        @JvmStatic
        fun decode(raw: String?): HostNetworkRules {
            if (raw.isNullOrBlank()) return HostNetworkRules.NONE
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return HostNetworkRules.NONE
            if (json.optInt("v", -1) != VERSION) return HostNetworkRules.NONE
            val array = json.optJSONArray("rules") ?: JSONArray()
            val rules = (0 until minOf(array.length(), MAX_RULES)).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val profile = (item.opt("profile") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NetworkProfileRule(
                    profileUuid = profile,
                    transport = TransportCondition.fromWire(item.opt("transport") as? String) ?: return@mapNotNull null,
                    metered = YesNoCondition.fromWire(item.opt("metered") as? String) ?: return@mapNotNull null,
                    novaVpn = YesNoCondition.fromWire(item.opt("vpn") as? String) ?: return@mapNotNull null,
                    hostConnection = HostConnectionCondition.fromWire(item.opt("host_connection") as? String)
                        ?: return@mapNotNull null,
                )
            }
            return HostNetworkRules(enabled = json.opt("enabled") == true, rules = rules)
        }
    }
}

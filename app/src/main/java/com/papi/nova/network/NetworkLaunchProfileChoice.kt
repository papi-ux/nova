package com.papi.nova.network

/**
 * Which saved settings one launch uses, and why, so Play Setup can show exactly what the launch
 * will send. [profileUuid] null means the active profile (or none), unchanged from today.
 */
data class NetworkLaunchProfileChoice(
    val profileUuid: String?,
    val source: Source,
    /** The matched rule when [source] is [Source.RULE]. */
    val match: NetworkRuleMatch? = null,
    /**
     * A copied snapshot of the effective settings, including inherited base settings and the
     * active-profile fallback. Compare values rather than hashes so edits cannot reuse an old
     * preflight, even when two settings maps happen to have the same hash.
     */
    val profileRevision: Map<String, Any?>? = null,
) {
    enum class Source {
        /** No rules, rules off, or none matched: the active profile, as before. */
        ACTIVE,
        /** The first matching rule chose a saved profile. */
        RULE,
        /** The person picked a saved profile for this launch only. */
        OVERRIDE,
        /** The person picked the active profile for this launch, past a matching rule. */
        OVERRIDE_ACTIVE,
    }

    companion object {
        /** An override value meaning "the active profile for this launch". */
        const val OVERRIDE_ACTIVE_PROFILE = ""

        /**
         * A one-launch override wins over any rule, and a rule wins over the active profile. A rule
         * naming the profile that is already active still counts as the rule's match, so the plan
         * can say which rule chose it. An override naming a missing profile is ignored.
         */
        @JvmStatic
        fun resolve(
            rules: HostNetworkRules,
            snapshot: NetworkSnapshot,
            overrideUuid: String?,
            profileExists: (String) -> Boolean,
            profileRevision: (String?) -> Map<String, Any?>? = { null },
        ): NetworkLaunchProfileChoice {
            when {
                overrideUuid == OVERRIDE_ACTIVE_PROFILE ->
                    return NetworkLaunchProfileChoice(null, Source.OVERRIDE_ACTIVE, profileRevision = snapshot(profileRevision(null)))
                overrideUuid != null && profileExists(overrideUuid) ->
                    return NetworkLaunchProfileChoice(overrideUuid, Source.OVERRIDE, profileRevision = snapshot(profileRevision(overrideUuid)))
            }
            val match = NetworkProfileRuleEngine.evaluate(rules, snapshot, profileExists)
                ?: return NetworkLaunchProfileChoice(null, Source.ACTIVE, profileRevision = snapshot(profileRevision(null)))
            val uuid = match.rule.profileUuid
            return NetworkLaunchProfileChoice(uuid, Source.RULE, match, snapshot(profileRevision(uuid)))
        }

        private fun snapshot(values: Map<String, Any?>?): Map<String, Any?>? = values?.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }

        /**
         * Whether two choices launch with the same settings: the same saved profile at the same
         * settings, including the active fallback. Null choices (rules never apply) compare with
         * each other. Which rule or reason chose it does not matter, only what would be sent.
         */
        @JvmStatic
        fun sameEffectiveProfile(a: NetworkLaunchProfileChoice?, b: NetworkLaunchProfileChoice?): Boolean =
            a?.profileUuid == b?.profileUuid && a?.profileRevision == b?.profileRevision
    }
}

/**
 * The last check before a launch leaves Play Setup. The plan on screen and the preflight the host
 * answered were both made for one effective profile; if what would launch now is a different one
 * (the network, the host's answer, a rule, a profile or the override changed), the launch stops,
 * the plan is shown again with the new profile, and Play has to be pressed again.
 */
object NetworkLaunchProfileGate {
    enum class Decision { PROCEED, PLAN_CHANGED }

    @JvmStatic
    fun decide(
        displayed: NetworkLaunchProfileChoice?,
        preflighted: NetworkLaunchProfileChoice?,
        current: NetworkLaunchProfileChoice?,
    ): Decision =
        if (NetworkLaunchProfileChoice.sameEffectiveProfile(displayed, current) &&
            NetworkLaunchProfileChoice.sameEffectiveProfile(preflighted, current)
        ) Decision.PROCEED else Decision.PLAN_CHANGED
}

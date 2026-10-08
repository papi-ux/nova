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
        ): NetworkLaunchProfileChoice {
            when {
                overrideUuid == OVERRIDE_ACTIVE_PROFILE ->
                    return NetworkLaunchProfileChoice(null, Source.OVERRIDE_ACTIVE)
                overrideUuid != null && profileExists(overrideUuid) ->
                    return NetworkLaunchProfileChoice(overrideUuid, Source.OVERRIDE)
            }
            val match = NetworkProfileRuleEngine.evaluate(rules, snapshot, profileExists)
                ?: return NetworkLaunchProfileChoice(null, Source.ACTIVE)
            return NetworkLaunchProfileChoice(match.rule.profileUuid, Source.RULE, match)
        }
    }
}

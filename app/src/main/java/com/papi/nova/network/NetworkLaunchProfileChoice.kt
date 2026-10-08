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
     * A fingerprint of [profileUuid]'s saved settings when the choice was made, so an edit to that
     * profile counts as a different effective profile. Null for the active profile, whose edits
     * apply to launches as they always have.
     */
    val profileRevision: Int? = null,
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
            profileRevision: (String) -> Int? = { null },
        ): NetworkLaunchProfileChoice {
            when {
                overrideUuid == OVERRIDE_ACTIVE_PROFILE ->
                    return NetworkLaunchProfileChoice(null, Source.OVERRIDE_ACTIVE)
                overrideUuid != null && profileExists(overrideUuid) ->
                    return NetworkLaunchProfileChoice(overrideUuid, Source.OVERRIDE, profileRevision = profileRevision(overrideUuid))
            }
            val match = NetworkProfileRuleEngine.evaluate(rules, snapshot, profileExists)
                ?: return NetworkLaunchProfileChoice(null, Source.ACTIVE)
            val uuid = match.rule.profileUuid
            return NetworkLaunchProfileChoice(uuid, Source.RULE, match, profileRevision(uuid))
        }

        /**
         * Whether two choices launch with the same settings: the same saved profile at the same
         * revision, or both the active profile. A null choice (rules never apply) is the active
         * profile. Which rule or reason chose it does not matter here, only what would be sent.
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

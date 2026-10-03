package com.papi.nova.manager

import com.papi.nova.preferences.PreferenceConfiguration

/** beta.1 keeps display precedence and only locks PyroWave or a metered request. */
object NovaTierLaunchPolicy {
    fun bitrateLocked(codec: PreferenceConfiguration.FormatOption?, space: Boolean, metered: Boolean) =
        metered || (!space && codec == PreferenceConfiguration.FormatOption.FORCE_PYROWAVE)

    // The launch callers share this decision so resume and shortcut semantics can be tested.
    fun sessionModeLocked(watchOnly: Boolean, resumeExisting: Boolean, width: Int, height: Int) =
        (watchOnly || resumeExisting) && width > 0 && height > 0

    fun needsGeneratedTier(tier: com.papi.nova.preferences.NovaTier, watchOnly: Boolean,
        resumeExisting: Boolean, space: Boolean) = !watchOnly && !resumeExisting && !space && tier != com.papi.nova.preferences.NovaTier.CUSTOM

    fun displayLocked(requested: Boolean, space: Boolean) = requested && !space
}

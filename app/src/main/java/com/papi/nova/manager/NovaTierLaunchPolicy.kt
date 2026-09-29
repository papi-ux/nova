package com.papi.nova.manager

import com.papi.nova.preferences.PreferenceConfiguration

/** beta.1 keeps display precedence and only locks PyroWave or a metered request. */
object NovaTierLaunchPolicy {
    fun bitrateLocked(codec: PreferenceConfiguration.FormatOption?, space: Boolean, metered: Boolean) =
        metered || (!space && codec == PreferenceConfiguration.FormatOption.FORCE_PYROWAVE)

    fun displayLocked(requested: Boolean, space: Boolean) = requested && !space
}

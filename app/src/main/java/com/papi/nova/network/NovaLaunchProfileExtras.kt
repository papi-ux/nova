package com.papi.nova.network

import com.papi.nova.manager.WorkerLaunchContract
import org.json.JSONObject

/**
 * Whether a launch intent's network-rule profile applies, decided in Game from the intent alone.
 * The library never sends one for these, so this is the defensive second check: watching and
 * resuming join a session already running, and a Space streams what its worker accepted, so all
 * three keep the active profile exactly as they did before rules existed.
 */
object NovaLaunchProfileExtras {
    @JvmStatic
    fun applicableProfile(
        profileUuid: String?,
        watchOnly: Boolean,
        resumeExisting: Boolean,
        appUuid: String?,
        appId: Int,
        launchOptimizationJson: String?,
    ): String? {
        if (profileUuid.isNullOrBlank() || watchOnly || resumeExisting) return null
        if (WorkerLaunchContract.isProfileApp(appUuid?.takeIf { it.isNotBlank() } ?: appId.toString())) return null
        val optimization = launchOptimizationJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (WorkerLaunchContract.parse(optimization) != null) return null
        return profileUuid
    }
}

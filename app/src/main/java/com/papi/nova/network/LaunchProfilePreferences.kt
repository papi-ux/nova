package com.papi.nova.network

import android.content.Context
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.profiles.ProfilesManager

/** Read the settings a launch and its preview use, without changing durable profile selection. */
object LaunchProfilePreferences {
    @JvmStatic
    fun read(context: Context, profileUuid: String?): PreferenceConfiguration =
        PreferenceConfiguration.readPreferences(context,
            ProfilesManager.getInstance().getOverlayingSharedPreferences(context, profileUuid))
}

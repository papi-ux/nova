package com.papi.nova

import android.app.Application
import android.widget.Toast
import com.papi.nova.profiles.ProfilesManager

class NovaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val profilesManager = ProfilesManager.getInstance()
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show()
        }
    }
}

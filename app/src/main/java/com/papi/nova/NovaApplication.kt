package com.papi.nova

import android.app.Application
import android.os.StrictMode
import android.widget.Toast
import com.papi.nova.profiles.ProfilesManager

class NovaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Debug-only: surface main-thread IO and leaked resources in logcat. Installed before
        // the ProfilesManager load below so its synchronous disk read is observed too.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }
        val profilesManager = ProfilesManager.getInstance()
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show()
        }
    }
}

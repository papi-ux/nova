package com.papi.nova

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
import android.os.StrictMode
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.papi.nova.diagnostics.NovaDiagnostics
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.ui.NovaSystemBars
import java.lang.ref.WeakReference

class NovaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Diagnostics first: a crash during startup is exactly the one worth
        // recording, and it would be missed if this were installed after the
        // work below.
        NovaDiagnostics.install(this, BuildConfig.VERSION_NAME)

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
        // Hide System Bars starts on for a device with built-in game controls; this
        // writes that default once and never replaces a choice already made.
        NovaSystemBars.seedDefault(this)
        registerActivityLifecycleCallbacks(SystemBarsOnResume)
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(SystemBarsOnResume)

        val profilesManager = ProfilesManager.getInstance()
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * A dialog, the keyboard or a settings change can leave the bars showing, so a Nova
     * screen takes the setting again each time it comes back. The stream never took
     * Nova's theme, so it is not marked and keeps its own full screen handling.
     * Settings lists the switch as instant, so a change also reaches the screen on top.
     */
    private object SystemBarsOnResume : ActivityLifecycleCallbacks, SharedPreferences.OnSharedPreferenceChangeListener {
        private var resumed: WeakReference<Activity>? = null

        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
            if (NovaSystemBars.isManaged(activity)) NovaSystemBars.apply(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            if (key != NovaSystemBars.KEY_HIDE_SYSTEM_BARS) return
            val activity = resumed?.get() ?: return
            if (NovaSystemBars.isManaged(activity)) NovaSystemBars.apply(activity)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

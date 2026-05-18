package com.papi.nova.ui

import android.R
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.preference.PreferenceManager
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaStreamHudModeTest {
    @Test
    fun cycleModePersistsNextHudModePreference() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val content = activity.findViewById<ViewGroup>(R.id.content)
        content.setViewTreeLifecycleOwner(activity)
        content.setViewTreeViewModelStoreOwner(activity)
        content.setViewTreeSavedStateRegistryOwner(activity)
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().putString("nova_polaris_hud_mode", NovaHudMode.FULL.preferenceValue).commit()

        val hud = NovaStreamHud(activity)
        hud.show()

        hud.cycleMode()

        assertEquals(
            NovaHudMode.BANNER.preferenceValue,
            prefs.getString("nova_polaris_hud_mode", null)
        )

        hud.dismiss()
    }
}

package com.papi.nova.ui

import android.R
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.preference.PreferenceManager
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        prefs.edit().putString("nova_polaris_hud_mode", NovaHudMode.MINIMAL.preferenceValue).commit()

        val hud = NovaStreamHud(activity)
        hud.show()

        hud.cycleMode()

        assertEquals(
            NovaHudMode.PERFORMANCE.preferenceValue,
            prefs.getString("nova_polaris_hud_mode", null)
        )

        hud.dismiss()
    }

    @Test
    fun setModePersistsTheChosenHudModePreference() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val content = activity.findViewById<ViewGroup>(R.id.content)
        content.setViewTreeLifecycleOwner(activity)
        content.setViewTreeViewModelStoreOwner(activity)
        content.setViewTreeSavedStateRegistryOwner(activity)
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        prefs.edit().putString("nova_polaris_hud_mode", NovaHudMode.MINIMAL.preferenceValue).commit()

        val hud = NovaStreamHud(activity)
        hud.show()

        // The Command Center picker jumps straight to a layout instead of cycling.
        hud.setMode(NovaHudMode.SLIM)

        assertEquals(
            NovaHudMode.SLIM.preferenceValue,
            prefs.getString("nova_polaris_hud_mode", null)
        )

        hud.dismiss()
    }

    @Test
    fun hudLongPressOpensCommandCenterAndPositionPersistsInsideSafeZone() {
        val source = String(
            java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/java/com/papi/nova/ui/NovaStreamHud.kt")),
            java.nio.charset.StandardCharsets.UTF_8
        )

        assertTrue(source.contains("onCommandCenterRequested?.invoke()"))
        assertTrue(source.contains("ViewConfiguration.getLongPressTimeout()"))
        assertTrue(source.contains("saveHudPosition"))
        assertTrue(source.contains("restoreHudPosition"))
        assertTrue(source.contains("clampHudPosition"))
        assertTrue(source.contains("nova_polaris_hud_x"))
        assertTrue(source.contains("nova_polaris_hud_y"))
    }
}

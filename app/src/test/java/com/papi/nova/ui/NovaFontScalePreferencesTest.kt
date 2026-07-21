package com.papi.nova.ui

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.NovaActivity
import com.papi.nova.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaFontScalePreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        Settings.System.putFloat(context.contentResolver, Settings.System.FONT_SCALE, 1.0f)
    }

    @Test
    fun defaultsToSystemRelativeOneHundredPercent() {
        assertEquals(100, NovaFontScalePreferences.readScalePercent(context))
        assertEquals(1, NovaFontScalePreferences.SCALE_STEP_PERCENT)
        assertEquals(1.15f, NovaFontScalePreferences.resolveFontScale(1.15f, 100), 0.001f)
    }

    @Test
    fun clampsStoredScaleToSupportedRange() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt(NovaFontScalePreferences.KEY_SCALE_PERCENT, 40).commit()
        assertEquals(80, NovaFontScalePreferences.readScalePercent(context))

        prefs.edit().putInt(NovaFontScalePreferences.KEY_SCALE_PERCENT, 180).commit()
        assertEquals(130, NovaFontScalePreferences.readScalePercent(context))
    }

    @Test
    fun multipliesNovaScaleByAndroidSystemScale() {
        assertEquals(0.92f, NovaFontScalePreferences.resolveFontScale(1.15f, 80), 0.001f)
        assertEquals(1.30f, NovaFontScalePreferences.resolveFontScale(1.00f, 130), 0.001f)
        assertEquals(1.69f, NovaFontScalePreferences.resolveFontScale(1.30f, 130), 0.001f)
    }

    @Test
    fun wrapsResourcesWithTheResolvedScale() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt(NovaFontScalePreferences.KEY_SCALE_PERCENT, 80).commit()

        val wrapped = NovaFontScalePreferences.wrapContext(context, systemFontScale = 1.25f)

        assertEquals(1.0f, wrapped.resources.configuration.fontScale, 0.001f)
    }

    @Test
    fun activeNovaActivityRecreatesAfterTheScalePreferenceChanges() {
        Settings.System.putFloat(context.contentResolver, Settings.System.FONT_SCALE, 1.25f)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt(NovaFontScalePreferences.KEY_SCALE_PERCENT, 80).commit()
        val controller = Robolectric.buildActivity(RecordingNovaActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(1.0f, activity.resources.configuration.fontScale, 0.001f)

        prefs.edit().putInt(NovaFontScalePreferences.KEY_SCALE_PERCENT, 130).commit()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, activity.recreateCalls)
        controller.destroy()
    }

    @Test
    fun userFacingActivitiesRouteThroughSharedScaledBase() {
        val activities = listOf(
            "src/main/java/com/papi/nova/AppView.kt",
            "src/main/java/com/papi/nova/DebugInfoActivity.kt",
            "src/main/java/com/papi/nova/EditProfileActivity.kt",
            "src/main/java/com/papi/nova/Game.kt",
            "src/main/java/com/papi/nova/HelpActivity.kt",
            "src/main/java/com/papi/nova/PcView.kt",
            "src/main/java/com/papi/nova/ProfilesActivity.kt",
            "src/main/java/com/papi/nova/ShortcutTrampoline.kt",
            "src/main/java/com/papi/nova/preferences/AddComputerManually.kt",
            "src/main/java/com/papi/nova/preferences/StreamSettings.kt",
            "src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt",
            "src/main/java/com/papi/nova/ui/NovaQrScanActivity.kt",
            "src/main/java/com/papi/nova/ui/NovaWelcomeActivity.kt",
        )
        activities.forEach { path ->
            assertTrue("$path should inherit NovaActivity", File(path).readText().contains(": NovaActivity"))
        }

        val base = File("src/main/java/com/papi/nova/NovaActivity.kt").readText()
        assertTrue(base.contains("override fun attachBaseContext"))
        assertTrue(base.contains("NovaFontScalePreferences.wrapContext"))
        assertTrue(base.contains("OnSharedPreferenceChangeListener"))
        assertTrue(base.contains("override fun onResume()"))
        assertTrue(base.contains("recreate()"))
        assertTrue(File("src/main/java/com/papi/nova/Game.kt").readText().contains("shouldRecreateForFontScaleChange(): Boolean = false"))
    }
}

private class RecordingNovaActivity : NovaActivity() {
    var recreateCalls: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }

    override fun recreate() {
        recreateCalls += 1
    }
}

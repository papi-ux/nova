package com.papi.nova.ui

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.Game
import com.papi.nova.NovaActivity
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun userFacingActivitiesRouteThroughSharedScaledBaseAtRuntime() {
        val activities = listOf(
            com.papi.nova.AppView::class.java,
            com.papi.nova.DebugInfoActivity::class.java,
            com.papi.nova.EditProfileActivity::class.java,
            Game::class.java,
            com.papi.nova.HelpActivity::class.java,
            com.papi.nova.PcView::class.java,
            com.papi.nova.ProfilesActivity::class.java,
            com.papi.nova.ShortcutTrampoline::class.java,
            com.papi.nova.preferences.AddComputerManually::class.java,
            com.papi.nova.preferences.StreamSettings::class.java,
            NovaLibraryActivity::class.java,
            NovaQrScanActivity::class.java,
            NovaWelcomeActivity::class.java,
        )
        activities.forEach { activityClass ->
            assertTrue(
                "${activityClass.name} should inherit NovaActivity",
                NovaActivity::class.java.isAssignableFrom(activityClass),
            )
        }

        val gamePolicy = Game::class.java
            .getDeclaredMethod("shouldRecreateForFontScaleChange")
            .apply { isAccessible = true }
        assertFalse(gamePolicy.invoke(Game()) as Boolean)
    }

    @Test
    fun activeOptOutNovaActivityDoesNotRecreateAfterTheScalePreferenceChanges() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt(NovaFontScalePreferences.KEY_SCALE_PERCENT, 80).commit()
        val controller = Robolectric.buildActivity(RecordingOptOutNovaActivity::class.java).setup()
        val activity = controller.get()

        prefs.edit().putInt(NovaFontScalePreferences.KEY_SCALE_PERCENT, 130).commit()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, activity.recreateCalls)
        controller.destroy()
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

private class RecordingOptOutNovaActivity : NovaActivity() {
    var recreateCalls: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }

    override fun shouldRecreateForFontScaleChange(): Boolean = false

    override fun recreate() {
        recreateCalls += 1
    }
}

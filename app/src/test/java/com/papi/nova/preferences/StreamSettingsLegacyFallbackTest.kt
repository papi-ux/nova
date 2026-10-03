package com.papi.nova.preferences

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * A row Compose Settings cannot show opens the legacy screen (audit M13). The switch said nothing:
 * the screen now names the setting it opened for and that Back returns, and B goes back to Compose
 * Settings rather than out of Settings.
 */
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class StreamSettingsLegacyFallbackTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, true)
    }

    @After
    fun tearDown() {
        NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, true)
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun aRowOnlyLegacyShowsSaysWhichAndBReturnsToCompose() {
        val settings = Robolectric.buildActivity(StreamSettings::class.java).setup().get()
        idle()
        val definition = NovaSettingDefinition(
            key = "frame_pacing", title = "Frame pacing", summary = "", categoryKey = "stream", type = NovaSettingType.Select,
        )

        StreamSettings::class.java.getDeclaredMethod("handleComposeAction", NovaSettingDefinition::class.java)
            .apply { isAccessible = true }
            .invoke(settings, definition)
        idle()

        val note = settings.findViewById<TextView>(R.id.legacyFallbackNote)
        assertEquals(View.VISIBLE, note.visibility)
        assertEquals(context.getString(R.string.nova_settings_legacy_fallback, "Frame pacing"), note.text.toString())
        assertNull("no floating notice on the way", ShadowToast.getLatestToast())

        settings.onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse("B goes back to Compose Settings, not out of Settings", settings.isFinishing)
        assertNull("Compose Settings is back", settings.findViewById<View>(R.id.legacyFallbackNote))
    }

    @Test
    fun theLegacyScreenChosenOnPurposeHasNoNote() {
        val intent = Intent(context, StreamSettings::class.java).putExtra(NovaSettingsFeatureFlags.EXTRA_FORCE_LEGACY, true)
        val settings = Robolectric.buildActivity(StreamSettings::class.java, intent).setup().get()
        idle()
        assertEquals(View.GONE, settings.findViewById<TextView>(R.id.legacyFallbackNote).visibility)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

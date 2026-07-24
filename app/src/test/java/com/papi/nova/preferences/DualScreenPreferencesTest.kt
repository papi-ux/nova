package com.papi.nova.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.utils.DualScreenQuickMenuPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
@RunWith(RobolectricTestRunner::class)
class DualScreenPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun dualScreenPreferencesKeepBackwardCompatibleDefaults() {
        val prefs = context.getSharedPreferences("dual-screen-defaults", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val config = PreferenceConfiguration.readPreferences(context, prefs)

        assertEquals(DualScreenQuickMenuPolicy.FOLLOW_INTERACTION, config.quickMenuDisplayPolicy)
        assertEquals(10, config.companionScreenDimTimeoutSeconds)
    }

    @Test
    fun dualScreenPreferencesReadQuickMenuPolicyAndCompanionDimTimeout() {
        val prefs = context.getSharedPreferences("dual-screen-custom", Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putString(
                PreferenceConfiguration.QUICK_MENU_DISPLAY_POLICY_PREF_STRING,
                DualScreenQuickMenuPolicy.COMPANION,
            )
            .putString(PreferenceConfiguration.COMPANION_SCREEN_DIM_TIMEOUT_PREF_STRING, "60")
            .commit()

        val config = PreferenceConfiguration.readPreferences(context, prefs)

        assertEquals(DualScreenQuickMenuPolicy.COMPANION, config.quickMenuDisplayPolicy)
        assertEquals(60, config.companionScreenDimTimeoutSeconds)
    }

    @Test
    fun invalidDualScreenPreferencesFailSoftToDefaults() {
        val prefs = context.getSharedPreferences("dual-screen-invalid", Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putString(PreferenceConfiguration.QUICK_MENU_DISPLAY_POLICY_PREF_STRING, "surprise")
            .putString(PreferenceConfiguration.COMPANION_SCREEN_DIM_TIMEOUT_PREF_STRING, "not-a-number")
            .commit()

        val config = PreferenceConfiguration.readPreferences(context, prefs)

        assertEquals(DualScreenQuickMenuPolicy.FOLLOW_INTERACTION, config.quickMenuDisplayPolicy)
        assertEquals(10, config.companionScreenDimTimeoutSeconds)
    }
}

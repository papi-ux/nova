package com.papi.nova.preferences

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [30], shadows = [ShadowMoonBridge::class])
@RunWith(RobolectricTestRunner::class)
class SourceTimePacingPreferenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun sourceTimePacingIsOffUnlessThePlayerTurnsItOn() {
        assertFalse(PreferenceConfiguration.readPreferences(context, prefs).sourceTimePacing)
        prefs.edit().putBoolean(PreferenceConfiguration.SOURCE_TIME_PACING_PREF_STRING, true).commit()
        assertTrue(PreferenceConfiguration.readPreferences(context, prefs).sourceTimePacing)
    }
}

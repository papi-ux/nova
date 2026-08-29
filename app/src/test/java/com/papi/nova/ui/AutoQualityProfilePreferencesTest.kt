package com.papi.nova.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class AutoQualityProfilePreferencesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun duplicateTitlesKeepIndependentCanonicalPresetChoices() {
        AutoQualityProfilePreferences.save(context, "uuid-a", "Control", "high_fps")
        AutoQualityProfilePreferences.save(context, "uuid-b", "Control", "quality")

        assertEquals("high_fps", AutoQualityProfilePreferences.load(context, "uuid-a", "Control"))
        assertEquals("quality", AutoQualityProfilePreferences.load(context, "uuid-b", "Control"))
    }

    @Test
    fun legacyNameChoiceMigratesOnceAndCannotLeakToADuplicateTitle() {
        val preferences = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("ai_profile_preference_name_Control", "stability")
            .commit()

        assertEquals("stability", AutoQualityProfilePreferences.load(context, "uuid-a", "Control"))
        assertFalse(preferences.contains("ai_profile_preference_name_Control"))
        assertEquals("auto", AutoQualityProfilePreferences.load(context, "uuid-b", "Control"))
        assertEquals("stability", AutoQualityProfilePreferences.load(context, "uuid-a", "Control"))
    }
}

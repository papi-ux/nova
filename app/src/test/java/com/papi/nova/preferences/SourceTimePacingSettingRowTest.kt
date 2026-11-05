package com.papi.nova.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [30], shadows = [ShadowMoonBridge::class])
@RunWith(RobolectricTestRunner::class)
class SourceTimePacingSettingRowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theRowIsAnOffByDefaultToggleBesideFramePacingForTheNextStream() {
        val definitions = NovaSettingDefinitions.load(context)
        val row = definitions.require(PreferenceConfiguration.SOURCE_TIME_PACING_PREF_STRING)
        assertEquals(NovaSettingType.Toggle, row.type)
        assertEquals(NovaSettingValue.BooleanValue(false), row.defaultValue)
        assertEquals("category_stream_quality", row.categoryKey)
        assertEquals(NovaSettingApplyTiming.NextStream, row.applyTiming)
        val keys = definitions.settingsForCategory("category_stream_quality").map { it.key }
        assertEquals(keys.indexOf("frame_pacing") + 1, keys.indexOf(row.key))
    }

    @Test
    fun theCopyIsPlainAndPromisesNoVariableRefresh() {
        val row = NovaSettingDefinitions.load(context).require(PreferenceConfiguration.SOURCE_TIME_PACING_PREF_STRING)
        val copy = (row.title + " " + row.summary).lowercase()
        for (claim in listOf("vrr", "variable refresh", "adaptive", "g-sync", "freesync")) {
            assertFalse("the row must not claim $claim", copy.contains(claim))
        }
        assertTrue(copy.contains("balanced"))
        assertTrue(copy.contains("next stream"))
    }

    @Test
    fun searchFindsItByWhatPlayersCallIt() {
        val definitions = NovaSettingDefinitions.load(context)
        val ui = NovaSettingsUiStateFactory.build(definitions, emptyMap(), "category_stream_quality", "frame timing")
        assertTrue(ui.visibleSettings.any { it.key == PreferenceConfiguration.SOURCE_TIME_PACING_PREF_STRING })
    }
}

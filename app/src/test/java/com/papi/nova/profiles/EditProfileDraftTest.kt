package com.papi.nova.profiles

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.EditProfileActivity
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.preferences.NovaSettingDefinition
import com.papi.nova.preferences.NovaSettingType
import com.papi.nova.preferences.NovaSettingsFeatureFlags
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/**
 * The preset editor keeps its draft (audit C05, C06, M13). Back dropped every edit with finish(), a
 * recreate rebuilt the draft from the saved preset, a failed save floated a Toast and closed the
 * editor anyway, and a row only Legacy could show opened it behind a Toast with B leaving the
 * preset.
 */
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class EditProfileDraftTest {
    private lateinit var context: Context
    private lateinit var pm: ProfilesManager
    private lateinit var profilesDir: File
    private val editors = mutableListOf<ActivityController<EditProfileActivity>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProfilesManager.instance = null
        profilesDir = File(context.filesDir, "profiles")
        profilesDir.deleteRecursively()
        pm = ProfilesManager.getInstance()
        pm.load(context)
        NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, true)
    }

    @After
    fun tearDown() {
        editors.asReversed().forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        idle()
        profilesDir.deleteRecursively()
        NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, true)
    }

    private fun saved(): SettingsProfile =
        SettingsProfile(UUID.randomUUID(), "Couch", 1L, 1L, mapOf("frame_pacing" to "latency")).also(pm::add)

    private fun editorFor(profile: SettingsProfile?): ActivityController<EditProfileActivity> {
        val intent = Intent(context, EditProfileActivity::class.java)
        profile?.let { intent.putExtra("profileUuid", it.getUuid().toString()) }
        return Robolectric.buildActivity(EditProfileActivity::class.java, intent).also(editors::add)
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun EditProfileActivity.edit(key: String, value: String) =
        getInMemoryPrefs().edit().putString(key, value).apply()

    private fun EditProfileActivity.panelTop(): NovaPage? = NovaSurfaces.existing(this)?.panel?.top

    private fun EditProfileActivity.back() {
        onBackPressedDispatcher.onBackPressed()
        idle()
    }

    private fun EditProfileActivity.save() {
        EditProfileActivity::class.java.getDeclaredMethod("saveProfile").apply { isAccessible = true }.invoke(this)
        idle()
    }

    private fun savedPacing(): Any? = pm.getProfiles().single().getOptions()!!["frame_pacing"]

    @Test
    fun backWithNothingChangedLeaves() {
        val editor = editorFor(saved()).setup().get()
        idle()
        editor.back()
        assertTrue(editor.isFinishing)
    }

    @Test
    fun diskNumbersReadAtStoredPrecisionAndRepresentationChangesDoNotBecomeEdits() {
        val original = SettingsProfile(UUID.randomUUID(), "Numeric", 1L, 1L,
            mapOf("frame_pacing" to "latency", "seekbar_bitrate_kbps" to 20000,
                "saved_timeout" to 12000L, "saved_fraction" to 0.1f))
        pm.add(original)
        assertTrue(pm.load(context)) // Gson reloads every number as a Double.
        val editor = editorFor(pm.getProfiles().single()).setup().get()
        idle()
        val prefs = editor.getInMemoryPrefs()
        assertEquals(0.1f, prefs.getFloat("saved_fraction", 1f), 0f)
        prefs.edit().putInt("seekbar_bitrate_kbps", 20000)
            .putLong("saved_timeout", 12000L).putFloat("saved_fraction", 0.1f).apply()
        editor.back()
        assertTrue("only the numeric representation changed", editor.isFinishing)
    }

    @Test
    fun aDifferentNumberStillAsksBeforeLeavingTheDraft() {
        val original = SettingsProfile(UUID.randomUUID(), "Numeric", 1L, 1L,
            mapOf("frame_pacing" to "latency", "seekbar_bitrate_kbps" to 20000))
        pm.add(original)
        assertTrue(pm.load(context))
        val editor = editorFor(pm.getProfiles().single()).setup().get()
        idle()
        editor.getInMemoryPrefs().edit().putInt("seekbar_bitrate_kbps", 25000).apply()
        editor.back()
        assertFalse(editor.isFinishing)
        assertEquals("profile-unsaved", (editor.panelTop() as NovaCommonPage.Menu).key)
    }

    @Test
    fun backWithAChangeAsksAndKeepsEveryEdit() {
        val editor = editorFor(saved()).setup().get()
        idle()
        editor.edit("frame_pacing", "balanced")

        editor.back()

        assertFalse("B no longer drops the draft", editor.isFinishing)
        val page = editor.panelTop() as NovaCommonPage.Menu
        assertEquals("profile-unsaved", page.key)
        assertEquals(listOf("save", "keep-editing", "discard"), page.items.map { it.key })
        assertTrue("Discard splits before it throws anything away", page.items.last() is NovaMenuItem.Destructive)
        assertEquals("balanced", editor.getInMemoryPrefs().getString("frame_pacing", null))
        assertEquals("the preset is unchanged until Save", "latency", savedPacing())

        (page.items.first { it.key == "save" } as NovaMenuItem.Action).onClick()
        idle()
        assertTrue(editor.isFinishing)
        assertEquals("balanced", savedPacing())
    }

    @Test
    fun discardLeavesThePresetAsItWas() {
        val editor = editorFor(saved()).setup().get()
        idle()
        editor.edit("frame_pacing", "balanced")
        editor.back()

        ((editor.panelTop() as NovaCommonPage.Menu).items.first { it.key == "discard" } as NovaMenuItem.Destructive).onConfirm()
        idle()

        assertTrue(editor.isFinishing)
        assertEquals("latency", savedPacing())
    }

    @Test
    fun theDraftSurvivesARecreate() {
        val controller = editorFor(saved()).setup()
        idle()
        controller.get().edit("frame_pacing", "balanced")

        controller.recreate()
        idle()

        assertEquals("balanced", controller.get().getInMemoryPrefs().getString("frame_pacing", null))
    }

    @Test
    fun theDraftComesBackAfterTheProcessWasStopped() {
        val profile = saved()
        val first = editorFor(profile).setup()
        idle()
        first.get().edit("frame_pacing", "balanced")
        val state = Bundle()
        first.saveInstanceState(state)
        first.pause().stop().destroy()

        val second = editorFor(profile).setup(state)
        idle()

        assertEquals("balanced", second.get().getInMemoryPrefs().getString("frame_pacing", null))
    }

    @Test
    fun aFailedSaveKeepsTheEditorEveryEditAndThePresetList() {
        val editor = editorFor(null).setup().get()
        idle()
        editor.edit("frame_pacing", "balanced")
        // A file where the presets directory should be: the save cannot be written.
        profilesDir.deleteRecursively()
        profilesDir.writeText("blocked")

        editor.save()

        assertFalse("the editor stays open", editor.isFinishing)
        assertTrue("the list has no preset the file does not have", pm.getProfiles().isEmpty())
        assertNull("nothing floats", ShadowToast.getLatestToast())
        val page = editor.panelTop() as NovaCommonPage.Notice
        assertEquals("profile-save-failed", page.key)
        assertEquals(context.getString(R.string.nova_panel_try_again), page.primary!!.label)
        assertEquals("balanced", editor.getInMemoryPrefs().getString("frame_pacing", null))

        // Try Again once the file can be written saves and closes.
        profilesDir.delete()
        page.primary!!.run()
        idle()
        assertTrue(editor.isFinishing)
        assertEquals("balanced", savedPacing())
    }

    @Test
    fun aRowOnlyLegacyShowsSaysWhichAndBReturnsToTheEditor() {
        val editor = editorFor(saved()).setup().get()
        idle()
        val definition = NovaSettingDefinition(
            key = "frame_pacing", title = "Frame pacing", summary = "", categoryKey = "stream", type = NovaSettingType.Select,
        )

        EditProfileActivity::class.java.getDeclaredMethod("handleComposeAction", NovaSettingDefinition::class.java)
            .apply { isAccessible = true }
            .invoke(editor, definition)
        idle()

        assertNull("no Toast on the way to Legacy", ShadowToast.getLatestToast())
        assertEquals(
            context.getString(R.string.nova_settings_profile_legacy_fallback, "Frame pacing"),
            editor.supportActionBar?.subtitle,
        )

        editor.back()
        assertFalse("B goes back to the row, not out of the preset", editor.isFinishing)
        assertNull("the Compose editor is back", editor.findViewById<View>(R.id.toolbar))
    }

    @Test
    fun aPresetThatIsGoneSaysSoOnAStatePageWithClose() {
        val intent = Intent(context, EditProfileActivity::class.java).putExtra("profileUuid", UUID.randomUUID().toString())
        val editor = Robolectric.buildActivity(EditProfileActivity::class.java, intent).setup().get()
        idle()

        assertNull(ShadowToast.getLatestToast())
        assertFalse(editor.isFinishing)
        val page = NovaSurfaces.existing(editor)!!.states.value.single() as NovaStatePage.Problem
        assertEquals(context.getString(R.string.profile_manager_profile_not_found), page.title)
        page.primary.run()
        assertTrue(editor.isFinishing)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

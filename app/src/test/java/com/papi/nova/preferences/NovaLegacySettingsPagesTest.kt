package com.papi.nova.preferences

import android.content.Context
import android.os.Looper
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.EditProfileActivity
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.utils.AndroidStreamDisplayTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenuItem

/**
 * The legacy settings screen stays for beta.1, and its dialogs open as pages through one
 * onDisplayPreferenceDialog override (papi's decision 2). Driven through the profile editor's
 * legacy mode, whose fragment is the same StreamSettings.SettingsFragment.
 */
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class NovaLegacySettingsPagesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProfilesManager.instance = null
        ProfilesManager.getInstance().load(context)
        NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, false)
    }

    @After
    fun tearDown() {
        NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, true)
    }

    private fun legacyEditor(block: (EditProfileActivity, StreamSettings.SettingsFragment) -> Unit) {
        val controller = Robolectric.buildActivity(EditProfileActivity::class.java)
        try {
            val activity = controller.setup().get()
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.preferences_container) as StreamSettings.SettingsFragment
            block(activity, fragment)
        } finally {
            controller.destroy()
        }
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun top(activity: EditProfileActivity): NovaPage? = NovaSurfaces.existing(activity)?.panel?.top

    @Test
    fun aListOpensAsAChoicePageOnItsCurrentValueAndPickingWritesIt() = legacyEditor { activity, fragment ->
        val pacing = fragment.findPreference<ListPreference>("frame_pacing")!!
        fragment.onDisplayPreferenceDialog(pacing)
        idle()

        @Suppress("UNCHECKED_CAST")
        val page = top(activity) as NovaCommonPage.Choice<String>
        assertEquals(pacing.value, page.current)
        assertEquals(pacing.entries.size, page.options.size)
        val other = page.options.first { it.value != pacing.value }.value
        page.onChoose(other)
        assertEquals(other, pacing.value)
    }

    @Test
    fun textOpensAsAFormPageThatRefusesABadValue() = legacyEditor { activity, fragment ->
        val bitrate = fragment.findPreference<EditTextPreference>(PreferenceConfiguration.CUSTOM_BITRATE_PREF_STRING)!!
        fragment.onDisplayPreferenceDialog(bitrate)
        idle()

        val page = top(activity) as NovaCommonPage.Form
        assertEquals(context.getString(R.string.nova_settings_warning_custom_bitrate), page.warning)
        val refused = page.onSubmit(mapOf(bitrate.key to "fast"))
        assertNotNull("a bad value stays on the page with the reason", refused)
        assertNull(page.onSubmit(mapOf(bitrate.key to "25")))
        assertEquals("25", bitrate.text)
    }

    @Test
    fun resettingControlsIsADestructiveConfirmWithKeepFocused() = legacyEditor { activity, fragment ->
        val reset = ConfirmDeleteOscPreference(fragment.requireContext()).apply {
            key = "option_reset_osc_preference"
            dialogTitle = "Reset on-screen controls"
            dialogMessage = "Your layout goes back to the default."
        }
        fragment.onDisplayPreferenceDialog(reset)
        idle()

        val page = top(activity) as NovaCommonPage.Confirm
        assertTrue(page.destructive)
        assertEquals(context.getString(R.string.nova_panel_keep), page.stayLabel)
        assertEquals(context.getString(R.string.nova_settings_reset_controls), page.actionLabel)
        assertEquals("Your layout goes back to the default.", page.message.text)
    }

    @Test
    fun theDisplayTargetOpensTheRoleComposerPage() = legacyEditor { activity, fragment ->
        val target = fragment.findPreference<ListPreference>(PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING)!!
        fragment.onDisplayPreferenceDialog(target)
        idle()

        val page = top(activity) as SettingsPage.DisplayRole
        assertEquals(context.getString(R.string.title_display_role_composer), page.title)
        page.onApply(AndroidStreamDisplayTarget.PRIMARY)
        assertEquals(AndroidStreamDisplayTarget.PRIMARY, target.value)
    }

    @Test
    fun aLegacySliderOpensAnExactSliderPage() = legacyEditor { activity, fragment ->
        val bitrate = fragment.findPreference<SeekBarPreference>(PreferenceConfiguration.BITRATE_PREF_STRING)!!
        bitrate.showDialog()
        idle()

        val page = top(activity) as NovaCommonPage.Slider
        assertEquals(500..300_000, page.range)
        assertEquals("Left and Right move by the preference's key step", 1000, page.step)
        page.onSave(25_000)
        assertEquals(25_000, fragment.getPrefs().getInt(PreferenceConfiguration.BITRATE_PREF_STRING, 0))
    }

    @Test
    fun renameIsAFormPageThatRefusesABlankName() = legacyEditor { activity, _ ->
        activity.onOptionsItemSelected(RoboMenuItem(R.id.action_rename))
        idle()

        val page = top(activity) as NovaCommonPage.Form
        assertEquals(context.getString(R.string.profile_manager_edit_profile_name), page.title)
        val key = page.fields.single().key
        assertEquals(context.getString(R.string.profile_manager_name_cannot_be_blank), page.onSubmit(mapOf(key to "  ")))
        assertNull(page.onSubmit(mapOf(key to "Couch")))
        assertTrue(activity.title.toString().contains("Couch"))
        assertNotEquals(context.getString(R.string.profile_manager_new_profile), activity.title.toString())
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

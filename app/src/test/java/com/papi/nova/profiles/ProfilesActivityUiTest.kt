package com.papi.nova.profiles

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.RadioButton
import androidx.preference.Preference
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.EditProfileActivity
import com.papi.nova.ProfilesActivity
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.preferences.NovaSettingsFeatureFlags
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class ProfilesActivityUiTest {
    private lateinit var context: Context
    private lateinit var pm: ProfilesManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProfilesManager.instance = null
        pm = ProfilesManager.getInstance()
        pm.load(context)
    }

    @Test
    fun fabLaunchesEditProfileActivity() {
        val controller = Robolectric.buildActivity(ProfilesActivity::class.java).setup()
        val activity = controller.get()

        val fab = activity.findViewById<ImageButton>(R.id.addProfileFab)
        assertNotNull(fab)
        fab.performClick()

        val next = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull("FAB should launch EditProfileActivity", next)
        assertEquals(EditProfileActivity::class.java.name, next.component!!.className)
    }

    @Test
    fun editProfileActivity_startsWithoutCrashForNewProfile() {
        val activity = Robolectric.buildActivity(EditProfileActivity::class.java).setup().get()

        assertNotNull(activity)
        assertNotNull(activity.getInMemoryPrefs())
    }

    @Test
    fun legacyProfileEditorHidesGlobalNovaTextSize() {
        NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, false)
        val controller = Robolectric.buildActivity(EditProfileActivity::class.java)
        try {
            val activity = controller.setup().get()
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager
                .findFragmentById(R.id.preferences_container) as EditProfileActivity.ProfilePreferenceFragment
            val textSize = fragment.findPreference<Preference>("nova_ui_font_scale_percent")

            assertNotNull(textSize)
            assertFalse(textSize!!.isVisible)
        } finally {
            controller.destroy()
            NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, true)
        }
    }

    @Test
    fun profileSaveStripsGlobalTextSizeInBothEditorModes() {
        try {
            for (composeEnabled in listOf(true, false)) {
                val profileId = UUID.randomUUID()
                val contaminated = SettingsProfile(
                    profileId,
                    "Contaminated",
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    mapOf(
                        "nova_ui_font_scale_percent" to 130,
                        "profile_test_marker" to "kept",
                    ),
                )
                pm.add(contaminated)
                NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, composeEnabled)
                val intent = Intent(context, EditProfileActivity::class.java)
                    .putExtra("profileUuid", profileId.toString())
                val controller = Robolectric.buildActivity(EditProfileActivity::class.java, intent)
                try {
                    val activity = controller.setup().get()
                    EditProfileActivity::class.java.getDeclaredMethod("saveProfile").apply {
                        isAccessible = true
                    }.invoke(activity)

                    val savedOptions = pm.getProfiles()
                        .single { it.getUuid() == profileId }
                        .getOptions()
                        .orEmpty()
                    assertFalse(savedOptions.containsKey("nova_ui_font_scale_percent"))
                    assertEquals("kept", savedOptions["profile_test_marker"])
                } finally {
                    controller.destroy()
                }
            }
        } finally {
            NovaSettingsFeatureFlags.setComposeSettingsEnabled(context, true)
        }
    }

    @Test
    fun radioClick_changesActiveProfile() {
        val p1 = SettingsProfile(UUID.randomUUID(), "One", System.currentTimeMillis(), System.currentTimeMillis(), null)
        val p2 = SettingsProfile(UUID.randomUUID(), "Two", System.currentTimeMillis(), System.currentTimeMillis(), null)
        pm.add(p1)
        pm.add(p2)
        pm.setActive(p1.getUuid())

        val activity = Robolectric.buildActivity(ProfilesActivity::class.java).setup().get()

        val rv = activity.findViewById<RecyclerView>(R.id.profilesRecyclerView)
        rv.layout(0, 0, 1000, 1000)
        assertEquals(2, rv.adapter!!.itemCount)

        val vh = rv.findViewHolderForAdapterPosition(1)
        assertNotNull(vh)
        val rb = vh!!.itemView.findViewById<RadioButton>(R.id.profileActive)
        assertNotNull(rb)
        rb.performClick()

        assertEquals(p2.getUuid(), pm.getActive()!!.getUuid())
    }

    @Test
    fun deleteProfile_removesRowAndUpdatesEmptyState() {
        val p = SettingsProfile(UUID.randomUUID(), "ToDelete", System.currentTimeMillis(), System.currentTimeMillis(), null)
        pm.add(p)

        val activity = Robolectric.buildActivity(ProfilesActivity::class.java).setup().get()

        val rv = activity.findViewById<RecyclerView>(R.id.profilesRecyclerView)
        rv.layout(0, 0, 1000, 1000)
        val vh = rv.findViewHolderForAdapterPosition(0)
        assertNotNull(vh)
        val deleteBtn = vh!!.itemView.findViewById<ImageButton>(R.id.deleteProfile)
        deleteBtn.performClick()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, rv.adapter!!.itemCount)
        assertTrue(pm.getProfiles().isEmpty())

        rv.layout(0, 0, 1000, 1000)
        assertEquals(View.GONE, rv.visibility)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

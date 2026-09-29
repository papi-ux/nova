package com.papi.nova.profiles

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.view.ViewCompat
import android.widget.ImageButton
import android.widget.FrameLayout
import androidx.preference.Preference
import androidx.compose.ui.platform.ComposeView
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
    // Robolectric 4.16 no longer supplies Android21/22. The wrapper uses FrameLayout's
    // API21 foreground contract; run the focus behavior on its oldest supplied SDK too.
    @Config(sdk = [23, 33])
    fun focusedFabUsesCompatibilityWrapperWithoutLosingChildFocus() {
        val controller = Robolectric.buildActivity(ProfilesActivity::class.java).setup()
        try {
            val fab = controller.get().findViewById<ImageButton>(R.id.addProfileFab)
            assertTrue("foreground belongs to the API21-compatible wrapper", fab.parent is FrameLayout)
            val frame = fab.parent as FrameLayout
            assertNotNull(frame.foreground)
            assertTrue(fab.requestFocus())
            frame.refreshDrawableState()
            assertTrue("child focus reaches the visible ring", frame.drawableState.contains(android.R.attr.state_focused))
            assertTrue(frame.foreground.isStateful)
        } finally {
            controller.destroy()
        }
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

    // The preset in use was a radio button, a separate focus stop that toggled on its own. It is
    // the one current mark now (R9), and the row itself is what A or a tap acts on.
    @Test
    fun rowClick_changesActiveProfileAndOnlyThatRowCarriesTheCheck() {
        val p1 = SettingsProfile(UUID.randomUUID(), "One", System.currentTimeMillis(), System.currentTimeMillis(), null)
        val p2 = SettingsProfile(UUID.randomUUID(), "Two", System.currentTimeMillis(), System.currentTimeMillis(), null)
        pm.add(p1)
        pm.add(p2)
        pm.setActive(p1.getUuid())

        val activity = Robolectric.buildActivity(ProfilesActivity::class.java).setup().get()

        val rv = activity.findViewById<RecyclerView>(R.id.profilesRecyclerView)
        rv.layout(0, 0, 1000, 1000)
        assertEquals(2, rv.adapter!!.itemCount)

        val current = rv.findViewHolderForAdapterPosition(0)!!.itemView
        val other = rv.findViewHolderForAdapterPosition(1)!!.itemView
        assertEquals(View.VISIBLE, current.findViewById<View>(R.id.profileCurrent).visibility)
        assertEquals(activity.getString(R.string.nova_panel_current), ViewCompat.getStateDescription(current))
        assertTrue(current.isSelected)
        assertEquals(View.GONE, other.findViewById<View>(R.id.profileCurrent).visibility)
        assertNull(ViewCompat.getStateDescription(other))
        assertTrue("the row is a focus stop, so A reaches it", other.isFocusable)

        other.performClick()

        assertEquals(p2.getUuid(), pm.getActive()!!.getUuid())
        // The mark moving is the answer: "Activated preset" floated over the list (audit X2).
        assertNull("no Toast floats", org.robolectric.shadows.ShadowToast.getLatestToast())

        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val exactly = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        rv.measure(exactly, exactly)
        rv.layout(0, 0, 1000, 1000)
        val nowCurrent = rv.findViewHolderForAdapterPosition(1)!!.itemView
        assertEquals("the mark moved to the row pressed", View.VISIBLE, nowCurrent.findViewById<View>(R.id.profileCurrent).visibility)
        nowCurrent.performClick()
        assertNull("the same row again stops using it", pm.getActive())
        assertNull("still no Toast", org.robolectric.shadows.ShadowToast.getLatestToast())
    }

    // Delete was a stock AlertDialog; it is a split confirm in the row now, driven through its
    // controller keys in ProfilesDeleteSplitComposeTest. Here: the row carries it in place of the
    // old trash button, and the screen answers A and B through the key gate.
    @Test
    fun deleteIsASplitConfirmInTheRow() {
        val p = SettingsProfile(UUID.randomUUID(), "ToDelete", System.currentTimeMillis(), System.currentTimeMillis(), null)
        pm.add(p)

        val activity = Robolectric.buildActivity(ProfilesActivity::class.java).setup().get()

        val rv = activity.findViewById<RecyclerView>(R.id.profilesRecyclerView)
        rv.layout(0, 0, 1000, 1000)
        val vh = rv.findViewHolderForAdapterPosition(0)
        assertNotNull(vh)
        val delete = vh!!.itemView.findViewById<View>(R.id.deleteProfile)
        assertTrue("Delete is drawn by Compose as a split confirm", delete is ComposeView)
        assertNull("no stock dialog is raised for Delete", ShadowAlertDialog.getLatestAlertDialog())
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

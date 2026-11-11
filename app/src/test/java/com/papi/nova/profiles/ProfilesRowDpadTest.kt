package com.papi.nova.profiles

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.EditProfileActivity
import com.papi.nova.ProfilesActivity
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.shadows.ShadowGameManager
import com.papi.nova.shadows.ShadowMoonBridge
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A saved preset's Edit and Delete by D-pad (#478). On the RP6, Right and Left on a preset's row
 * changed nothing, so with a controller a preset could be made but not edited or deleted. The row
 * is itself the focus stop that A acts on (it makes the preset the one in use), and Edit and Delete
 * stand inside it: a search to the right from a row that spans the list finds nothing past its
 * edge.
 *
 * Keys go as the window takes them: to the focused view first, and where it leaves an arrow, to
 * the focus search ViewRootImpl runs from it. That is the route a D-pad press takes on a device;
 * this runner does not run ViewRootImpl's own key stage.
 */
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class ProfilesRowDpadTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun withPreset(name: String, block: (ActivityScenario<ProfilesActivity>, ProfilesManager) -> Unit) {
        ProfilesManager.instance = null
        val profiles = ProfilesManager.getInstance().apply { load(context) }
        val now = System.currentTimeMillis()
        profiles.add(SettingsProfile(UUID.randomUUID(), name, now, now, null))
        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            rule.waitForIdle()
            block(scenario, profiles)
        }
    }

    private fun Activity.row(): View {
        val holder = findViewById<RecyclerView>(R.id.profilesRecyclerView).findViewHolderForAdapterPosition(0)
        assertNotNull("the preset is listed", holder)
        return holder!!.itemView
    }

    private fun Activity.focused(): View? = window.decorView.findFocus()

    private fun direction(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
        else -> null
    }

    /** One press of [keyCode], down and up, as the window takes it. */
    private fun press(scenario: ActivityScenario<ProfilesActivity>, keyCode: Int) {
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val focused = activity.focused() ?: error("nothing has focus before $keyCode")
            if (!root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))) {
                direction(keyCode)?.let { d -> focused.focusSearch(d)?.takeIf { it !== focused }?.requestFocus(d) }
            }
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.waitForIdle()
    }

    private fun focusTheRow(scenario: ActivityScenario<ProfilesActivity>) {
        scenario.onActivity { activity -> assertTrue("the row takes focus", activity.row().requestFocus()) }
        rule.waitForIdle()
    }

    /** [expected] is the focused view itself, or, for Delete, holds the focus inside its Compose content. */
    private fun assertFocusIs(scenario: ActivityScenario<ProfilesActivity>, what: String, expected: (Activity) -> View) {
        scenario.onActivity { activity ->
            val focused = activity.focused()
            val target = expected(activity)
            val holds = if (target.id == R.id.deleteProfile) target.hasFocus() else focused === target
            assertTrue("$what has focus, not $focused", holds)
        }
    }

    private val edit: (Activity) -> View = { it.row().findViewById(R.id.editProfile) }
    private val delete: (Activity) -> View = { it.row().findViewById(R.id.deleteProfile) }
    private val row: (Activity) -> View = { it.row() }

    @Test
    fun rightFromTheRowReachesEditThenDeleteAndLeftComesBackToTheRow() = withPreset("W8 test") { scenario, _ ->
        focusTheRow(scenario)
        press(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocusIs(scenario, "Edit", edit)
        press(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocusIs(scenario, "Delete", delete)
        press(scenario, KeyEvent.KEYCODE_DPAD_LEFT)
        assertFocusIs(scenario, "Edit", edit)
        press(scenario, KeyEvent.KEYCODE_DPAD_LEFT)
        assertFocusIs(scenario, "the row", row)
    }

    @Test
    fun editShowsTheFocusRingWhenItHasFocus() = withPreset("W8 test") { scenario, _ ->
        focusTheRow(scenario)
        press(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
        scenario.onActivity { activity ->
            val button = edit(activity)
            assertTrue(button.isFocused)
            val ring = button.foreground
            assertNotNull("Edit carries the focus ring", ring)
            assertTrue("the ring answers focus", ring!!.isStateful && ring.state.contains(android.R.attr.state_focused))
        }
    }

    @Test
    fun aOnEditOpensTheEditor() = withPreset("W8 test") { scenario, _ ->
        focusTheRow(scenario)
        press(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocusIs(scenario, "Edit", edit)
        press(scenario, KeyEvent.KEYCODE_DPAD_CENTER)
        scenario.onActivity { activity ->
            val next = shadowOf(activity).nextStartedActivity
            assertNotNull("A on Edit opens the editor", next)
            assertEquals(EditProfileActivity::class.java.name, next.component!!.className)
        }
    }

    /** Delete keeps its confirmation: A arms Keep and Delete, and nothing is deleted. */
    @Test
    fun aOnDeleteAsksFirstAndDeletesNothing() = withPreset("W8 test") { scenario, profiles ->
        focusTheRow(scenario)
        press(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
        press(scenario, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocusIs(scenario, "Delete", delete)
        press(scenario, KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText(context.getString(R.string.nova_panel_keep)).assertExists()
        rule.onNodeWithText(context.getString(R.string.nova_profiles_delete_consequence, "W8 test")).assertExists()
        assertEquals("one A deletes nothing", 1, profiles.getProfiles().size)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

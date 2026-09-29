package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.profiles.SettingsProfile
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The Compatibility App List: the preset floated as a button over the tiles and cut the last row
 * (audit M16), and its error card asked Retry for focus while the card was still gone, then took a
 * grey focus box itself (audit C08).
 */
@Config(sdk = [33], shadows = [com.papi.nova.shadows.ShadowMoonBridge::class, com.papi.nova.shadows.ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class AppViewPresetAndErrorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("GlPreferences", 0).edit()
            .putString("Renderer", "TestRenderer")
            .putString("Fingerprint", Build.FINGERPRINT)
            .commit()
        Shadows.shadowOf(context as Application).setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        Shadows.shadowOf(context).setComponentNameAndServiceForBindService(
            ComponentName(context, ComputerManagerService::class.java),
            mock(ComputerManagerService.ComputerManagerBinder::class.java),
        )
        ProfilesManager.instance = null
        File(context.filesDir, "profiles").deleteRecursively()
        ProfilesManager.getInstance().load(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "profiles").deleteRecursively()
    }

    private fun open(): AppView {
        val intent = Intent(context, AppView::class.java)
            .putExtra(AppView.UUID_EXTRA, "test-uuid")
            .putExtra(AppView.NAME_EXTRA, "Test PC")
        return Robolectric.buildActivity(AppView::class.java, intent).setup().get().also { idle() }
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun thePresetIsAButtonInTheHeaderNotOverTheTiles() {
        val list = open()
        val button = list.findViewById<TextView>(R.id.profilesButton)
        val header = list.findViewById<ViewGroup>(R.id.appListHeader)
        assertTrue("the preset button stands in the header", isInside(button, header))
        assertEquals(context.getString(R.string.profile_manager_choose_profile), button.text.toString())
        assertFalse(
            "nothing floats over the tiles",
            File("src/main/res/layout/activity_app_view.xml").readText().contains("FloatingActionButton"),
        )
    }

    @Test
    fun thePresetButtonNamesThePresetInUse() {
        val preset = SettingsProfile(UUID.randomUUID(), "Couch", 1L, 1L, null)
        ProfilesManager.getInstance().add(preset)
        ProfilesManager.getInstance().setActive(preset.getUuid())

        val list = open()

        assertEquals(
            context.getString(R.string.profile_manager_preset_name, "Couch"),
            list.findViewById<TextView>(R.id.profilesButton).text.toString(),
        )
    }

    @Test
    fun anErrorPutsFocusOnRetryAndTheCardTakesNoneItself() {
        val list = open()
        AppView::class.java.getDeclaredMethod("showAppListError", String::class.java)
            .apply { isAccessible = true }
            .invoke(list, "boom")
        idle()

        val card = list.findViewById<View>(R.id.appListErrorCard)
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse("the card is not a focus stop with a grey box of its own", card.isFocusable)
        assertTrue("Retry holds focus", list.findViewById<View>(R.id.appListRetryButton).hasFocus())
    }

    private fun isInside(view: View, parent: ViewGroup): Boolean {
        var current = view.parent
        while (current != null) {
            if (current === parent) return true
            current = current.parent
        }
        return false
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

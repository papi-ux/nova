package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPageExit
import com.papi.nova.ui.panel.NovaSurfaces
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowDialog

/** The real Home picker callback; Android's eventual input focus is a separate device check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h600dp-land", shadows = [
    com.papi.nova.shadows.ShadowMoonBridge::class,
    com.papi.nova.shadows.ShadowGameManager::class,
    PcViewThemeWindowLifecycleTest.ThemeRecreateShadow::class,
])
class PcViewThemeWindowLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<PcView>? = null

    @Before fun prepare() {
        ThemeRecreateShadow.windowsShowingAtRecreate.clear()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("welcome_seen", true).commit()
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_OLED)
        context.getSharedPreferences("GlPreferences", Context.MODE_PRIVATE).edit()
            .putString("Renderer", "ThemeLifecycleFixture")
            .putString("Fingerprint", Build.FINGERPRINT).commit()
        shadowOf(context as Application).setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        shadowOf(context).setComponentNameAndServiceForBindService(
            ComponentName(context, ComputerManagerService::class.java),
            mock(ComputerManagerService.ComputerManagerBinder::class.java),
        )
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun openPicker(): Pair<NovaSurfaces, NovaCommonPage.Choice<String>> {
        val activity = Robolectric.buildActivity(PcView::class.java).create().start().resume().visible()
            .also { controller = it }.get()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.findViewById<View>(R.id.actionTheme).performClick())
        val surfaces = NovaSurfaces.of(activity)
        @Suppress("UNCHECKED_CAST")
        val picker = surfaces.panel.top as NovaCommonPage.Choice<String>
        assertEquals("theme", picker.key)
        assertTrue("the actual Home picker owns a shown dialog", ShadowDialog.getLatestDialog().isShowing)
        return surfaces to picker
    }

    private fun choose(surfaces: NovaSurfaces, picker: NovaCommonPage.Choice<String>, theme: String) {
        // ChoicePage uses this actual exit object: close state first, invoke the owner's callback
        // immediately, while the window's exit animation has not yet reached onWindowIdle.
        NovaPageExit(mayAct = { surfaces.panel.isOpen }, answer = {}, leave = surfaces.panel::close)
            .leaveThen { picker.onChoose(theme) }
    }

    @Test fun changedThemeClosesOwnedPickerBeforeRecreatingHome() {
        val (surfaces, picker) = openPicker()
        val window = ShadowDialog.getLatestDialog()
        choose(surfaces, picker, NovaThemeManager.THEME_DIRECTOR)

        assertEquals(NovaThemeManager.THEME_DIRECTOR, NovaThemeManager.getTheme(context))
        assertEquals("one accepted choice requests one recreate", 1, ThemeRecreateShadow.windowsShowingAtRecreate.size)
        assertFalse("recreation must not begin with the exiting picker still owning a window",
            ThemeRecreateShadow.windowsShowingAtRecreate.single())
        assertFalse("the old picker is dismissed before the Home window is replaced", window.isShowing)
    }

    @Test fun choosingCurrentThemeDoesNotRecreateOrRewriteIt() {
        val (surfaces, picker) = openPicker()
        val before = PreferenceManager.getDefaultSharedPreferences(context).all
        choose(surfaces, picker, NovaThemeManager.THEME_OLED)
        assertTrue(ThemeRecreateShadow.windowsShowingAtRecreate.isEmpty())
        assertEquals(before, PreferenceManager.getDefaultSharedPreferences(context).all)
        assertFalse(surfaces.panel.isOpen)
        surfaces.onWindowIdle()
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
    }

    @Test fun closingPickerWithoutChoosingDoesNotApplyOrRecreate() {
        val (surfaces, _) = openPicker()
        surfaces.panel.close()
        surfaces.onWindowIdle()
        assertEquals(NovaThemeManager.THEME_OLED, NovaThemeManager.getTheme(context))
        assertTrue(ThemeRecreateShadow.windowsShowingAtRecreate.isEmpty())
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
    }

    @Implements(PcView::class)
    class ThemeRecreateShadow : ShadowActivity() {
        @Implementation override fun recreate() {
            // Inspect the real callback boundary without modeling Android's WindowManager focus.
            windowsShowingAtRecreate += ShadowDialog.getLatestDialog()?.isShowing == true
        }
        companion object {
            val windowsShowingAtRecreate = mutableListOf<Boolean>()
        }
    }
}

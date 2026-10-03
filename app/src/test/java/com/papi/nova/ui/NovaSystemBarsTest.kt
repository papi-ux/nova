package com.papi.nova.ui

import android.app.Activity
import android.content.Context
import android.view.InputDevice
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaSystemBarsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gamepad = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD

    @Before
    fun clearPrefs() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun onlyAControllerThatIsPartOfTheDeviceCountsAsBuiltIn() {
        // A Retroid's own controls: internal, real, a gamepad.
        assertTrue(NovaSystemBars.hasBuiltInGamepad(listOf(NovaSystemBars.InputProbe(gamepad, external = false, virtual = false))))
        // A phone with a paired controller is still a phone.
        assertFalse(NovaSystemBars.hasBuiltInGamepad(listOf(NovaSystemBars.InputProbe(gamepad, external = true, virtual = false))))
        // A virtual device an app created is not hardware.
        assertFalse(NovaSystemBars.hasBuiltInGamepad(listOf(NovaSystemBars.InputProbe(gamepad, external = false, virtual = true))))
        // The volume and power keys are internal, but they are not a gamepad.
        assertFalse(
            NovaSystemBars.hasBuiltInGamepad(
                listOf(NovaSystemBars.InputProbe(InputDevice.SOURCE_KEYBOARD, external = false, virtual = false)),
            ),
        )
        assertFalse(NovaSystemBars.hasBuiltInGamepad(emptyList()))
    }

    @Test
    fun retroidAndOtherHandheldMakersCountEvenThoughTheirControlsLookPaired() {
        // What a Retroid Pocket Nova reports (Android 13): its controls are an external
        // "Xbox Wireless Controller", so only the maker identifies it as a handheld.
        assertTrue(NovaSystemBars.isKnownHandheld("Moorechip", "Retroid Pocket Nova"))
        assertTrue(NovaSystemBars.isKnownHandheld("", "Retroid Pocket 6"))
        assertTrue(NovaSystemBars.isKnownHandheld("AYN", "Odin2"))
        assertTrue(NovaSystemBars.isKnownHandheld("ANBERNIC", "RG556"))
        assertFalse(NovaSystemBars.isKnownHandheld("Google", "Pixel 10 Pro"))
        assertFalse(NovaSystemBars.isKnownHandheld("samsung", "SM-X910"))
        assertTrue(NovaSystemBars.defaultHidden(television = false, builtInGamepad = false, knownHandheld = true))
    }

    @Test
    fun defaultsOnForAHandheldAndNeverOnATelevision() {
        assertTrue(NovaSystemBars.defaultHidden(television = false, builtInGamepad = true))
        assertFalse(NovaSystemBars.defaultHidden(television = false, builtInGamepad = false))
        assertFalse("a TV has no bars to hide and its remote is not a handheld", NovaSystemBars.defaultHidden(television = true, builtInGamepad = true))
        assertFalse(NovaSystemBars.defaultHidden(television = true, builtInGamepad = false, knownHandheld = true))
    }

    @Test
    fun theDefaultIsWrittenOnceAndAChoiceIsNeverReplaced() {
        NovaSystemBars.seedDefault(context, hiddenByDefault = true)
        assertTrue(NovaSystemBars.isHidden(context))

        // The owner turns it off; a later start must not turn it back on.
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(NovaSystemBars.KEY_HIDE_SYSTEM_BARS, false).commit()
        NovaSystemBars.seedDefault(context, hiddenByDefault = true)
        assertFalse(NovaSystemBars.isHidden(context))
    }

    @Test
    fun aThemedScreenHidesTheBarsAndLetsASwipeBringThemBack() {
        NovaSystemBars.seedDefault(context, hiddenByDefault = true)
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        NovaThemeManager.applyTheme(activity)
        controller.setup()

        assertTrue("a screen that took Nova's theme is managed", NovaSystemBars.isManaged(activity))
        assertTrue("the setting is on, so the screen hid its bars", activity.window.decorView.getTag(R.id.nova_system_bars_hidden) == true)
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).systemBarsBehavior,
        )
        controller.destroy()
    }

    @Test
    fun turnedOffItShowsAgainOnlyTheBarsAScreenHid() {
        NovaSystemBars.seedDefault(context, hiddenByDefault = false)
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        NovaThemeManager.applyTheme(activity)
        controller.setup()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val hidBars = { activity.window.decorView.getTag(R.id.nova_system_bars_hidden) == true }
        assertFalse("with the setting off, a themed screen's bars are never touched", hidBars())

        prefs.edit().putBoolean(NovaSystemBars.KEY_HIDE_SYSTEM_BARS, true).commit()
        NovaSystemBars.apply(activity)
        assertTrue(hidBars())

        prefs.edit().putBoolean(NovaSystemBars.KEY_HIDE_SYSTEM_BARS, false).commit()
        NovaSystemBars.apply(activity)
        assertFalse("turned off, the bars the screen hid come back", hidBars())
        controller.destroy()
    }

    @Test
    fun panelsKeepTheBarsHiddenToo() {
        fun source(path: String) = String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)
        // A host's Pair menu, Library Options and Nova Text Size each brought the status and navigation
        // bars back over a screen that had hidden them, because each was a sheet or a dialog in a window
        // of its own. Each is a page in NovaPanelWindow now, NovaPanelSourceGuardTest keeps any other
        // window from coming back, and NovaDialogWindow, which adopted a Compose dialog's window, went
        // with the last Compose dialog.
        assertFalse(
            "no Compose dialog is left to adopt a window of its own",
            source("src/main/java/com/papi/nova/ui/NovaDialogWindows.kt").contains("fun NovaDialogWindow(")
        )
        // Both dialogs that were built outside the sheet chrome and adopted their own window are
        // gone: PcView's OTP pairing dialog is a Form page in NovaPanelWindow, and legacy lists
        // open there as Choice pages instead of NovaListPreferenceDialogFragment. NovaPanelWindow
        // adopts its window (asserted below).
        // UiHelper's confirms and SpinnerDialog's waits no longer build a dialog of their own: they
        // open in NovaPanelWindow, which is the one window that has to adopt.
        assertTrue(
            "NovaPanelWindow adopts its window, so the bars stay hidden over a screen that hid them",
            source("src/main/java/com/papi/nova/ui/panel/NovaPanelWindow.kt").contains("NovaDialogWindows.adopt(placement.context, window)"),
        )
        assertTrue(
            "UiHelper's confirms present on the screen's NovaSurfaces, so they open in NovaPanelWindow",
            source("src/main/java/com/papi/nova/utils/UiHelper.kt").contains("NovaSurfaces.of(parent).present("),
        )
        assertTrue(
            "SpinnerDialog shows on the screen's NovaSurfaces, so its wait opens in NovaPanelWindow",
            source("src/main/java/com/papi/nova/utils/SpinnerDialog.kt").contains("NovaSurfaces.of(activity).show("),
        )
    }

    @Test
    fun theStreamsDialogsHideTheBarsWhateverTheSetting() {
        NovaSystemBars.seedDefault(context, hiddenByDefault = false)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        assertFalse(NovaSystemBars.isStream(activity))
        NovaSystemBars.markStream(activity)
        assertTrue(NovaSystemBars.isStream(activity))
        assertFalse("the stream is not one of Nova's themed screens", NovaSystemBars.isManaged(activity))
        controller.destroy()

        fun source(path: String) = String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)
        assertTrue(
            "End Session showed the clock, the battery and the navigation bar over the game: the stream marks itself",
            source("src/main/java/com/papi/nova/Game.kt").contains("NovaSystemBars.markStream(this)")
        )
        val windows = source("src/main/java/com/papi/nova/ui/NovaDialogWindows.kt")
        val stream = windows.indexOf("NovaSystemBars.isStream(host)")
        val managed = windows.indexOf("NovaSystemBars.isManaged(host)")
        assertTrue(
            "a dialog over the stream hides the bars whatever the setting says, before the themed-screen check turns it away",
            stream in 0 until managed && windows.contains("NovaSystemBars.applyToStreamDialog(window)")
        )
    }

    @Test
    fun aScreenThatNeverTookNovasThemeIsLeftAlone() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        assertFalse("the stream never takes Nova's theme, so it keeps its own full screen", NovaSystemBars.isManaged(controller.get()))
        controller.destroy()
    }

    @Test
    fun theAppSeedsTheDefaultAndReappliesOnResume() {
        val app = String(Files.readAllBytes(Path.of("src/main/java/com/papi/nova/NovaApplication.kt")), StandardCharsets.UTF_8)
        assertTrue(app.contains("NovaSystemBars.seedDefault(this)"))
        assertTrue(app.contains("registerActivityLifecycleCallbacks(SystemBarsOnResume)"))
        assertTrue(app.contains("if (NovaSystemBars.isManaged(activity)) NovaSystemBars.apply(activity)"))
        assertTrue(
            "Settings calls the switch instant, so a change reaches the screen on top",
            app.contains(".registerOnSharedPreferenceChangeListener(SystemBarsOnResume)") &&
                app.contains("if (key != NovaSystemBars.KEY_HIDE_SYSTEM_BARS) return"),
        )
        val prefs = String(Files.readAllBytes(Path.of("src/main/res/xml/preferences.xml")), StandardCharsets.UTF_8)
        assertTrue("the setting sits in the Nova section", prefs.contains("android:key=\"nova_hide_system_bars\""))
    }
}

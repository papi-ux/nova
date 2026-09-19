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
    fun sheetsAndDialogsKeepTheBarsHiddenToo() {
        val chrome = String(Files.readAllBytes(Path.of("src/main/java/com/papi/nova/ui/NovaSheetChrome.kt")), StandardCharsets.UTF_8)
        assertEquals(
            "a host's Pair menu brought the status and navigation bars back over a screen that had hidden them; " +
                "the bottom sheet and both alert chromes pass their window to NovaSystemBars",
            3,
            Regex("NovaDialogWindows\\.adopt\\(context, window\\)").findAll(chrome).count(),
        )
    }

    @Test
    fun composeDialogsKeepTheBarsHiddenToo() {
        fun source(path: String) = String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)
        val library = source("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val dialogs = Regex("\\bDialog\\(\\n|ModalBottomSheet\\(\\n").findAll(library).count()
        assertEquals(
            "Library Options brought the navigation bar back: every Compose dialog and sheet in the library calls NovaDialogWindow",
            dialogs,
            Regex("NovaDialogWindow\\(\\)").findAll(library).count(),
        )
        val settings = source("src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt")
        assertEquals(
            "Nova Text Size and the other settings dialogs brought both bars back; every dialog in Modern Settings calls NovaDialogWindow",
            Regex("\\b(Alert)?Dialog\\(\\n").findAll(settings).count(),
            Regex("NovaDialogWindow\\(\\)").findAll(settings).count(),
        )
        for (path in listOf(
            "src/main/java/com/papi/nova/utils/UiHelper.kt",
            "src/main/java/com/papi/nova/utils/SpinnerDialog.kt",
            "src/main/java/com/papi/nova/PcView.kt",
            "src/main/java/com/papi/nova/preferences/NovaListPreferenceDialogFragment.kt",
        )) {
            assertTrue("$path builds a shared dialog outside the sheet chrome and adopts its window", source(path).contains("NovaDialogWindows.adopt("))
        }
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

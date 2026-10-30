package com.papi.nova.ui

import android.content.Context
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.preferences.NovaSettingDefinition
import com.papi.nova.preferences.NovaSettingType
import com.papi.nova.preferences.NovaSettingValue
import com.papi.nova.preferences.NovaSettingsUiState
import com.papi.nova.preferences.THEME_KEY
import com.papi.nova.preferences.valueLabel
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rules behind Nova's hidden things (#424). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaEasterEggsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before @After fun clearTheSky() {
        while (NovaSkyQuiet.active) NovaSkyQuiet.streamEnded()
    }

    @Test fun codesAreReadHoweverTheyWereTyped() {
        assertEquals(NovaEasterEggs.Code.SUPERNOVA, NovaEasterEggs.codeFor("SUPERNOVA"))
        assertEquals(NovaEasterEggs.Code.SUPERNOVA, NovaEasterEggs.codeFor("  supernova\n"))
        assertEquals(NovaEasterEggs.Code.LEONIDS, NovaEasterEggs.codeFor("Leonids"))
        assertEquals(NovaEasterEggs.Code.POLARIS, NovaEasterEggs.codeFor("polaris"))
    }

    @Test fun anythingElseIsNoCode() {
        listOf(null, "", "   ", "SUPER NOVA", "SUPERNOVA!", "NOVA").forEach { assertNull(it, NovaEasterEggs.codeFor(it)) }
    }

    @Test fun meteorsFallOnTheThreePeaksAndTheirDaysOnly() {
        val peaks = setOf(8 to 12, 8 to 13, 11 to 17, 11 to 18, 12 to 13, 12 to 14)
        for (month in 1..12) for (day in 1..31) {
            assertEquals("$month/$day", (month to day) in peaks, NovaEasterEggs.isMeteorNight(month, day))
        }
    }

    @Test fun theNightComesFromTheLocalDate() {
        assertTrue(NovaEasterEggs.isMeteorNight(GregorianCalendar(2026, Calendar.NOVEMBER, 17)))
        assertFalse(NovaEasterEggs.isMeteorNight(GregorianCalendar(2026, Calendar.NOVEMBER, 19)))
    }

    @Test fun theVersionLineOpensOnTheFifthPressInARow() {
        val taps = NovaVersionLineTaps()
        repeat(4) { assertFalse(taps.press(1_000L + it * 500L)) }
        assertTrue(taps.press(3_000L))
        // And it starts over, rather than opening on every press after.
        assertFalse(taps.press(3_100L))
    }

    @Test fun aLongPauseStartsTheCountAgain() {
        val taps = NovaVersionLineTaps()
        repeat(4) { assertFalse(taps.press(1_000L + it * 100L)) }
        assertFalse(taps.press(10_000L))
        repeat(3) { assertFalse(taps.press(10_100L + it * 100L)) }
        assertTrue(taps.press(10_500L))
    }

    @Test fun onlyAHostSeenOfflineComesOnline() {
        val hosts = NovaHostsComingOnline()
        assertEquals("Nova's first look isn't a host coming online", emptySet<String>(), hosts.update(mapOf("a" to true, "b" to null)))
        assertEquals(emptySet<String>(), hosts.update(mapOf("a" to false, "b" to true)))
        assertEquals(setOf("a"), hosts.update(mapOf("a" to true, "b" to true)))
        assertEquals("once", emptySet<String>(), hosts.update(mapOf("a" to true, "b" to true)))
    }

    @Test fun aHostGoneFromTheListIsForgotten() {
        val hosts = NovaHostsComingOnline()
        hosts.update(mapOf("a" to false))
        hosts.update(emptyMap())
        assertEquals(emptySet<String>(), hosts.update(mapOf("a" to true)))
    }

    @Test fun anUnknownStateDoesNotUndoHavingBeenSeenOffline() {
        val hosts = NovaHostsComingOnline()
        hosts.update(mapOf("a" to false))
        hosts.update(mapOf("a" to null))
        assertEquals(setOf("a"), hosts.update(mapOf("a" to true)))
    }

    @Test fun theDoctorRemarksOnlyUnderTwoMilliseconds() {
        assertTrue(NovaEasterEggs.fasterThanLight(1))
        assertFalse(NovaEasterEggs.fasterThanLight(2))
        assertFalse(NovaEasterEggs.fasterThanLight(40))
        assertFalse("0 is no estimate yet", NovaEasterEggs.fasterThanLight(0))
        assertFalse(NovaEasterEggs.fasterThanLight(null))
    }

    @Test fun theNorthStarIsOffUntilAskedForAndComesBackOff() {
        assertFalse(NovaEasterEggs.northStarEnabled(context))
        assertTrue(NovaEasterEggs.toggleNorthStar(context))
        assertTrue(NovaEasterEggs.northStarEnabled(context))
        assertFalse(NovaEasterEggs.toggleNorthStar(context))
    }

    // Aurora stays the default, and the hidden theme is opt-in.

    private val listed get() = context.resources.getStringArray(R.array.nova_theme_values).toList()

    @Test fun unlockingOffersTheThemeWithoutChoosingIt() {
        assertEquals(NovaThemeManager.THEME_POLARIS, NovaThemeManager.getTheme(context))
        assertFalse(NovaThemeManager.THEME_SUPERNOVA in NovaThemeManager.pickableThemes(context, listed))

        NovaEasterEggs.unlockSupernovaTheme(context)

        assertEquals("still Aurora", NovaThemeManager.THEME_POLARIS, NovaThemeManager.getTheme(context))
        assertEquals(NovaThemeManager.THEME_SUPERNOVA, NovaThemeManager.pickableThemes(context, listed).last())
    }

    @Test fun theShippedThemeListNeverNamesIt() {
        assertFalse(NovaThemeManager.THEME_SUPERNOVA in listed)
        assertFalse(
            context.getString(R.string.nova_theme_supernova_label) in
                context.resources.getStringArray(R.array.nova_theme_names),
        )
    }

    @Test fun theCycleNeverLandsOnIt() {
        NovaEasterEggs.unlockSupernovaTheme(context)
        val seen = List(listed.size + 2) { NovaThemeManager.cycleTheme(context) }
        assertFalse(NovaThemeManager.THEME_SUPERNOVA in seen)
    }

    @Test fun chosenItPersistsAndReadsAsItself() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_SUPERNOVA)
        assertEquals(NovaThemeManager.THEME_SUPERNOVA, NovaThemeManager.getTheme(context))
        assertEquals("Supernova", NovaThemeManager.getThemeLabel(context))
        assertTrue("a list always shows the current theme", NovaThemeManager.THEME_SUPERNOVA in NovaThemeManager.pickableThemes(context, listed))
    }

    @Test fun itsColoursAreItsOwnAndReadable() {
        NovaThemeManager.setTheme(context, NovaThemeManager.THEME_SUPERNOVA)
        val window = NovaThemeManager.getWindowBackgroundColor(context)
        assertEquals(context.getColor(R.color.nova_supernova_bg_window), window)
        assertEquals(context.getColor(R.color.nova_supernova_accent), NovaThemeManager.getAccentColor(context))
        listOf(
            NovaThemeManager.getTextPrimaryColor(context),
            NovaThemeManager.getTextSecondaryColor(context),
            NovaThemeManager.getTextMutedColor(context),
            NovaThemeManager.getAccentColor(context),
        ).forEach { ink ->
            assertTrue(ColorUtils.calculateContrast(ColorUtils.setAlphaComponent(ink, 0xFF), window) >= 4.5)
        }
    }

    @Test fun settingsNamesItRatherThanItsKey() {
        val theme = NovaSettingDefinition(
            key = THEME_KEY,
            title = "Theme",
            summary = "",
            categoryKey = "appearance",
            type = NovaSettingType.Select,
        )
        val state = NovaSettingsUiState(
            categories = emptyList(),
            selectedCategoryKey = "appearance",
            searchQuery = "",
            searchResultCount = 0,
            quickSettings = emptyList(),
            visibleSettings = listOf(theme),
            values = mapOf(THEME_KEY to NovaSettingValue.StringValue(NovaThemeManager.THEME_SUPERNOVA)),
        )
        assertEquals("Supernova", state.valueLabel(context, theme))
    }

    @Test fun polarisIsNotToldAThemeItLacks() {
        assertNull(NovaHostConsoleHandoff.polarisTheme(NovaThemeManager.THEME_SUPERNOVA))
    }

    /** "Nothing is shared or sent": no network reach from anywhere in the eggs' own code. */
    @Test fun nothingHereCanSendAnything() {
        val source = File("src/main/java/com/papi/nova/ui/NovaEasterEggs.kt").readText()
        listOf("okhttp", "java.net", "PolarisApiClient", "NvHTTP", "NovaDiagnostics", "LimeLog").forEach {
            assertFalse(it, source.contains(it))
        }
    }
}

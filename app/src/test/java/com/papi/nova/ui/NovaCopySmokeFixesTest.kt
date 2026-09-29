package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaPanelWidth
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Leftover upstream copy, dashes and text marks the 2026-09-29 smoke test found. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCopySmokeFixesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun noGfeNoOtherStuffAndOneExperimentalCase() {
        for (id in listOf(R.string.error_404, R.string.summary_checkbox_enable_sops, R.string.summary_checkbox_force_qwerty)) {
            assertFalse(context.getString(id), context.getString(id).contains("GFE"))
        }
        assertFalse(context.getString(R.string.summary_debug_info).contains("other stuff"))
        assertTrue(context.getString(R.string.title_checkbox_ultra_low_latency).endsWith("(Experimental)"))
        assertFalse(context.getString(R.string.summary_seekbar_metered_bitrate).contains("¼"))
    }

    @Test
    fun theSystemPanelUsesWordsNotTextMarks() {
        assertEquals("Matrix", context.getString(R.string.nova_system_menu_matrix))
        assertEquals("Sponsor", context.getString(R.string.nova_system_menu_sponsor))
        assertFalse(context.getString(R.string.nova_system_menu_host_named_format).contains("•"))
    }

    @Test
    fun settingsSummariesLiveInResourcesWithoutDashes() {
        val xml = File("src/main/res/xml/preferences.xml").readText()
        assertFalse("no em or en dash in a summary", Regex("android:(summary|title)=\"[^\"@]*[—–]").containsMatchIn(xml))
        assertFalse(xml.contains("Give up after N failures"))
        assertTrue(context.getString(R.string.summary_nova_gyro_aim).isNotBlank())
    }

    @Test
    fun commandCenterRowsAreInTitleCase() {
        for (id in listOf(
            R.string.nova_cc_mouse_mode,
            R.string.nova_cc_android_keyboard,
            R.string.nova_cc_zoom,
            R.string.nova_cc_fetch_clipboard,
            R.string.nova_cc_controller_mouse,
        )) {
            val words = context.getString(id).split(' ').filter { it.length > 3 }
            assertTrue(context.getString(id), words.all { it.first().isUpperCase() })
        }
        assertEquals("Meta", context.getString(R.string.game_menu_send_keys_win))
    }

    @Test
    fun aPushedCommandCenterPageKeepsTheRootsWidth() {
        assertEquals(NovaPanelWidth.Wide, CommandCenterPage.Keys("Keys", emptyList()).width)
        assertEquals(NovaPanelWidth.Wide, CommandCenterPage.MoreControls("More Controls", emptyList()).width)
    }

    @Test
    fun addServerWaitsForAToOpenItsKeyboard() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val addServer = manifest.substringAfter("android:name=\".preferences.AddComputerManually\"").substringBefore("</activity>")
        assertFalse(addServer.contains("stateVisible"))
    }
}

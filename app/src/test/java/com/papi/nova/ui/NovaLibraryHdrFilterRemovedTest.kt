package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The HDR filter and HDR First are gone until Polaris knows HDR per title (N14, papi's call).
 * Polaris sets hdr_supported for every app from the host's HEVC Main10 mode, so the filter
 * matched every title, launchers included, and the sort changed nothing. A library saved on
 * either comes back on the whole library in its own order.
 */
@RunWith(RobolectricTestRunner::class)
class NovaLibraryHdrFilterRemovedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun freshPrefs(name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun neitherTheFilterNorTheSortIsOfferedAnyMore() {
        assertFalse(NovaLibraryPrimaryFilter.entries.any { it.name == "HDR" })
        assertFalse(NovaLibrarySortMode.entries.any { it.name == "HDR_FIRST" })
    }

    @Test
    fun aLibrarySavedOnTheHdrFilterComesBackOnTheWholeLibrary() {
        val prefs = freshPrefs("nova-library-hdr-filter-saved")
        prefs.edit().putString("nova_library_filter_primary", "HDR").commit()

        assertEquals(NovaLibraryFilterState(), NovaLibraryPreferences.loadFilterState(prefs))
    }

    @Test
    fun aLibrarySavedOnHdrFirstComesBackInLibraryOrder() {
        val prefs = freshPrefs("nova-library-hdr-first-saved")
        prefs.edit().putString("nova_library_sort_mode", "HDR_FIRST").commit()

        assertEquals(NovaLibrarySortMode.LIBRARY_ORDER, NovaLibraryPreferences.loadOptions(prefs).sortMode)
    }

    @Test
    fun theFilterCountsNoLongerCountHdr() {
        val strings = File("src/main/res/values/strings_ui_library.xml").readText()
        val counts = strings.substringAfter("name=\"nova_library_panel_filter_counts\">").substringBefore("</string>")
        assertFalse("every title counted as HDR", counts.contains("HDR"))
    }
}

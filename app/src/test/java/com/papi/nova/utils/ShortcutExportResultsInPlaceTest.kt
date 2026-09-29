package com.papi.nova.utils

import android.os.Looper
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaSurfaces
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Export Launcher File on the App list floated each of its errors, and its success, in a snackbar
 * that was gone in seconds (audit X2). An export that could not finish is a Notice in the App
 * list's edge panel; one that worked needs nothing more than the save sheet closing on Save.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class ShortcutExportResultsInPlaceTest {
    private lateinit var controller: ActivityController<ResultsTestActivity>
    private lateinit var activity: ResultsTestActivity

    @Before
    fun start() {
        controller = Robolectric.buildActivity(ResultsTestActivity::class.java)
        activity = controller.get().apply { setTheme(R.style.AppTheme) }
        controller.setup()
    }

    @After
    fun finish() {
        ShortcutHelper.artFileContentToExport = null
        controller.pause().stop().destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun topNotice(): NovaCommonPage.Notice {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val top = NovaSurfaces.of(activity).panel.top
        assertTrue("expected a Notice page on top, found $top", top is NovaCommonPage.Notice)
        return top as NovaCommonPage.Notice
    }

    @Test
    fun aSaveWithNoPlaceChosenIsANotice() {
        ShortcutHelper.artFileContentToExport = "[host_uuid] host\n"
        ShortcutHelper.writeArtFileToUri(activity, null)

        val notice = topNotice()
        assertEquals(activity.getString(R.string.applist_menu_export_launcher), notice.title)
        assertEquals(activity.getString(R.string.file_export_failed_no_location_selected), notice.message)
    }

    @Test
    fun aSaveWithNothingToWriteIsANotice() {
        ShortcutHelper.artFileContentToExport = null
        ShortcutHelper.writeArtFileToUri(activity, android.net.Uri.parse("content://nova.test/export.art"))

        assertEquals(activity.getString(R.string.file_export_failed_no_content_to_write), topNotice().message)
    }

    @Test
    fun theExportFloatsNothing() {
        val source = File("src/main/java/com/papi/nova/utils/ShortcutHelper.kt").readText()
        assertFalse("say export results on a Notice, or not at all when the save worked", source.contains("NovaSnackbar"))
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

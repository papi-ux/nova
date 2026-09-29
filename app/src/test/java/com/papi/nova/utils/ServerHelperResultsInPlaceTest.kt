package com.papi.nova.utils

import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.papi.nova.R
import com.papi.nova.TestLogSuppressor
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaSurfaces
import java.io.IOException
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * A start on an offline host, and every quit, floated its result as a Toast over whichever screen
 * asked, and a quit floated "Quitting" before it (audit X2). A refusal is a Notice in that screen's
 * right edge panel now; a quit that worked is shown by the caller's own list as it refreshes.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class ServerHelperResultsInPlaceTest {
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
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun topNotice(): NovaCommonPage.Notice {
        val top = NovaSurfaces.of(activity).panel.top
        assertTrue("expected a Notice page on top, found $top", top is NovaCommonPage.Notice)
        return top as NovaCommonPage.Notice
    }

    @Test
    fun aStartOnAnOfflineHostIsANoticeInTheEdgePanel() {
        val computer = ComputerDetails().apply { state = ComputerDetails.State.OFFLINE }

        ServerHelper.doStart(activity, NvApp("Control"), computer, mock(ComputerManagerService.ComputerManagerBinder::class.java), false)
        idle()

        val notice = topNotice()
        assertEquals(activity.getString(R.string.hosts_offline_title), notice.title)
        assertEquals(activity.getString(R.string.hosts_offline_message), notice.message)
        assertNull("nothing floats", ShadowToast.getLatestToast())
    }

    @Test
    fun aRefusedQuitIsANoticeWithTheHostsReasonAndNothingFloats() {
        val http = mock(NvHTTP::class.java)
        `when`(http.getServerInfo(true)).thenThrow(IOException("The host is busy"))
        var completed = false
        var failed = false

        ServerHelper.doQuit(activity, http, "Control", { completed = true }, { failed = true })
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!failed && System.nanoTime() < deadline) {
            Thread.sleep(10)
            idle()
        }

        assertTrue("the fail callback runs, on the main thread", failed)
        assertFalse(completed)
        val notice = topNotice()
        assertEquals(activity.getString(R.string.nova_library_end_failed), notice.title)
        assertEquals("The host is busy", notice.message)
        assertNull("no Quitting Toast, and no result Toast", ShadowToast.getLatestToast())
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

internal class ResultsTestActivity : AppCompatActivity()

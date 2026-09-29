package com.papi.nova

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.ui.panel.NovaProblemBack
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Help on a TV (audit C07): its loading page had no Cancel, so it swallowed A and B, and a page
 * that failed to load had no state of its own.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class HelpActivityLoadStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun open(): Pair<HelpActivity, WebView> {
        val intent = Intent(context, HelpActivity::class.java).setData(Uri.parse(URL))
        val help = Robolectric.buildActivity(HelpActivity::class.java, intent).setup().get()
        val web = help.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as WebView
        return help to web
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private fun HelpActivity.states(): List<NovaStatePage> = NovaSurfaces.existing(this)?.states?.value.orEmpty()

    private fun request(mainFrame: Boolean): WebResourceRequest =
        mock(WebResourceRequest::class.java).also { `when`(it.isForMainFrame).thenReturn(mainFrame) }

    @Test
    fun theLoadingPageHasACancelThatLeavesHelp() {
        val (help, web) = open()
        Shadows.shadowOf(web).webViewClient.onPageStarted(web, URL, null)
        idle()

        val busy = help.states().single() as NovaStatePage.Busy
        assertNotNull("Cancel is there, so A and B are not swallowed", busy.cancel)
        busy.cancel!!.run()
        assertTrue(help.isFinishing)
    }

    @Test
    fun aPageThatDoesNotLoadSaysSoWithTryAgainAndClose() {
        val (help, web) = open()
        val client = Shadows.shadowOf(web).webViewClient
        client.onPageStarted(web, URL, null)
        client.onReceivedError(web, request(mainFrame = true), mock(WebResourceError::class.java))
        client.onPageFinished(web, URL)
        idle()

        val problem = help.states().single() as NovaStatePage.Problem
        assertEquals(context.getString(R.string.nova_panel_try_again), problem.primary.label)
        assertTrue("B closes Help", problem.back is NovaProblemBack.Close)
        problem.back.action!!.run()
        assertTrue(help.isFinishing)
    }

    @Test
    fun aPartOfThePageThatFailsLeavesThePageAlone() {
        val (help, web) = open()
        val client = Shadows.shadowOf(web).webViewClient
        client.onPageStarted(web, URL, null)
        client.onReceivedError(web, request(mainFrame = false), mock(WebResourceError::class.java))
        client.onPageFinished(web, URL)
        idle()

        assertTrue(help.states().isEmpty())
    }

    private companion object {
        const val URL = "https://papi-ux.com/docs/troubleshooting/"
    }
}

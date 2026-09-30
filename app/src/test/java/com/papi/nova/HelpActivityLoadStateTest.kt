package com.papi.nova

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Looper
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.ui.panel.NovaProblemBack
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
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

    private fun request(mainFrame: Boolean, url: String? = null): WebResourceRequest =
        mock(WebResourceRequest::class.java).also {
            `when`(it.isForMainFrame).thenReturn(mainFrame)
            if (url != null) `when`(it.url).thenReturn(Uri.parse(url))
        }

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

    // B on a later page's loading or failed page left Help, when a page to go back to was there
    // (review of C07): B goes back one step, and leaves Help only from the first page.
    @Test
    fun cancellingALaterPagesLoadGoesBackAPage() {
        val (help, web) = open()
        onASecondPage(web)
        Shadows.shadowOf(web).webViewClient.onPageStarted(web, NEXT, null)
        idle()

        (help.states().single() as NovaStatePage.Busy).cancel!!.run()
        idle()
        assertFalse("Help stays open", help.isFinishing)
        assertEquals("one page back", 1, Shadows.shadowOf(web).goBackInvocations)
    }

    @Test
    fun aLaterPageThatFailsGoesBackAPageOnB() {
        val (help, web) = open()
        val client = Shadows.shadowOf(web).webViewClient
        onASecondPage(web)
        client.onPageStarted(web, NEXT, null)
        client.onReceivedError(web, request(mainFrame = true, url = NEXT), mock(WebResourceError::class.java))
        client.onPageFinished(web, NEXT)
        idle()

        val problem = help.states().single() as NovaStatePage.Problem
        assertEquals(context.getString(R.string.nova_panel_back), problem.back.action!!.label)
        problem.back.action!!.run()
        idle()
        assertFalse("Help stays open", help.isFinishing)
        assertEquals("one page back", 1, Shadows.shadowOf(web).goBackInvocations)
        assertTrue("the failed page is gone", help.states().none { it is NovaStatePage.Problem })
    }

    // The certificate branch compared the error's address with the page that had started, but a
    // page's certificate error arrives before it starts, so the screen stayed blank (review of C07).
    @Test
    fun aCertificateErrorOnThePageBeforeItStartsSaysItDidNotLoad() {
        val (help, web) = open()
        val handler = mock(SslErrorHandler::class.java)
        Shadows.shadowOf(web).webViewClient.onReceivedSslError(web, handler, sslError(URL))
        idle()

        verify(handler).cancel()
        val problem = help.states().single() as NovaStatePage.Problem
        assertEquals(context.getString(R.string.help_load_failed_title), problem.title)
    }

    @Test
    fun aCertificateErrorOnALinkFollowedBeforeItStartsSaysItDidNotLoad() {
        val (help, web) = open()
        val client = Shadows.shadowOf(web).webViewClient
        client.onPageStarted(web, URL, null)
        client.onPageFinished(web, URL)
        idle()
        val link = mock(WebResourceRequest::class.java).also {
            `when`(it.isForMainFrame).thenReturn(true)
            `when`(it.url).thenReturn(Uri.parse(NEXT))
        }
        assertFalse("a link to an https page is followed", client.shouldOverrideUrlLoading(web, link))
        client.onReceivedSslError(web, mock(SslErrorHandler::class.java), sslError(NEXT))
        idle()

        // The link never replaced the page on screen, so B there is back on that page.
        val problem = help.states().single() as NovaStatePage.Problem
        problem.back.action!!.run()
        idle()
        assertFalse("Help stays open", help.isFinishing)
        assertEquals("on the page it was on", 0, Shadows.shadowOf(web).goBackInvocations)
        assertTrue(help.states().isEmpty())
    }

    @Test
    fun aCertificateErrorOnAPartOfThePageLeavesThePageAlone() {
        val (help, web) = open()
        val client = Shadows.shadowOf(web).webViewClient
        client.onPageStarted(web, URL, null)
        val handler = mock(SslErrorHandler::class.java)
        client.onReceivedSslError(web, handler, sslError("https://cdn.example.com/picture.png"))
        client.onPageFinished(web, URL)
        idle()

        verify(handler).cancel()
        assertTrue(help.states().isEmpty())
    }

    /** The first page read, and a link followed from it, which is on screen now. */
    private fun onASecondPage(web: WebView) {
        Shadows.shadowOf(web).pushEntryToHistory(URL)
        Shadows.shadowOf(web).pushEntryToHistory(NEXT)
    }

    private fun sslError(url: String): SslError = SslError(SslError.SSL_UNTRUSTED, mock(SslCertificate::class.java), url)

    private companion object {
        const val URL = "https://papi-ux.com/docs/troubleshooting/"
        const val NEXT = "https://papi-ux.com/docs/pairing/"
    }
}

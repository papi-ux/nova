package com.papi.nova.ui

import android.net.http.SslCertificate
import android.net.http.SslError
import android.net.Uri
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.papi.nova.ui.panel.NovaPanelWidth
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host's console page trusts exactly the certificate Nova paired with, on the console's own
 * address, and nothing else (N6): not a system authority, and never a warning to click through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostConsoleTest {
    private val console = "https://10.0.0.232:47990"
    private val paired = certificate(PAIRED)
    private val other = certificate(OTHER)
    private val page = NovaHostConsolePage(title = "Host Console", url = console, pinnedCertificate = paired.encoded)
    private val states = mutableListOf<NovaHostConsoleState>()
    private val client = NovaHostConsoleClient(page, onState = { states += it }, onHistory = {})
    private val view: WebView = mock(WebView::class.java)

    @Test
    fun onlyThePairedCertificateIsTrusted() {
        assertTrue(NovaHostConsoleTrust.isPinned(paired.encoded, paired.encoded.copyOf()))
        assertFalse(NovaHostConsoleTrust.isPinned(paired.encoded, other.encoded))
        assertFalse("no pin trusts nothing", NovaHostConsoleTrust.isPinned(null, paired.encoded))
        assertFalse(NovaHostConsoleTrust.isPinned(paired.encoded, null))
    }

    @Test
    fun onlyTheConsolesOwnAddressIsTheConsole() {
        assertTrue(NovaHostConsoleTrust.isConsole(console, "$console/"))
        assertTrue("a path and a tab in its hash", NovaHostConsoleTrust.isConsole(console, "$console/#/config#av"))
        assertFalse("another port is another server", NovaHostConsoleTrust.isConsole(console, "https://10.0.0.232:47984/"))
        assertFalse("another host", NovaHostConsoleTrust.isConsole(console, "https://10.0.0.233:47990/"))
        assertFalse("never plain http", NovaHostConsoleTrust.isConsole(console, "http://10.0.0.232:47990/"))
        assertFalse(NovaHostConsoleTrust.isConsole(console, "https://github.com/papi-ux/polaris"))
        assertFalse(NovaHostConsoleTrust.isConsole(console, null))
        assertTrue("443 is https's own port", NovaHostConsoleTrust.isConsole("https://host.lan", "https://host.lan:443/"))
    }

    @Test
    fun theConsoleIsAWidePage() {
        assertEquals(NovaPanelWidth.Wide, page.width)
        assertEquals(NovaHostConsolePage.KEY, page.key)
    }

    @Test
    fun aCertificateErrorIsLetThroughOnlyForThePinOnTheConsole() {
        val handler = mock(SslErrorHandler::class.java)
        client.onReceivedSslError(view, handler, SslError(SslError.SSL_UNTRUSTED, SslCertificate(paired), "$console/"))
        verify(handler).proceed()
        verify(handler, never()).cancel()
        assertTrue(states.isEmpty())
    }

    @Test
    fun anotherCertificateOnTheConsoleIsRefusedWithNoWayPast() {
        val handler = mock(SslErrorHandler::class.java)
        client.onReceivedSslError(view, handler, SslError(SslError.SSL_UNTRUSTED, SslCertificate(other), "$console/"))
        verify(handler).cancel()
        verify(handler, never()).proceed()
        verify(view).stopLoading()
        assertEquals(listOf(NovaHostConsoleState.Refused), states)

        // Once refused, not even the pin is let through on this view.
        val later = mock(SslErrorHandler::class.java)
        client.onReceivedSslError(view, later, SslError(SslError.SSL_UNTRUSTED, SslCertificate(paired), "$console/"))
        verify(later).cancel()
        verify(later, never()).proceed()
    }

    @Test
    fun thePinIsNotTrustedAwayFromTheConsole() {
        val handler = mock(SslErrorHandler::class.java)
        client.onReceivedSslError(view, handler, SslError(SslError.SSL_UNTRUSTED, SslCertificate(paired), "https://10.0.0.232:47984/"))
        verify(handler).cancel()
        verify(handler, never()).proceed()
        assertTrue("a part of the page elsewhere failing leaves the console", states.isEmpty())
    }

    @Test
    fun nothingButTheConsoleLoads() {
        fun request(url: String): WebResourceRequest = mock(WebResourceRequest::class.java).also {
            `when`(it.url).thenReturn(Uri.parse(url))
        }
        assertFalse(client.shouldOverrideUrlLoading(view, request("$console/#/apps")))
        assertTrue(client.shouldOverrideUrlLoading(view, request("https://github.com/papi-ux/polaris")))
        assertTrue(client.shouldOverrideUrlLoading(view, request("http://10.0.0.232:47990/")))
    }

    @Test
    fun aPageShowsOnlyWithThePairedCertificateEvenOneTheSystemTrusts() {
        `when`(view.certificate).thenReturn(SslCertificate(other))
        client.onPageFinished(view, "$console/")
        assertEquals(listOf(NovaHostConsoleState.Refused), states)
        verify(view, never()).visibility = View.VISIBLE

        val shown = mutableListOf<NovaHostConsoleState>()
        val fresh = NovaHostConsoleClient(page, onState = { shown += it }, onHistory = {})
        val good: WebView = mock(WebView::class.java)
        `when`(good.certificate).thenReturn(SslCertificate(paired))
        fresh.onPageFinished(good, "$console/")
        assertEquals(listOf(NovaHostConsoleState.Showing), shown)
        verify(good).visibility = View.VISIBLE
    }

    @Test
    fun aPageWithNoCertificateIsNotShown() {
        client.onPageFinished(view, "$console/")
        assertEquals(listOf(NovaHostConsoleState.Refused), states)
    }

    @Test
    fun onlyThePageItselfFailingIsAFailure() {
        val part = mock(WebResourceRequest::class.java)
        `when`(part.isForMainFrame).thenReturn(false)
        client.onReceivedError(view, part, mock(android.webkit.WebResourceError::class.java))
        assertTrue(states.isEmpty())
        val main = mock(WebResourceRequest::class.java)
        `when`(main.isForMainFrame).thenReturn(true)
        client.onReceivedError(view, main, mock(android.webkit.WebResourceError::class.java))
        assertEquals(listOf(NovaHostConsoleState.Failed), states)
    }

    private companion object {
        // Two throwaway self-signed certificates: one stands for the pairing certificate.
        const val PAIRED = "MIIBiTCCAS+gAwIBAgIUIaJczDVRt5Z9RKON0U4n9BbaCNwwCgYIKoZIzj0EAwIwGTEXMBUGA1UEAwwOUG9sYXJpcyBUZXN0IGEwIBcNMjYwOTI5MTM1NDI1WhgPMjEyNjA5MDUxMzU0MjVaMBkxFzAVBgNVBAMMDlBvbGFyaXMgVGVzdCBhMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXRPHahtNgHSCLnzZBtTMcVVHi+o8B5LBm+LN5+Cl72UV1y+/IamxK1Bs54tXHOOetsEJpJPghSnox8OnV3aJgaNTMFEwHQYDVR0OBBYEFHUI2KOP97cCMD5D1P+YUHJvWqMjMB8GA1UdIwQYMBaAFHUI2KOP97cCMD5D1P+YUHJvWqMjMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgMe7TiHRUIe8yaffDMGWeX6mtD3f3D7Yg2Li8tO4lpisCIQD1XJYkg4XtP2u6SdA2cPdPy7gq0GiN+5wrhro0cQR2Qw=="
        const val OTHER = "MIIBiTCCAS+gAwIBAgIUHjK84mg7MHzJNegbTSfgtUavebIwCgYIKoZIzj0EAwIwGTEXMBUGA1UEAwwOUG9sYXJpcyBUZXN0IGIwIBcNMjYwOTI5MTM1NDI1WhgPMjEyNjA5MDUxMzU0MjVaMBkxFzAVBgNVBAMMDlBvbGFyaXMgVGVzdCBiMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsE0Du7yY13/h0I+SIcAXjOJUd4hk5FyykHzyK9JE2yJClDCJdv47SHHTUzC0U+0c+jywmTGSROdM4lwktmD1nKNTMFEwHQYDVR0OBBYEFJZd9EVYDp6eoulQ28pMhbomE29CMB8GA1UdIwQYMBaAFJZd9EVYDp6eoulQ28pMhbomE29CMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAKj4DQDmr/5GwL4ycbLwrWLHcJE9eDtLPXHTh6m7BtORAiBHoxkFCzkvNCWs8HrSmX2JG+BfznULaEmdv+r1MnHj9Q=="

        fun certificate(base64: String): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64))) as X509Certificate
    }
}

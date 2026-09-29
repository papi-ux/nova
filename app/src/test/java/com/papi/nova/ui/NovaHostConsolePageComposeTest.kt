package com.papi.nova.ui

import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The host's console as a page in the panel (N6), driven where the page draws it.
 *
 * A web view with no chrome client answers every alert, confirm and prompt No, so the console's
 * Unpair, Delete, Launch and Close did nothing in Nova's Host Console. The console's questions are
 * asked in its own page now, and the player's answer goes back to the console.
 *
 * B stepped back through the console's history forever on a first visit that had signed in: the
 * login sits one entry back, and a signed-in console sends it straight on again. B steps back only
 * to another page of the console, and leaves from its first page, from before its login, and from
 * a page it was sent straight back to.
 *
 * A host Nova has not paired with had no Host Console at all. It opens on a page that says Nova
 * cannot check the host, with Back focused, and Open Anyway trusts, for that visit only, the
 * certificate the host presents first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaHostConsolePageComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val console = "https://10.0.0.232:47990"
    private val paired = certificate(PAIRED)
    private val other = certificate(OTHER)
    private lateinit var panel: NovaPanelState

    private fun page(pin: ByteArray?, onLink: ((NovaHostConsoleLink) -> String?)? = null) =
        NovaHostConsolePage(title = "Host Console", url = console, pinnedCertificate = pin, hostUuid = HOST, onLink = onLink)

    private fun request(url: String): WebResourceRequest = mock(WebResourceRequest::class.java).also {
        `when`(it.url).thenReturn(Uri.parse(url))
        `when`(it.isForMainFrame).thenReturn(true)
    }

    /** The console on screen, its certificate checked. */
    private fun shown(web: WebView) = onUi { web.webViewClient.onPageFinished(web, "$console/#/") }

    /** The console pushed over the host's menu, as the host menu pushes it. */
    private fun show(page: NovaHostConsolePage, certificateOf: (WebView) -> SslCertificate? = { SslCertificate(paired) }): NovaTestKeys {
        panel = NovaPanelState().apply {
            open(NovaCommonPage.Menu(key = "host", title = "Host", items = emptyList()))
            push(page)
        }
        val keys = rule.setPanelContent {
            NovaPageStackHost(state = panel) { shown ->
                if (shown is NovaHostConsolePage) NovaHostConsole(shown, certificateOf)
            }
        }
        rule.waitForIdle()
        return keys
    }

    private fun webView(): WebView? {
        fun find(view: View): WebView? = when (view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return find(rule.activity.window.decorView)
    }

    // A button, not the hint bar's word for the same key.
    private fun button(text: String) = rule.onNode(hasText(text) and hasClickAction())

    private fun onUi(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.waitForIdle()
    }

    @Test
    fun aConfirmFromTheConsoleIsAskedInItsPageAndEitherAnswerReachesTheConsole() {
        val keys = show(page(paired.encoded))
        val web = webView()!!
        val chrome = shadowOf(web).webChromeClient
        assertNotNull("the console's questions have a chrome client to answer them", chrome)

        // OK: Right from Cancel, then A.
        val unpair = mock(JsResult::class.java)
        onUi { assertTrue("Nova answers it, no default dialog", chrome!!.onJsConfirm(web, console, "Unpair every client?", unpair)) }
        rule.onNodeWithText("Unpair every client?").assertIsDisplayed()
        button("Cancel").assertIsFocused()
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.CENTER)
        verify(unpair).confirm()
        verify(unpair, never()).cancel()
        rule.onNodeWithText("Unpair every client?").assertDoesNotExist()

        // Cancel: A where focus starts.
        val delete = mock(JsResult::class.java)
        onUi { chrome!!.onJsConfirm(web, console, "Delete this app?", delete) }
        button("Cancel").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        verify(delete).cancel()
        verify(delete, never()).confirm()

        // B answers Cancel too, one step: the console page stays.
        val close = mock(JsResult::class.java)
        onUi { chrome!!.onJsConfirm(web, console, "Close this app?", close) }
        keys.back()
        verify(close).cancel()
        verify(close, never()).confirm()
        assertTrue("B took the question away, not the console", panel.top is NovaHostConsolePage)
    }

    @Test
    fun anAlertAndAPromptAreAskedInThePageToo() {
        val keys = show(page(paired.encoded))
        val web = webView()!!
        val chrome = shadowOf(web).webChromeClient!!

        val alert = mock(JsResult::class.java)
        onUi { assertTrue(chrome.onJsAlert(web, console, "Saved. Restart the host to apply it.", alert)) }
        button("OK").assertIsFocused()
        keys.press(NovaTestKeys.CENTER)
        verify(alert).confirm()

        val prompt = mock(JsPromptResult::class.java)
        onUi { assertTrue(chrome.onJsPrompt(web, console, "Name this client", "Retroid Pocket 6", prompt)) }
        rule.onNodeWithText("Name this client").assertIsDisplayed()
        button("OK").assertExists()
        keys.back()
        verify(prompt).cancel()
        verify(prompt, never()).confirm("Retroid Pocket 6")
    }

    @Test
    fun aQuestionStillOpenWhenTheConsoleLeavesIsAnsweredSoItsScriptNeverWaits() {
        show(page(paired.encoded))
        val web = webView()!!
        val waiting = mock(JsResult::class.java)
        onUi { shadowOf(web).webChromeClient!!.onJsConfirm(web, console, "Unpair every client?", waiting) }
        // The console leaves the stack, and its view with it.
        onUi { panel.pop() }
        verify(waiting).cancel()
    }

    @Test
    fun backNeverStepsIntoTheLoginASignedInConsoleSendsOnFrom() {
        val keys = show(page(paired.encoded))
        val web = webView()!!
        val client = web.webViewClient
        // A first visit that had to sign in: the login, then the console's first page it sent on to.
        onUi {
            shadowOf(web).pushEntryToHistory("$console/#/login?redirect=/")
            client.doUpdateVisitedHistory(web, "$console/#/login?redirect=/", false)
            shadowOf(web).pushEntryToHistory("$console/#/")
            client.doUpdateVisitedHistory(web, "$console/#/", false)
            client.onPageFinished(web, "$console/#/")
        }
        keys.back()
        assertEquals("B never goes back to the login", 0, shadowOf(web).goBackInvocations)
        assertFalse("it leaves the console, one step", panel.top is NovaHostConsolePage)
        assertTrue(panel.top is NovaCommonPage.Menu)
    }

    @Test
    fun backStepsThroughTheConsolesOwnPagesAndLeavesFromOneItWasSentStraightBackTo() {
        val keys = show(page(paired.encoded))
        val web = webView()!!
        val client = web.webViewClient
        onUi {
            shadowOf(web).pushEntryToHistory("$console/#/")
            client.doUpdateVisitedHistory(web, "$console/#/", false)
            client.onPageFinished(web, "$console/#/")
            shadowOf(web).pushEntryToHistory("$console/#/apps")
            client.doUpdateVisitedHistory(web, "$console/#/apps", false)
        }
        keys.back()
        assertEquals("B steps back one of the console's pages", 1, shadowOf(web).goBackInvocations)
        assertTrue(panel.top is NovaHostConsolePage)

        // The page it landed on sends the console straight on to Apps again, as a route guard does.
        onUi {
            client.doUpdateVisitedHistory(web, "$console/#/", false)
            shadowOf(web).pushEntryToHistory("$console/#/apps")
            client.doUpdateVisitedHistory(web, "$console/#/apps", false)
        }
        keys.back()
        assertEquals("no second bounce", 1, shadowOf(web).goBackInvocations)
        assertFalse("B leaves the console instead", panel.top is NovaHostConsolePage)
    }

    @Test
    fun anUnpairedHostsConsoleSaysNovaCannotCheckItAndBackLoadsNothing() {
        val keys = show(page(null))
        rule.onNodeWithText("Nova cannot check this host until it is paired", substring = true).assertIsDisplayed()
        button("Back").assertIsFocused()
        button("Open Anyway").assertIsDisplayed()
        assertNull("nothing loads before the player opens it", webView())

        keys.press(NovaTestKeys.CENTER)
        assertFalse(panel.top is NovaHostConsolePage)
        assertNull(webView())
    }

    @Test
    fun openAnywayTrustsOnlyTheFirstCertificateTheHostPresentsAndNeverAsItsPin() {
        val page = page(null)
        val keys = show(page, certificateOf = { null })
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.CENTER)
        val web = webView()
        assertNotNull("Open Anyway loads the console", web)
        assertEquals(console, shadowOf(web!!).lastLoadedUrl)
        val client = web.webViewClient

        val first = mock(SslErrorHandler::class.java)
        onUi { client.onReceivedSslError(web, first, SslError(SslError.SSL_UNTRUSTED, SslCertificate(paired), "$console/")) }
        verify(first).proceed()
        val again = mock(SslErrorHandler::class.java)
        onUi { client.onReceivedSslError(web, again, SslError(SslError.SSL_UNTRUSTED, SslCertificate(paired), "$console/#/apps")) }
        verify(again).proceed()

        val swapped = mock(SslErrorHandler::class.java)
        onUi { client.onReceivedSslError(web, swapped, SslError(SslError.SSL_UNTRUSTED, SslCertificate(other), "$console/")) }
        verify(swapped).cancel()
        verify(swapped, never()).proceed()
        rule.onNodeWithText("different certificate", substring = true).assertIsDisplayed()
        assertNull("what this visit trusted is never kept as the host's pin", page.pinnedCertificate)
    }

    // Polaris's Pin page offers "Pair now?" after a new PIN, and OK opens its pairing address, the
    // one its QR code holds. The console blocked it, so OK on a host Nova has not paired with did
    // nothing and said nothing. It goes to Nova's own pairing now, with that PIN and passphrase.
    @Test
    fun pairNowFromAnUnpairedHostsConsoleGoesToNovasPairingWithItsPinAndPassphrase() {
        val followed = mutableListOf<NovaHostConsoleLink>()
        val keys = show(page(null) { followed += it; null }, certificateOf = { SslCertificate(paired) })
        keys.press(NovaTestKeys.RIGHT)
        keys.press(NovaTestKeys.CENTER)
        val web = webView()!!
        shown(web)
        val link = "art://10.0.0.232:47989?pin=1234&passphrase=abcd&name=pc-papi"
        onUi { assertTrue("it never loads in the console", web.webViewClient.shouldOverrideUrlLoading(web, request(link))) }
        assertEquals(listOf(NovaHostConsoleLink.Pair(link, "10.0.0.232", 47989, "1234", "abcd")), followed)
        rule.onNodeWithTag(NOVA_HOST_CONSOLE_QUESTION_TAG).assertDoesNotExist()
        assertEquals("nothing but the link was asked for", console, shadowOf(web).lastLoadedUrl)
    }

    // Launch asks "launch it from the local client on this device?", and OK opens the app's launch
    // link, which the console blocked too: nothing happened. It is Nova's launch now.
    @Test
    fun launchFromTheConsoleForThisHostsAppGoesToNovasLaunch() {
        val followed = mutableListOf<NovaHostConsoleLink>()
        show(page(paired.encoded) { followed += it; null })
        val web = webView()!!
        shown(web)
        val link = "art://launch?host_uuid=$HOST&host_name=pc-papi&app_uuid=$CONTROL&app_name=Control"
        onUi { assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, request(link))) }
        assertEquals(listOf(NovaHostConsoleLink.Launch(HOST, "pc-papi", CONTROL, null, "Control")), followed)
        rule.onNodeWithTag(NOVA_HOST_CONSOLE_QUESTION_TAG).assertDoesNotExist()
    }

    // A link Nova cannot follow is said in place of the console, as its questions are, and OK goes
    // back to the console: another host's app, an app with no id, and any other art:// link.
    @Test
    fun aLinkForAnotherAppIsSaidInPlaceAndOkGoesBackToTheConsole() {
        val followed = mutableListOf<NovaHostConsoleLink>()
        val keys = show(page(paired.encoded) { followed += it; null })
        val web = webView()!!
        shown(web)
        val elsewhere = rule.activity.getString(R.string.nova_host_console_link_elsewhere)
        for (link in listOf(
            "art://launch?host_uuid=$OTHER_HOST&app_uuid=$CONTROL&app_name=Control",
            "art://launch?host_uuid=$HOST&app_name=Control",
            "art://settings?open=apps",
            "art://10.0.0.232:47989?name=pc-papi",
        )) {
            onUi { assertTrue(link, web.webViewClient.shouldOverrideUrlLoading(web, request(link))) }
            rule.onNodeWithText(elsewhere).assertIsDisplayed()
            assertEquals("the console is hidden under it: $link", View.INVISIBLE, web.visibility)
            button("OK").assertIsFocused()
            keys.press(NovaTestKeys.CENTER)
            rule.onNodeWithText(elsewhere).assertDoesNotExist()
            assertEquals("the console again: $link", View.VISIBLE, web.visibility)
            assertTrue(panel.top is NovaHostConsolePage)
        }
        assertTrue("none of them was followed: $followed", followed.isEmpty())
    }

    // What the page's owner says instead of following a link is said in place too: a library's
    // host is paired already, so its Pair Now has nothing to pair.
    @Test
    fun whatTheOwnerSaysInsteadIsSaidInPlace() {
        val line = rule.activity.getString(R.string.nova_host_console_link_paired)
        val keys = show(page(paired.encoded) { line })
        val web = webView()!!
        shown(web)
        onUi { web.webViewClient.shouldOverrideUrlLoading(web, request("art://10.0.0.232:47989?pin=1234&passphrase=abcd")) }
        rule.onNodeWithText(line).assertIsDisplayed()
        keys.back()
        rule.onNodeWithText(line).assertDoesNotExist()
        assertTrue("B answered it, one step", panel.top is NovaHostConsolePage)
    }

    private companion object {
        const val HOST = "5E1F5A0B-2C3D-4E5F-8A9B-0C1D2E3F4A5B"
        const val OTHER_HOST = "11111111-2222-4333-8444-555555555555"
        const val CONTROL = "992FF124-4652-5708-501D-EDDBBB80EA8E"

        // Two throwaway self-signed certificates: one stands for the certificate the host presents.
        const val PAIRED = "MIIBiTCCAS+gAwIBAgIUIaJczDVRt5Z9RKON0U4n9BbaCNwwCgYIKoZIzj0EAwIwGTEXMBUGA1UEAwwOUG9sYXJpcyBUZXN0IGEwIBcNMjYwOTI5MTM1NDI1WhgPMjEyNjA5MDUxMzU0MjVaMBkxFzAVBgNVBAMMDlBvbGFyaXMgVGVzdCBhMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXRPHahtNgHSCLnzZBtTMcVVHi+o8B5LBm+LN5+Cl72UV1y+/IamxK1Bs54tXHOOetsEJpJPghSnox8OnV3aJgaNTMFEwHQYDVR0OBBYEFHUI2KOP97cCMD5D1P+YUHJvWqMjMB8GA1UdIwQYMBaAFHUI2KOP97cCMD5D1P+YUHJvWqMjMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgMe7TiHRUIe8yaffDMGWeX6mtD3f3D7Yg2Li8tO4lpisCIQD1XJYkg4XtP2u6SdA2cPdPy7gq0GiN+5wrhro0cQR2Qw=="
        const val OTHER = "MIIBiTCCAS+gAwIBAgIUHjK84mg7MHzJNegbTSfgtUavebIwCgYIKoZIzj0EAwIwGTEXMBUGA1UEAwwOUG9sYXJpcyBUZXN0IGIwIBcNMjYwOTI5MTM1NDI1WhgPMjEyNjA5MDUxMzU0MjVaMBkxFzAVBgNVBAMMDlBvbGFyaXMgVGVzdCBiMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsE0Du7yY13/h0I+SIcAXjOJUd4hk5FyykHzyK9JE2yJClDCJdv47SHHTUzC0U+0c+jywmTGSROdM4lwktmD1nKNTMFEwHQYDVR0OBBYEFJZd9EVYDp6eoulQ28pMhbomE29CMB8GA1UdIwQYMBaAFJZd9EVYDp6eoulQ28pMhbomE29CMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAKj4DQDmr/5GwL4ycbLwrWLHcJE9eDtLPXHTh6m7BtORAiBHoxkFCzkvNCWs8HrSmX2JG+BfznULaEmdv+r1MnHj9Q=="

        fun certificate(base64: String): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64))) as X509Certificate
    }
}

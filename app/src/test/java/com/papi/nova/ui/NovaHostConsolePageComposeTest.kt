package com.papi.nova.ui

import android.net.http.SslCertificate
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

    private fun page(pin: ByteArray?) = NovaHostConsolePage(title = "Host Console", url = console, pinnedCertificate = pin)

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

    private companion object {
        // Two throwaway self-signed certificates: one stands for the certificate the host presents.
        const val PAIRED = "MIIBiTCCAS+gAwIBAgIUIaJczDVRt5Z9RKON0U4n9BbaCNwwCgYIKoZIzj0EAwIwGTEXMBUGA1UEAwwOUG9sYXJpcyBUZXN0IGEwIBcNMjYwOTI5MTM1NDI1WhgPMjEyNjA5MDUxMzU0MjVaMBkxFzAVBgNVBAMMDlBvbGFyaXMgVGVzdCBhMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXRPHahtNgHSCLnzZBtTMcVVHi+o8B5LBm+LN5+Cl72UV1y+/IamxK1Bs54tXHOOetsEJpJPghSnox8OnV3aJgaNTMFEwHQYDVR0OBBYEFHUI2KOP97cCMD5D1P+YUHJvWqMjMB8GA1UdIwQYMBaAFHUI2KOP97cCMD5D1P+YUHJvWqMjMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgMe7TiHRUIe8yaffDMGWeX6mtD3f3D7Yg2Li8tO4lpisCIQD1XJYkg4XtP2u6SdA2cPdPy7gq0GiN+5wrhro0cQR2Qw=="
        const val OTHER = "MIIBiTCCAS+gAwIBAgIUHjK84mg7MHzJNegbTSfgtUavebIwCgYIKoZIzj0EAwIwGTEXMBUGA1UEAwwOUG9sYXJpcyBUZXN0IGIwIBcNMjYwOTI5MTM1NDI1WhgPMjEyNjA5MDUxMzU0MjVaMBkxFzAVBgNVBAMMDlBvbGFyaXMgVGVzdCBiMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsE0Du7yY13/h0I+SIcAXjOJUd4hk5FyykHzyK9JE2yJClDCJdv47SHHTUzC0U+0c+jywmTGSROdM4lwktmD1nKNTMFEwHQYDVR0OBBYEFJZd9EVYDp6eoulQ28pMhbomE29CMB8GA1UdIwQYMBaAFJZd9EVYDp6eoulQ28pMhbomE29CMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAKj4DQDmr/5GwL4ycbLwrWLHcJE9eDtLPXHTh6m7BtORAiBHoxkFCzkvNCWs8HrSmX2JG+BfznULaEmdv+r1MnHj9Q=="

        fun certificate(base64: String): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64))) as X509Certificate
    }
}

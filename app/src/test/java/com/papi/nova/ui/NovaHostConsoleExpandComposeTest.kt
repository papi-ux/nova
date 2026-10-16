package com.papi.nova.ui

import android.net.http.SslCertificate
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPanelPlacementKey
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaScrim
import com.papi.nova.ui.panel.NovaSurfacesLayer
import com.papi.nova.ui.panel.NovaTestKeys
import com.papi.nova.ui.panel.setPanelContent
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host Console's Expand and Collapse (#317), in a panel drawn the way a Nova window draws one: its
 * surfaces, frame and page stack around the console.
 *
 * The console sat in a panel 60% of the window wide, at most 560dp, and in portrait in a sheet at
 * most 88% of the window high, so Polaris laid its pages out for a phone even on a tablet. Expand,
 * at the end of the console's header, or Y gives it the whole window, and Collapse puts it back.
 * Nova keeps the choice on this device.
 *
 * It uses only what the console had before Expand, so the same checks run against that console.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h520dp")
class NovaHostConsoleExpandComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val console = "https://192.0.2.10:47990"
    private val paired = certificate(PAIRED)
    private lateinit var panel: NovaPanelState

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(rule.activity)

    @Before
    fun aDeviceThatHasNotChosen() {
        prefs.edit().clear().commit()
    }

    /** The console pushed over the host's menu, as the host menu pushes it, its certificate the paired one. */
    private fun show(): NovaTestKeys {
        panel = NovaPanelState().apply {
            open(NovaCommonPage.Menu(key = "host", title = "Host", items = emptyList()))
            push(NovaHostConsolePage(title = "Host Console", url = console, pinnedCertificate = paired.encoded, hostUuid = HOST))
        }
        val keys = rule.setPanelContent {
            NovaSurfacesLayer(
                panel = panel,
                states = emptyList(),
                scrim = NovaScrim.Screen,
                pageContent = { page -> if (page is NovaHostConsolePage) NovaHostConsole(page) { SslCertificate(paired) } },
                onIdle = {},
            )
        }
        rule.waitForIdle()
        return keys
    }

    private fun webView(): WebView {
        fun find(view: View): WebView? = when (view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return find(rule.activity.window.decorView)!!
    }

    /** The console on screen, its certificate checked, with focus in it. */
    private fun shown(web: WebView) {
        rule.runOnUiThread { web.webViewClient.onPageFinished(web, "$console/#/") }
        rule.waitForIdle()
    }

    /** The panel's own size, from the placement it publishes. */
    private fun panelSize(): Pair<Dp, Dp> {
        val node = rule.onNode(SemanticsMatcher.keyIsDefined(NovaPanelPlacementKey), useUnmergedTree = true).fetchSemanticsNode()
        return with(rule.density) { node.size.width.toDp() to node.size.height.toDp() }
    }

    private fun window(): Pair<Dp, Dp> = rule.onRoot().getBoundsInRoot().let { (it.right - it.left) to (it.bottom - it.top) }

    private fun expandedKept() = prefs.getBoolean("nova_host_console_expanded", false)

    @Test
    fun expandGivesTheConsoleTheWholeWindowAndCollapsePutsItBackWithTheSamePage() {
        show()
        val web = webView()
        val (windowWidth, _) = window()
        assertEquals("collapsed, the console keeps its panel: 60% of 900dp", 540f, panelSize().first.value, 0.5f)

        rule.onNodeWithContentDescription("Expand console").performClick()
        rule.waitForIdle()
        assertEquals("expanded, it has the window's whole width", windowWidth.value, panelSize().first.value, 0.5f)
        assertTrue("and Nova keeps that for this device", expandedKept())
        assertSame("the same console, not loaded again", web, webView())

        rule.onNodeWithContentDescription("Collapse console").performClick()
        rule.waitForIdle()
        assertEquals("collapsed again, back in its panel", 540f, panelSize().first.value, 0.5f)
        assertFalse(expandedKept())
        assertSame(web, webView())
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun inPortraitExpandGivesTheSheetTheWindowsWholeHeight() {
        show()
        val (windowWidth, windowHeight) = window()
        val (width, collapsed) = panelSize()
        assertEquals("the portrait sheet is the window's width", windowWidth.value, width.value, 0.5f)
        assertEquals("collapsed, at most 88% of the window high", windowHeight.value * 0.88f, collapsed.value, 1f)

        rule.onNodeWithContentDescription("Expand console").performClick()
        rule.waitForIdle()
        assertEquals("expanded, the window's whole height", windowHeight.value, panelSize().second.value, 0.5f)
        assertTrue(expandedKept())
    }

    /** A key as Android hands it to the focused web view, and whether the view kept it. */
    private fun toWebView(web: WebView, action: Int): Boolean {
        var kept = false
        rule.runOnUiThread { kept = web.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_BUTTON_Y)) }
        rule.waitForIdle()
        return kept
    }

    @Test
    fun yInTheConsolesPageExpandsAndCollapsesItAndTheHintBarSaysWhichIsNext() {
        show()
        val web = webView()
        shown(web)
        rule.onNodeWithText("Y").assertExists()
        rule.onNodeWithText("Expand").assertExists()

        assertTrue("the page never sees Y", toWebView(web, KeyEvent.ACTION_DOWN) && toWebView(web, KeyEvent.ACTION_UP))
        assertTrue("Y expanded it", expandedKept())
        assertEquals(window().first.value, panelSize().first.value, 0.5f)
        rule.onNodeWithText("Collapse").assertExists()

        toWebView(web, KeyEvent.ACTION_DOWN)
        toWebView(web, KeyEvent.ACTION_UP)
        assertFalse("and collapsed it again", expandedKept())
        assertEquals(540f, panelSize().first.value, 0.5f)

        // A Y held since before the console opened does nothing when it comes up.
        assertTrue(toWebView(web, KeyEvent.ACTION_UP))
        assertFalse(expandedKept())
    }

    @Test
    fun yWorksWithFocusOnTheConsolesOwnButtonsAndOnTheToggle() {
        val keys = show()
        val web = webView()
        val main = mock(WebResourceRequest::class.java)
        `when`(main.isForMainFrame).thenReturn(true)
        // The console did not load: Try Again and Back stand in its place, with focus.
        rule.runOnUiThread { web.webViewClient.onReceivedError(web, main, mock(WebResourceError::class.java)) }
        rule.waitForIdle()

        keys.press(KeyEvent.KEYCODE_BUTTON_Y)
        assertTrue(expandedKept())

        rule.onNodeWithContentDescription("Collapse console").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        keys.press(KeyEvent.KEYCODE_BUTTON_Y)
        assertFalse(expandedKept())
        rule.onNodeWithContentDescription("Expand console").assertIsFocused()
    }

    @Test
    fun aPadPressesTheToggleWithAAndItKeepsFocus() {
        val keys = show()
        val toggle = rule.onNodeWithContentDescription("Expand console")
        val node = toggle.fetchSemanticsNode()
        assertEquals("a button to TalkBack", Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        val bounds = toggle.getBoundsInRoot()
        assertTrue("a finger has 48dp of it: $bounds", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)

        toggle.performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        keys.press(NovaTestKeys.CENTER)
        assertTrue(expandedKept())
        rule.onNodeWithContentDescription("Collapse console").assertIsFocused()
    }

    @Test
    fun bStillLeavesAnExpandedConsoleForTheMenuUnderIt() {
        val keys = show()
        rule.onNodeWithContentDescription("Expand console").performClick()
        rule.waitForIdle()
        keys.back()
        assertFalse("B left the console", panel.top is NovaHostConsolePage)
        assertTrue("for the host's menu, in its own panel", panelSize().first < window().first)
        assertTrue("the choice stays for the next visit", expandedKept())
    }

    private companion object {
        const val HOST = "5E1F5A0B-2C3D-4E5F-8A9B-0C1D2E3F4A5B"

        // The throwaway self-signed certificate NovaHostConsolePageComposeTest pairs with.
        const val PAIRED = "MIIBiTCCAS+gAwIBAgIUIaJczDVRt5Z9RKON0U4n9BbaCNwwCgYIKoZIzj0EAwIwGTEXMBUGA1UEAwwOUG9sYXJpcyBUZXN0IGEwIBcNMjYwOTI5MTM1NDI1WhgPMjEyNjA5MDUxMzU0MjVaMBkxFzAVBgNVBAMMDlBvbGFyaXMgVGVzdCBhMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEXRPHahtNgHSCLnzZBtTMcVVHi+o8B5LBm+LN5+Cl72UV1y+/IamxK1Bs54tXHOOetsEJpJPghSnox8OnV3aJgaNTMFEwHQYDVR0OBBYEFHUI2KOP97cCMD5D1P+YUHJvWqMjMB8GA1UdIwQYMBaAFHUI2KOP97cCMD5D1P+YUHJvWqMjMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgMe7TiHRUIe8yaffDMGWeX6mtD3f3D7Yg2Li8tO4lpisCIQD1XJYkg4XtP2u6SdA2cPdPy7gq0GiN+5wrhro0cQR2Qw=="

        fun certificate(base64: String): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64))) as X509Certificate
    }
}

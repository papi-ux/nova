package com.papi.nova.ui

import android.content.Context
import android.net.http.SslCertificate
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaPanelFrame
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.NovaScrim
import com.papi.nova.ui.panel.NovaSurfacesLayer
import com.papi.nova.ui.panel.setPanelContent
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who a touch on Host Console belongs to (#319).
 *
 * Papi saw it on a Pixel in portrait: scrolling the console moved the sheet as well. Compose hands
 * a drag on an embedded view to the panel's own drag too, unless the view asks its parent not to
 * intercept; then the panel's drag never sees it. A gesture that starts on the console is the
 * console's. The panel still closes from its header line, with B or Back, and with a tap beside it.
 * The sideways case, a swipe across a landscape panel's console, is the same mechanism on the
 * panel's horizontal drag, covered here from the source, not from anything seen on a device.
 *
 * Robolectric measures the console's web view but keeps its frame 0 by 0 whatever lays it out, so
 * no injected touch reaches it. So the console's own view is driven directly, for what it asks of
 * its parent, and the panel's response to that request runs through Compose's interop with a
 * plain view standing in for the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-port")
class NovaHostConsoleTouchComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val console = "https://192.0.2.10:47990"
    private val paired = certificate(PAIRED)
    private lateinit var panel: NovaPanelState
    private var open by mutableStateOf(true)

    /** The console pushed over the host's menu and showing, its certificate the paired one. */
    private fun show(): WebView {
        panel = NovaPanelState().apply {
            open(NovaCommonPage.Menu(key = "host", title = "Host", items = emptyList()))
            push(NovaHostConsolePage(title = "Host Console", url = console, pinnedCertificate = paired.encoded, hostUuid = HOST))
        }
        rule.setPanelContent {
            NovaSurfacesLayer(
                panel = panel,
                states = emptyList(),
                scrim = NovaScrim.Screen,
                pageContent = { page -> if (page is NovaHostConsolePage) NovaHostConsole(page) { SslCertificate(paired) } },
                onIdle = {},
            )
        }
        rule.waitForIdle()
        val web = webView()
        rule.runOnUiThread { web.webViewClient.onPageFinished(web, "$console/#/") }
        rule.waitForIdle()
        return web
    }

    private fun webView(): WebView {
        fun find(view: View): WebView? = when (view) {
            is WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return find(rule.activity.window.decorView)!!
    }

    /**
     * Whether [group] has been asked not to intercept its children's touches. ViewGroup keeps it
     * in a flag it does not publish; FLAG_DISALLOW_INTERCEPT is 0x80000.
     */
    private fun asksNotToIntercept(group: ViewGroup): Boolean {
        val flags = ViewGroup::class.java.getDeclaredField("mGroupFlags").apply { isAccessible = true }.getInt(group)
        return flags and 0x80000 != 0
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }

    /** A finger down at [start] on [node], moved by [step] eight times with a frame between moves, as on a device, then up. */
    private fun drag(node: SemanticsNodeInteraction, start: (androidx.compose.ui.test.TouchInjectionScope) -> Offset, step: Offset) {
        node.performTouchInput { down(start(this)) }
        repeat(8) {
            node.performTouchInput { moveBy(step) }
            rule.waitForIdle()
        }
        node.performTouchInput { up() }
        settle()
    }

    @Test
    fun aTouchThatStartsOnTheConsoleAsksThePanelToLeaveItToTheConsole() {
        val web = show()
        val holder = web.parent as ViewGroup
        assertFalse(asksNotToIntercept(holder))
        rule.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            web.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        }
        assertTrue("from the moment the finger lands, the gesture is the console's", asksNotToIntercept(holder))
    }

    @Test
    fun theHeaderLineStillDragsTheSheetClosed() {
        show()
        drag(rule.onNodeWithText("Host Console"), { it.center }, Offset(0f, 120f))
        assertFalse("a drag from the header line closes it, as it closes every panel", panel.isOpen)
    }

    @Test
    fun aTapBesideTheSheetStillClosesIt() {
        show()
        rule.onRoot().performTouchInput { click(Offset(centerX, 8f)) }
        settle()
        assertFalse(panel.isOpen)
    }

    /** A panel frame with a header line and, under it, a view that takes every touch on it, as a web view does. */
    private fun frameWithAPage(asks: Boolean) {
        rule.setPanelContent {
            NovaPanelFrame(edge = NovaEdge.End, width = NovaPanelWidth.Wide, open = open, onDismissRequest = { open = false }, onClosed = {}) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(48.dp).testTag("header"))
                    AndroidView(factory = { context -> PageView(context, asks) }, modifier = Modifier.fillMaxSize().testTag("page"))
                }
            }
        }
        rule.waitForIdle()
    }

    private class PageView(context: Context, private val asks: Boolean) : View(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (asks && event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            super.dispatchTouchEvent(event)
            return true
        }
    }

    @Test
    fun aPageThatDoesNotAskHasItsScrollTakenByTheSheetAsPapiSaw() {
        frameWithAPage(asks = false)
        drag(rule.onNodeWithTag("page"), { it.center }, Offset(0f, 80f))
        assertFalse("the sheet followed the page's scroll down and closed", open)
    }

    @Test
    fun aPageThatAsksKeepsItsScrollAndTheHeaderLineStillClosesTheSheet() {
        frameWithAPage(asks = true)
        drag(rule.onNodeWithTag("page"), { it.center }, Offset(0f, 80f))
        assertTrue("the page's scroll stays the page's", open)
        drag(rule.onNodeWithTag("header"), { it.center }, Offset(0f, 120f))
        assertFalse(open)
    }

    @Test
    @Config(qualifiers = "w900dp-h520dp")
    fun sidewaysOnALandscapePanelAPageThatDoesNotAskHasItsSwipeTakenByThePanel() {
        frameWithAPage(asks = false)
        drag(rule.onNodeWithTag("page"), { it.center }, Offset(60f, 0f))
        assertFalse("a sideways swipe on the page closed the panel", open)
    }

    @Test
    @Config(qualifiers = "w900dp-h520dp")
    fun sidewaysOnALandscapePanelAPageThatAsksKeepsItsSwipeAndTheHeaderLineStillCloses() {
        frameWithAPage(asks = true)
        drag(rule.onNodeWithTag("page"), { it.center }, Offset(60f, 0f))
        assertTrue("the swipe stays the page's", open)
        drag(rule.onNodeWithTag("header"), { it.center }, Offset(80f, 0f))
        assertFalse(open)
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

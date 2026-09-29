package com.papi.nova.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.papi.nova.R
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.panel.NovaBackHandler
import com.papi.nova.ui.panel.NovaFocusHint
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaPanelButton
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.novaClickable
import com.papi.nova.ui.panel.novaFocusHint
import com.papi.nova.ui.panel.novaPanelType
import java.security.MessageDigest

/**
 * The host's console, Polaris's own settings, as a page in Nova's edge panel (N6).
 *
 * It opened in a browser, which met the host's self-signed certificate and asked the player to
 * click through a warning. Polaris serves its console with the certificate it pairs with: its web
 * server and its pairing server both load `config::nvhttp.cert`. So this page trusts exactly the
 * certificate Nova stored when it paired, [pinnedCertificate], and nothing else, not even one a
 * system authority signed. Anything else stops the page with a plain line saying why, and nothing
 * on the page goes past it. Only the console's own address loads here.
 *
 * B steps back through the console's own pages, and at its first page leaves this one.
 */
internal class NovaHostConsolePage(
    override val title: String,
    /** The console: https, the host's address and the console's port, and a path when one is asked for. */
    val url: String,
    /** The DER of the certificate Nova paired with, or null when it has none, which trusts nothing. */
    val pinnedCertificate: ByteArray?,
) : NovaPage {
    override val key: String get() = KEY

    // The console is a web page laid out for a window, and the widest panel is the closest.
    override val width: NovaPanelWidth get() = NovaPanelWidth.Wide

    companion object {
        const val KEY = "host-console"
    }
}

/** What the console page trusts: the certificate Nova paired with, on the console's own address. */
internal object NovaHostConsoleTrust {
    /** Whether [presented] is, byte for byte, the certificate Nova paired with. No pin trusts nothing. */
    fun isPinned(pinned: ByteArray?, presented: ByteArray?): Boolean =
        pinned != null && presented != null && MessageDigest.isEqual(pinned, presented)

    /** Whether [url] is on [console]'s own address: https, the same host, the same port. */
    fun isConsole(console: String, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val home = Uri.parse(console)
        val there = Uri.parse(url)
        val homeHost = home.host ?: return false
        return "https".equals(home.scheme, ignoreCase = true) &&
            "https".equals(there.scheme, ignoreCase = true) &&
            homeHost.equals(there.host, ignoreCase = true) &&
            port(home) == port(there)
    }

    private fun port(uri: Uri): Int = if (uri.port == -1) 443 else uri.port
}

/** The DER of a certificate the web view was shown, or null when it cannot be read, which trusts nothing. */
internal fun SslCertificate.novaDer(): ByteArray? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        x509Certificate?.encoded
    } else {
        // Before Android 10 the certificate is only reachable through the state it saves.
        SslCertificate.saveState(this)?.getByteArray("x509-certificate")
    }
}.getOrNull()

internal enum class NovaHostConsoleState {
    /** Loading, hidden until the certificate it came with has been checked. */
    Loading,

    /** Showing the console, checked. */
    Showing,

    /** The console came with a certificate Nova did not pair with. There is no way past it. */
    Refused,

    /** Nova has no certificate for this host to check the console by. */
    Unpaired,

    /** The console did not load. */
    Failed,
}

/**
 * Checks every certificate and every address the console's view meets. A certificate error on the
 * console's address is let through only for the certificate Nova paired with; a page is only
 * shown once the certificate it came with has been checked, and any address other than the
 * console's does not load.
 */
internal class NovaHostConsoleClient(
    private val page: NovaHostConsolePage,
    private val onState: (NovaHostConsoleState) -> Unit,
    private val onHistory: (Boolean) -> Unit,
) : WebViewClient() {
    private var stopped = false

    private fun trusted(url: String?, certificate: SslCertificate?): Boolean =
        NovaHostConsoleTrust.isConsole(page.url, url) &&
            NovaHostConsoleTrust.isPinned(page.pinnedCertificate, certificate?.novaDer())

    private fun stop(view: WebView, state: NovaHostConsoleState) {
        if (stopped) return
        stopped = true
        view.stopLoading()
        view.visibility = View.INVISIBLE
        onState(state)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        if (!stopped && trusted(error.url, error.certificate)) {
            handler.proceed()
            return
        }
        handler.cancel()
        // The console's own address with another certificate is not the host Nova paired with.
        if (NovaHostConsoleTrust.isConsole(page.url, error.url)) stop(view, NovaHostConsoleState.Refused)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !NovaHostConsoleTrust.isConsole(page.url, request.url.toString())

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        !NovaHostConsoleTrust.isConsole(page.url, url)

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (stopped) return
        if (!NovaHostConsoleTrust.isConsole(page.url, url)) {
            stop(view, NovaHostConsoleState.Refused)
            return
        }
        // A certificate is known once the page commits; one that is known and is not the pin stops it.
        val certificate = view.certificate
        if (certificate != null && !trusted(url, certificate)) stop(view, NovaHostConsoleState.Refused)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (stopped) return
        // Shown only now, and only with the certificate Nova paired with, whether or not the
        // system trusted it on its own.
        if (trusted(url, view.certificate)) {
            view.visibility = View.VISIBLE
            onState(NovaHostConsoleState.Showing)
            onHistory(view.canGoBack())
        } else {
            stop(view, NovaHostConsoleState.Refused)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        onHistory(view.canGoBack())
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        // A picture or a script that fails leaves the console readable; only the page itself counts.
        if (request.isForMainFrame) stop(view, NovaHostConsoleState.Failed)
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
        // Before Android 6 this is the only report, and it is only ever for the page itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) stop(view, NovaHostConsoleState.Failed)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // The view cannot be used again; the page drops it and Try Again starts a new one.
        stopped = true
        onState(NovaHostConsoleState.Failed)
        return true
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun WebView.configureForHostConsole() {
    // The console is a script page that keeps its session in the page's storage.
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    // The panel's glass shows through until the console paints.
    setBackgroundColor(Color.TRANSPARENT)
    isFocusable = true
    isFocusableInTouchMode = true
}

/**
 * Draws a [NovaHostConsolePage]: the console once its certificate has been checked, and in its
 * place, while it loads or when it will not, a line saying so. Try Again starts a new view.
 */
@Composable
internal fun NovaPageScope.NovaHostConsole(page: NovaHostConsolePage) {
    var attempt by remember(page) { mutableIntStateOf(0) }
    var state by remember(page) {
        mutableStateOf(if (page.pinnedCertificate == null) NovaHostConsoleState.Unpaired else NovaHostConsoleState.Loading)
    }
    var canGoBack by remember(page) { mutableStateOf(false) }
    var web by remember(page) { mutableStateOf<WebView?>(null) }
    val webFocus = remember(page) { FocusRequester() }
    val statusFocus = remember(page) { FocusRequester() }
    val live = state == NovaHostConsoleState.Loading || state == NovaHostConsoleState.Showing

    // B goes back one of the console's own pages first; at its first, the panel takes this page off.
    NovaBackHandler(active = state == NovaHostConsoleState.Showing && canGoBack) { web?.goBack() }
    LaunchedEffect(state, attempt) {
        withFrameNanos { }
        // Focus is never left on nothing: on the console once it shows, else on what is said instead.
        if (state == NovaHostConsoleState.Showing) {
            runCatching { webFocus.requestFocus() }
            // The view itself, should the request not reach into it.
            web?.takeIf { !it.hasFocus() }?.requestFocus()
        } else {
            runCatching { statusFocus.requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (live) {
            key(attempt) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            configureForHostConsole()
                            visibility = View.INVISIBLE
                            webViewClient = NovaHostConsoleClient(
                                page = page,
                                onState = { state = it },
                                onHistory = { canGoBack = it },
                            )
                            web = this
                            loadUrl(page.url)
                        }
                    },
                    onRelease = { view ->
                        if (web === view) web = null
                        view.stopLoading()
                        // What was let through was for this certificate only; nothing of it is kept.
                        view.clearSslPreferences()
                        view.destroy()
                    },
                    modifier = Modifier.fillMaxSize().focusRequester(webFocus),
                )
            }
        }
        if (state != NovaHostConsoleState.Showing) {
            NovaHostConsoleStatus(
                state = state,
                focus = statusFocus,
                onTryAgain = {
                    canGoBack = false
                    state = NovaHostConsoleState.Loading
                    attempt++
                },
                onBack = { if (isTop && !panel.pop()) closeThen { } },
            )
        }
    }
}

/** What the console page says in the console's place, with the one thing to do about it focused. */
@Composable
private fun NovaHostConsoleStatus(
    state: NovaHostConsoleState,
    focus: FocusRequester,
    onTryAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val message = stringResource(
        when (state) {
            NovaHostConsoleState.Refused -> R.string.nova_host_console_refused
            NovaHostConsoleState.Unpaired -> R.string.nova_host_console_unpaired
            NovaHostConsoleState.Failed -> R.string.nova_host_console_failed
            else -> R.string.nova_host_console_opening
        },
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
    ) {
        val text = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        if (state == NovaHostConsoleState.Loading) {
            // Holds focus while the console loads, so A and B land here, where A does nothing.
            Text(
                text = message,
                style = novaPanelType.rowTitle,
                color = colors.textSecondary,
                modifier = text
                    .focusRequester(focus)
                    .novaFocusHint(NovaFocusHint.Read)
                    .novaClickable(onClick = {}),
            )
            return@Column
        }
        Text(text = message, style = novaPanelType.rowTitle, color = colors.textSecondary, modifier = text)
        if (state == NovaHostConsoleState.Failed) {
            NovaPanelButton(
                text = stringResource(R.string.nova_panel_try_again),
                primary = true,
                onClick = onTryAgain,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        NovaPanelButton(
            text = stringResource(R.string.nova_panel_back),
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (state == NovaHostConsoleState.Failed) Modifier else Modifier.focusRequester(focus)),
        )
    }
}

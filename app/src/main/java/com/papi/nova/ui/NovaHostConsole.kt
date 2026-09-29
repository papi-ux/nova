package com.papi.nova.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.testTag
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
import com.papi.nova.ui.panel.NovaPanelButtonPair
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.NovaTextField
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
 * A host Nova has not paired with has no certificate to check it by. Its console opens on a page
 * saying so, and loads only once the player opens it anyway; then the certificate it presents first
 * is the one this visit trusts, and it is never kept as the host's pin ([NovaHostConsoleVisitPin]).
 *
 * The console's own questions, its alerts, confirms and prompts, are asked in this page, in place of
 * the console, and their answers go back to the console ([NovaHostConsoleAsk]).
 *
 * B steps back through the console's own pages, never into its login, and otherwise leaves this one
 * ([NovaHostConsoleHistory]).
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

/**
 * The certificate a console Nova has not paired with presented first, once the player opened it
 * anyway: the only one this visit trusts after it. It lives as long as the page does, and nothing
 * writes it anywhere, so it never becomes the host's pairing pin.
 */
internal class NovaHostConsoleVisitPin {
    var der: ByteArray? = null
        private set

    /** Takes [presented] as this visit's certificate, if the visit has none yet. */
    fun adopt(presented: ByteArray?) {
        if (der == null && presented != null) der = presented.copyOf()
    }
}

/**
 * Where B goes in the console. It steps back through the console's own pages only while the entry
 * before is another page: not the console's login, which a signed-in console sends straight on
 * again (Polaris's router pushes `/` after it), and not the page already on screen. From anywhere
 * else B leaves the console page, and so it does from a page B came straight back to, where the
 * page before had sent it on again.
 */
internal class NovaHostConsoleHistory(private val now: () -> Long = SystemClock::uptimeMillis) {
    // The entry B stepped back from, when, and the one it landed on, until the page after that.
    private var backFrom: String? = null
    private var backAt = 0L
    private var landed: String? = null

    // An entry B came straight back to: B leaves from it rather than bounce again.
    private var looped: String? = null

    /** B on [view]: one step back, remembering where from. */
    fun back(view: WebView) {
        backFrom = view.url
        backAt = now()
        landed = null
        view.goBack()
    }

    /** The console's history changed and [url] is on screen. */
    fun update(url: String?) {
        val from = backFrom
        if (from != null) {
            if (landed == null) {
                landed = url
            } else {
                // Sent straight on to where B was pressed, as a router's guard does at once: a loop.
                // A page the player opens later is not one.
                if (url == from && now() - backAt <= NOVA_HOST_CONSOLE_REDIRECT_MS) looped = from
                backFrom = null
                landed = null
            }
        }
        if (url != looped) looped = null
    }

    /** Whether B steps back in [view]'s history, rather than leaving the console. */
    fun stepsBack(view: WebView): Boolean {
        // A view with no history to copy steps nowhere.
        val list: android.webkit.WebBackForwardList = view.copyBackForwardListOrNull() ?: return false
        val urls = (0 until list.size).map { list.getItemAtIndex(it)?.url }
        val current = list.currentIndex
        return stepsBack(urls, current) && urls.getOrNull(current) != looped
    }

    companion object {
        /** Whether B steps back from entry [current] of [urls] to the one before it. */
        fun stepsBack(urls: List<String?>, current: Int): Boolean {
            if (current < 1 || current > urls.lastIndex) return false
            val previous = urls[current - 1] ?: return false
            return !isLogin(previous) && !samePage(previous, urls[current])
        }

        /** Whether [url] is the console's login: `#/login` in its hash, or `/login` as its path. */
        fun isLogin(url: String): Boolean = route(url).equals("/login", ignoreCase = true)

        // The console's route: the hash's path where it routes by hash, else the address's path.
        private fun route(url: String): String {
            val uri = Uri.parse(url)
            val fragment = uri.fragment
            val path = if (!fragment.isNullOrEmpty() && fragment.startsWith("/")) fragment else uri.path.orEmpty()
            return path.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        }

        private fun samePage(a: String, b: String?): Boolean = b != null && a.trimEnd('/') == b.trimEnd('/')
    }
}

// Declared never null, and null from a view with nothing behind it, such as a stand-in.
private fun WebView.copyBackForwardListOrNull(): android.webkit.WebBackForwardList? =
    runCatching { copyBackForwardList() }.getOrNull()

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

    /** Nova has no certificate for this host to check the console by, and it has not been opened anyway. */
    Unpaired,

    /** The console did not load. */
    Failed,
}

/**
 * Checks every certificate and every address the console's view meets. A certificate error on the
 * console's address is let through only for the certificate Nova paired with, or, on a visit to a
 * host Nova has not paired with ([visit]), the first one the console presented; a page is only
 * shown once the certificate it came with has been checked, and any address other than the
 * console's does not load. [certificateOf] reads the certificate a page came with.
 */
internal class NovaHostConsoleClient(
    private val page: NovaHostConsolePage,
    private val onState: (NovaHostConsoleState) -> Unit,
    private val onHistory: (Boolean) -> Unit,
    private val history: NovaHostConsoleHistory = NovaHostConsoleHistory(),
    private val visit: NovaHostConsoleVisitPin? = null,
    private val certificateOf: (WebView) -> SslCertificate? = { it.certificate },
) : WebViewClient() {
    private var stopped = false

    private fun trusted(url: String?, certificate: SslCertificate?): Boolean {
        if (!NovaHostConsoleTrust.isConsole(page.url, url)) return false
        val presented = certificate?.novaDer()
        val pin = page.pinnedCertificate ?: visit?.let {
            // A host Nova has not paired with: the first certificate its console presents is the
            // one this visit trusts.
            it.adopt(presented)
            it.der
        }
        return NovaHostConsoleTrust.isPinned(pin, presented)
    }

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
        val certificate = certificateOf(view)
        if (certificate != null && !trusted(url, certificate)) stop(view, NovaHostConsoleState.Refused)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (stopped) return
        // Shown only now, and only with the certificate Nova paired with, whether or not the
        // system trusted it on its own.
        if (trusted(url, certificateOf(view))) {
            view.visibility = View.VISIBLE
            onState(NovaHostConsoleState.Showing)
            onHistory(history.stepsBack(view))
        } else {
            stop(view, NovaHostConsoleState.Refused)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        history.update(url)
        onHistory(history.stepsBack(view))
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

/**
 * A question the console's page asked: an alert, a confirm or a prompt, or whether to leave a page
 * with changes. A web view with no chrome client answers every one No, so the console's Unpair,
 * Delete and Launch did nothing, and the default dialogs float over the panel. It is asked in the
 * console's page instead, and answered once: [answer] with the player's answer, [dismiss] when the
 * page goes without one, which is Cancel, and OK for an alert, which has nothing else.
 */
internal class NovaHostConsoleAsk(
    val kind: Kind,
    val message: String,
    /** A prompt's starting text. */
    val initial: String,
    private val result: JsResult,
) {
    enum class Kind { Alert, Confirm, Prompt }

    private var answered = false

    /** The player's answer: OK, with [text] for a prompt, or Cancel. */
    fun answer(ok: Boolean, text: String = initial) {
        if (answered) return
        answered = true
        when {
            !ok -> result.cancel()
            result is JsPromptResult -> result.confirm(text)
            else -> result.confirm()
        }
    }

    /** No answer: the page left, or a new view took its place. */
    fun dismiss() = answer(ok = kind == Kind.Alert)
}

/** Hands the console's questions to [onAsk], which asks them in the console's page. */
internal class NovaHostConsoleChromeClient(
    private val leaveMessage: String,
    private val onAsk: (NovaHostConsoleAsk) -> Unit,
) : WebChromeClient() {
    private fun ask(kind: NovaHostConsoleAsk.Kind, message: String?, initial: String?, result: JsResult?): Boolean {
        result ?: return false
        onAsk(NovaHostConsoleAsk(kind, message.orEmpty(), initial.orEmpty(), result))
        return true
    }

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
        ask(NovaHostConsoleAsk.Kind.Alert, message, null, result)

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
        ask(NovaHostConsoleAsk.Kind.Confirm, message, null, result)

    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean =
        ask(NovaHostConsoleAsk.Kind.Prompt, message, defaultValue, result)

    // Its own message is the browser's, and says nothing a player can act on.
    override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
        ask(NovaHostConsoleAsk.Kind.Confirm, leaveMessage, null, result)
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
 * place, while it loads or when it will not, a line saying so. Try Again starts a new view. A host
 * Nova has not paired with opens on a page saying Nova cannot check it, with Back focused and Open
 * Anyway beside it. A question the console asks takes the console's place until it is answered.
 * [certificateOf] reads the certificate a page came with.
 */
@Composable
internal fun NovaPageScope.NovaHostConsole(
    page: NovaHostConsolePage,
    certificateOf: (WebView) -> SslCertificate? = { it.certificate },
) {
    var attempt by remember(page) { mutableIntStateOf(0) }
    var state by remember(page) {
        mutableStateOf(if (page.pinnedCertificate == null) NovaHostConsoleState.Unpaired else NovaHostConsoleState.Loading)
    }
    var canGoBack by remember(page) { mutableStateOf(false) }
    var web by remember(page) { mutableStateOf<WebView?>(null) }
    var ask by remember(page) { mutableStateOf<NovaHostConsoleAsk?>(null) }
    val history = remember(page) { NovaHostConsoleHistory() }
    // Only for a host Nova has not paired with, and only for as long as this page is open.
    val visit = remember(page) { NovaHostConsoleVisitPin().takeIf { page.pinnedCertificate == null } }
    val webFocus = remember(page) { FocusRequester() }
    val statusFocus = remember(page) { FocusRequester() }
    val live = state == NovaHostConsoleState.Loading || state == NovaHostConsoleState.Showing
    val leaveMessage = stringResource(R.string.nova_host_console_leave_page)
    val leave = { if (isTop && !panel.pop()) closeThen { } }

    // B goes back one of the console's own pages first; elsewhere the panel takes this page off.
    NovaBackHandler(active = state == NovaHostConsoleState.Showing && canGoBack && ask == null) {
        web?.let(history::back)
    }
    // A question still open when the page goes is answered as a closed dialog is, so the console's
    // script never waits on it.
    DisposableEffect(page) { onDispose { ask?.dismiss() } }
    // The console is hidden while a question stands in its place, and shown again once answered.
    LaunchedEffect(state, ask, web) {
        web?.visibility = if (state == NovaHostConsoleState.Showing && ask == null) View.VISIBLE else View.INVISIBLE
    }
    LaunchedEffect(state, attempt, ask) {
        if (ask != null) return@LaunchedEffect
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
                                history = history,
                                visit = visit,
                                certificateOf = certificateOf,
                            )
                            webChromeClient = NovaHostConsoleChromeClient(leaveMessage) { next ->
                                ask?.dismiss()
                                ask = next
                            }
                            web = this
                            loadUrl(page.url)
                        }
                    },
                    onRelease = { view ->
                        if (web === view) web = null
                        ask?.dismiss()
                        ask = null
                        view.stopLoading()
                        // What was let through was for this certificate only; nothing of it is kept.
                        view.clearSslPreferences()
                        view.destroy()
                    },
                    modifier = Modifier.fillMaxSize().focusRequester(webFocus),
                )
            }
        }
        val question = ask
        when {
            question != null -> NovaHostConsoleQuestion(question, onAnswered = { ask = null })
            state == NovaHostConsoleState.Unpaired -> NovaHostConsoleUnpaired(
                focus = statusFocus,
                onOpenAnyway = {
                    canGoBack = false
                    state = NovaHostConsoleState.Loading
                    attempt++
                },
                onBack = leave,
            )
            state != NovaHostConsoleState.Showing -> NovaHostConsoleStatus(
                state = state,
                visit = visit != null,
                focus = statusFocus,
                onTryAgain = {
                    canGoBack = false
                    state = NovaHostConsoleState.Loading
                    attempt++
                },
                onBack = leave,
            )
        }
    }
}

/**
 * A question from the console, asked in the console's place: its words, then OK for an alert, a
 * split of Cancel (focused) and OK for a confirm, and a field over that split for a prompt. B is
 * Cancel, and OK for an alert. Nothing floats.
 */
@Composable
private fun NovaHostConsoleQuestion(ask: NovaHostConsoleAsk, onAnswered: () -> Unit) {
    val colors = LocalNovaComposeColors.current
    val first = remember(ask) { FocusRequester() }
    var typed by remember(ask) { mutableStateOf(ask.initial) }
    fun answer(ok: Boolean) {
        ask.answer(ok, typed)
        onAnswered()
    }
    NovaBackHandler(active = true) { answer(ok = ask.kind == NovaHostConsoleAsk.Kind.Alert) }
    LaunchedEffect(ask) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val ok = stringResource(R.string.nova_host_console_ok)
    val cancel = stringResource(R.string.nova_panel_cancel)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = NovaPanelMetrics.SpaceSm)
            .testTag(NOVA_HOST_CONSOLE_QUESTION_TAG),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
    ) {
        Text(
            text = ask.message,
            style = novaPanelType.rowTitle,
            color = colors.textSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (ask.kind == NovaHostConsoleAsk.Kind.Alert) {
            NovaPanelButton(
                text = ok,
                primary = true,
                onClick = { answer(ok = true) },
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
            return@Column
        }
        val prompt = ask.kind == NovaHostConsoleAsk.Kind.Prompt
        if (prompt) {
            NovaTextField(
                value = typed,
                onValueChange = { typed = it },
                label = stringResource(R.string.nova_host_console_answer),
                onImeAction = { answer(ok = true) },
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
        }
        NovaPanelButtonPair(
            first = {
                NovaPanelButton(
                    text = cancel,
                    onClick = { answer(ok = false) },
                    modifier = if (prompt) Modifier else Modifier.focusRequester(first),
                )
            },
            second = { NovaPanelButton(text = ok, primary = true, onClick = { answer(ok = true) }) },
        )
    }
}

/**
 * The first page of a host Nova has not paired with: Nova cannot check it until it is paired, with
 * Back focused and Open Anyway beside it, which A, Right, A reaches. Back leaves without loading
 * anything.
 */
@Composable
private fun NovaHostConsoleUnpaired(focus: FocusRequester, onOpenAnyway: () -> Unit, onBack: () -> Unit) {
    val colors = LocalNovaComposeColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceMd),
    ) {
        Text(
            text = stringResource(R.string.nova_host_console_unpaired),
            style = novaPanelType.rowTitle,
            color = colors.textSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        NovaPanelButtonPair(
            first = {
                NovaPanelButton(
                    text = stringResource(R.string.nova_panel_back),
                    onClick = onBack,
                    modifier = Modifier.focusRequester(focus),
                )
            },
            second = {
                NovaPanelButton(
                    text = stringResource(R.string.nova_host_console_open_anyway),
                    primary = true,
                    destructive = true,
                    onClick = onOpenAnyway,
                )
            },
        )
    }
}

/** What the console page says in the console's place, with the one thing to do about it focused. */
@Composable
private fun NovaHostConsoleStatus(
    state: NovaHostConsoleState,
    visit: Boolean,
    focus: FocusRequester,
    onTryAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val message = stringResource(
        when (state) {
            NovaHostConsoleState.Refused ->
                if (visit) R.string.nova_host_console_visit_refused else R.string.nova_host_console_refused
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

/** How soon after B a page sent straight back to where B was pressed counts as a redirect. */
private const val NOVA_HOST_CONSOLE_REDIRECT_MS = 1_500L

/** The question the console asked, while it stands in the console's place, for a test to find it. */
internal const val NOVA_HOST_CONSOLE_QUESTION_TAG = "nova-host-console-question"

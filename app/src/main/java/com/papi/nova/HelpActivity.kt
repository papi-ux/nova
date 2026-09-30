package com.papi.nova

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaProblemBack
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.utils.UiHelper
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Help inside Nova, which is the only Help on a TV (HelpLauncher sends every Leanback device here).
 * While a page loads, Cancel is focused; a page that cannot load says so on a state page with Try
 * Again instead of leaving a blank screen. B on either goes back one page, and leaves Help only
 * from the first.
 */
@Suppress("DEPRECATION")
class HelpActivity : NovaActivity() {
    private var loading = false
    private lateinit var webView: WebView

    /**
     * The page asked for, set before its load starts: a certificate error on the page itself comes
     * before the page has started, so waiting for onPageStarted to name it missed it and left the
     * screen blank. It tells an error on the page from one on a part of it.
     */
    private var loadingUrl: String? = null

    /**
     * Back steps back through the pages read here before it leaves. B reaches the dispatcher through
     * the key gate, which never runs an onBackPressed override, so this is a callback, on only while
     * there is a page to go back to.
     */
    private val pageBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            webView.goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, pageBack)

        webView = WebView(this)
        setContentView(webView)
        UiHelper.padContentForSystemBars(this)

        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.javaScriptEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            webView.settings.allowFileAccessFromFileURLs = false
            webView.settings.allowUniversalAccessFromFileURLs = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.settings.safeBrowsingEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                follow(request.url.toString(), request.isForMainFrame)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = follow(url, mainFrame = true)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loadingUrl = url
                if (!loading) {
                    loading = true
                    // With Cancel, which is focused and is what B does: without it the page held A
                    // and B on a TV, where this is the only Help, until the load gave up.
                    NovaSurfaces.of(this@HelpActivity).show(
                        NovaStatePage.Busy(
                            key = LOADING_PAGE,
                            title = getString(R.string.help_loading_title),
                            message = MutableStateFlow(getString(R.string.help_loading_msg)),
                            cancel = NovaAction(getString(R.string.nova_panel_cancel)) {
                                dismissLoading()
                                view.stopLoading()
                                step(stepFrom(loadingUrl))
                            },
                        ),
                    )
                }

                refreshBackDispatchState()
            }

            override fun onPageFinished(view: WebView, url: String) {
                dismissLoading()
                refreshBackDispatchState()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // A picture or a script that fails leaves the page readable; only the page itself counts.
                if (request.isForMainFrame) showLoadFailed(request.url?.toString())
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                // Before Android 6 this is the only report, and it is only ever for the page itself.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) showLoadFailed(failingUrl)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // Never past a certificate error. When it is the page itself, say it did not load.
                handler.cancel()
                if (error.url == loadingUrl) showLoadFailed(error.url)
            }
        }

        val initialUrl = intent.dataString
        if (initialUrl == null || !isSafeUrl(initialUrl)) {
            finish()
            return
        }

        loadingUrl = initialUrl
        webView.loadUrl(initialUrl)
    }

    /**
     * Whether to stop the WebView loading [url]: any page that is not https is. A page it goes on
     * to load is the one a certificate error is checked against.
     */
    private fun follow(url: String, mainFrame: Boolean): Boolean {
        if (!isSafeUrl(url)) return true
        if (mainFrame) loadingUrl = url
        return false
    }

    private fun dismissLoading() {
        if (!loading) return
        loading = false
        NovaSurfaces.existing(this)?.dismiss(LOADING_PAGE)
    }

    /** Where B goes from a page that is loading or did not load. */
    private enum class Step { Stay, Back, Leave }

    /**
     * B from [url], a page loading or one that did not load: the page on screen stays when that
     * load never replaced it, as a link stopped at its certificate does; otherwise one page back,
     * and out of Help only from the first page, or when no page is on screen at all.
     */
    private fun stepFrom(url: String?): Step {
        val onScreen = webView.url ?: return Step.Leave
        return when {
            url != null && url != onScreen -> Step.Stay
            webView.canGoBack() -> Step.Back
            else -> Step.Leave
        }
    }

    private fun step(step: Step) {
        when (step) {
            Step.Stay -> Unit
            Step.Back -> webView.goBack()
            Step.Leave -> finish()
        }
    }

    /**
     * A state page in place of the page that did not load, [url]: Try Again is focused and loads it
     * again, and B goes back one step ([stepFrom]), which closes Help only from the first page.
     */
    private fun showLoadFailed(url: String?) {
        if (isFinishing || isDestroyed) return
        dismissLoading()
        val surfaces = NovaSurfaces.of(this)
        val leaves = stepFrom(url) == Step.Leave
        val back = NovaAction(getString(if (leaves) R.string.nova_panel_close else R.string.nova_panel_back)) {
            surfaces.dismiss(LOAD_FAILED_PAGE)
            step(stepFrom(url))
        }
        surfaces.show(
            NovaStatePage.Problem(
                key = LOAD_FAILED_PAGE,
                title = getString(R.string.help_load_failed_title),
                message = getString(R.string.help_load_failed_message),
                primary = NovaAction(getString(R.string.nova_panel_try_again)) {
                    surfaces.dismiss(LOAD_FAILED_PAGE)
                    // A page stopped at its certificate never replaced the one on screen, so a
                    // reload would load that one again: the failed page is asked for by its address.
                    if (url.isNullOrBlank() || url == webView.url) {
                        webView.reload()
                    } else {
                        loadingUrl = url
                        webView.loadUrl(url)
                    }
                },
                back = NovaProblemBack.Close(back),
                secondary = listOf(back),
            ),
        )
    }

    private fun isSafeUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme
        return scheme != null && scheme.equals("https", ignoreCase = true)
    }

    private fun refreshBackDispatchState() {
        pageBack.isEnabled = webView.canGoBack()
    }

    private companion object {
        const val LOADING_PAGE = "help-loading"
        const val LOAD_FAILED_PAGE = "help-load-failed"
    }
}

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
import com.papi.nova.utils.SpinnerDialog
import com.papi.nova.utils.UiHelper

/**
 * Help inside Nova, which is the only Help on a TV (HelpLauncher sends every Leanback device here).
 * While a page loads, Cancel is focused and B leaves; a page that cannot load says so on a state
 * page with Try Again and Close instead of leaving a blank screen.
 */
@Suppress("DEPRECATION")
class HelpActivity : NovaActivity() {
    private var loadingDialog: SpinnerDialog? = null
    private lateinit var webView: WebView

    /** The page being loaded, so a certificate error on it can be told from one on a part of it. */
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
                !isSafeUrl(request.url.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !isSafeUrl(url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loadingUrl = url
                if (loadingDialog == null) {
                    // With Cancel, which is focused and is what B does: without it the page held A
                    // and B on a TV, where this is the only Help, until the load gave up.
                    loadingDialog = SpinnerDialog.displayDialog(
                        this@HelpActivity,
                        resources.getString(R.string.help_loading_title),
                        resources.getString(R.string.help_loading_msg),
                        true,
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
                if (request.isForMainFrame) showLoadFailed()
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                // Before Android 6 this is the only report, and it is only ever for the page itself.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) showLoadFailed()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // Never past a certificate error. When it is the page itself, say it did not load.
                handler.cancel()
                if (error.url == loadingUrl) showLoadFailed()
            }
        }

        val initialUrl = intent.dataString
        if (initialUrl == null || !isSafeUrl(initialUrl)) {
            finish()
            return
        }

        webView.loadUrl(initialUrl)
    }

    private fun dismissLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    /** A state page in place of the page that did not load: Try Again is focused, and B closes Help. */
    private fun showLoadFailed() {
        if (isFinishing || isDestroyed) return
        dismissLoading()
        val surfaces = NovaSurfaces.of(this)
        val close = NovaAction(getString(R.string.nova_panel_close)) {
            surfaces.dismiss(LOAD_FAILED_PAGE)
            finish()
        }
        surfaces.show(
            NovaStatePage.Problem(
                key = LOAD_FAILED_PAGE,
                title = getString(R.string.help_load_failed_title),
                message = getString(R.string.help_load_failed_message),
                primary = NovaAction(getString(R.string.nova_panel_try_again)) {
                    surfaces.dismiss(LOAD_FAILED_PAGE)
                    webView.reload()
                },
                back = NovaProblemBack.Close(close),
                secondary = listOf(close),
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
        const val LOAD_FAILED_PAGE = "help-load-failed"
    }
}

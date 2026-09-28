package com.papi.nova

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.utils.SpinnerDialog
import com.papi.nova.utils.UiHelper

@Suppress("DEPRECATION")
class HelpActivity : NovaActivity() {
    private var loadingDialog: SpinnerDialog? = null
    private lateinit var webView: WebView

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
                if (loadingDialog == null) {
                    loadingDialog = SpinnerDialog.displayDialog(
                        this@HelpActivity,
                        resources.getString(R.string.help_loading_title),
                        resources.getString(R.string.help_loading_msg),
                        false,
                    )
                }

                refreshBackDispatchState()
            }

            override fun onPageFinished(view: WebView, url: String) {
                loadingDialog?.dismiss()
                loadingDialog = null

                refreshBackDispatchState()
            }
        }

        val initialUrl = intent.dataString
        if (initialUrl == null || !isSafeUrl(initialUrl)) {
            finish()
            return
        }

        webView.loadUrl(initialUrl)
    }

    private fun isSafeUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme
        return scheme != null && scheme.equals("https", ignoreCase = true)
    }

    private fun refreshBackDispatchState() {
        pageBack.isEnabled = webView.canGoBack()
    }
}

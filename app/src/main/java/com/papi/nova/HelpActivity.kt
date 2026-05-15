package com.papi.nova

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.app.AppCompatActivity
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.utils.SpinnerDialog

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class HelpActivity : AppCompatActivity() {
    private var loadingDialog: SpinnerDialog? = null
    private lateinit var webView: WebView
    private var backCallbackRegistered = false
    private var onBackInvokedCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedCallback = OnBackInvokedCallback {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
            }
        }

        webView = WebView(this)
        setContentView(webView)

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = onBackInvokedCallback ?: return
            if (webView.canGoBack() && !backCallbackRegistered) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    callback,
                )
                backCallbackRegistered = true
            } else if (!webView.canGoBack() && backCallbackRegistered) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
                backCallbackRegistered = false
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallbackRegistered) {
            onBackInvokedCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }

        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}

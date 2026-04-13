package com.papi.nova;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.appcompat.app.AppCompatActivity;

import com.papi.nova.utils.SpinnerDialog;

public class HelpActivity extends AppCompatActivity {

    private SpinnerDialog loadingDialog;
    private WebView webView;

    private boolean backCallbackRegistered;
    private OnBackInvokedCallback onBackInvokedCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.papi.nova.ui.NovaThemeManager.INSTANCE.applyTheme(this);
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedCallback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    // We should always be able to go back because we unregister our callback
                    // when we can't go back. Nonetheless, we will still check anyway.
                    if (webView.canGoBack()) {
                        webView.goBack();
                    }
                }
            };
        }

        webView = new WebView(this);
        setContentView(webView);

        // These allow the user to zoom the page
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);

        // This sets the view to display the whole page by default
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);

        // GitHub docs rely on basic JS for navigation, but keep the rest of the
        // WebView surface as locked down as possible.
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            webView.getSettings().setAllowFileAccessFromFileURLs(false);
            webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.getSettings().setSafeBrowsingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isSafeUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isSafeUrl(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (loadingDialog == null) {
                    loadingDialog = SpinnerDialog.displayDialog(HelpActivity.this,
                            getResources().getString(R.string.help_loading_title),
                            getResources().getString(R.string.help_loading_msg), false);
                }

                refreshBackDispatchState();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (loadingDialog != null) {
                    loadingDialog.dismiss();
                    loadingDialog = null;
                }

                refreshBackDispatchState();
            }
        });

        String initialUrl = getIntent().getDataString();
        if (initialUrl == null || !isSafeUrl(initialUrl)) {
            finish();
            return;
        }

        webView.loadUrl(initialUrl);
    }

    private boolean isSafeUrl(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return scheme != null && scheme.equalsIgnoreCase("https");
    }

    private void refreshBackDispatchState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (webView.canGoBack() && !backCallbackRegistered) {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, onBackInvokedCallback);
                backCallbackRegistered = true;
            }
            else if (!webView.canGoBack() && backCallbackRegistered) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(onBackInvokedCallback);
                backCallbackRegistered = false;
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (backCallbackRegistered) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(onBackInvokedCallback);
            }
        }

        super.onDestroy();
    }

    @Override
    // NOTE: This will NOT be called on Android 13+ with android:enableOnBackInvokedCallback="true"
    public void onBackPressed() {
        // Back goes back through the WebView history
        // until no more history remains
        if (webView.canGoBack()) {
            webView.goBack();
        }
        else {
            super.onBackPressed();
        }
    }
}

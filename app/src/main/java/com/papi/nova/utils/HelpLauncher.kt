package com.papi.nova.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.papi.nova.HelpActivity

object HelpLauncher {
    @JvmStatic
    fun launchUrl(context: Context, url: String) {
        var activeUrl = url
        if (activeUrl.startsWith("@")) {
            try {
                val resId = activeUrl.substring(1).toInt()
                activeUrl = context.getString(resId)
            } catch (_: Exception) {
            }
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(activeUrl)

            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {
            // Fall through to the in-app WebView for devices without a usable browser.
        }

        val intent = Intent(context, HelpActivity::class.java)
        intent.data = Uri.parse(activeUrl)
        context.startActivity(intent)
    }

    @JvmStatic
    fun launchSetupGuide(context: Context) {
        launchGithub(context)
    }

    @JvmStatic
    fun launchGithub(context: Context) {
        launchUrl(context, "https://github.com/papi-ux/nova")
    }

    @JvmStatic
    fun launchTroubleshooting(context: Context) {
        launchUrl(context, "https://papi-ux.com/docs/troubleshooting/")
    }

    @JvmStatic
    fun launchGameStreamEolFaq(context: Context) {
        launchUrl(
            context,
            "https://github.com/moonlight-stream/moonlight-docs/wiki/NVIDIA-GameStream-End-Of-Service-Announcement-FAQ"
        )
    }
}

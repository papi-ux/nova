package com.papi.nova.network

import android.content.Intent
import android.content.pm.PackageManager

/**
 * Opening the Tailscale app from host setup. Being installed says nothing about whether a host is
 * reached through Tailscale; this only offers a shortcut to it, and only when its own exported,
 * permission-free launcher activity is what the launch would open.
 */
object NovaTailscaleApp {
    const val PACKAGE = "com.tailscale.ipn"

    /** The launch intent, or null when the app is absent or its launch surface is not safe to use. */
    @JvmStatic
    fun launchIntent(packageManager: PackageManager): Intent? {
        val intent = runCatching {
            packageManager.getLaunchIntentForPackage(PACKAGE) ?: packageManager.getLeanbackLaunchIntentForPackage(PACKAGE)
        }.getOrNull() ?: return null
        val info = runCatching { packageManager.resolveActivity(intent, 0)?.activityInfo }.getOrNull() ?: return null
        return intent.takeIf {
            isSafeLaunchTarget(
                componentPackage = intent.component?.packageName,
                resolvedPackage = info.packageName,
                exported = info.exported,
                enabled = info.enabled,
                permission = info.permission,
            )
        }
    }

    /** Only Tailscale's own enabled, exported activity that needs no permission. */
    @JvmStatic
    fun isSafeLaunchTarget(
        componentPackage: String?,
        resolvedPackage: String?,
        exported: Boolean,
        enabled: Boolean,
        permission: String?,
    ): Boolean = componentPackage == PACKAGE && resolvedPackage == PACKAGE && exported && enabled && permission == null
}

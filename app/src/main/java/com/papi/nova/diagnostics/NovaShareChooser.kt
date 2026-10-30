package com.papi.nova.diagnostics

import android.content.Intent

/**
 * The one way Nova opens a share sheet, so picking where something goes is a step the user sees.
 *
 * Android's chooser starts a lone target by itself. With Google services off on the RP6, Bluetooth
 * is the only target, and Share Report landed straight in "Choose Bluetooth device" (#280).
 */
object NovaShareChooser {
    /**
     * What Android's chooser reads to decide that. It is not in the public SDK: a chooser that
     * ignores it starts a lone target as before, and every other choice stays as it was.
     */
    internal const val EXTRA_AUTO_LAUNCH_SINGLE_CHOICE = "android.intent.extra.AUTO_LAUNCH_SINGLE_CHOICE"

    @JvmStatic
    fun create(send: Intent, title: CharSequence): Intent =
        Intent.createChooser(send, title).putExtra(EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false)
}

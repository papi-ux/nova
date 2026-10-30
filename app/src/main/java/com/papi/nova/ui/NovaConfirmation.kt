package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.papi.nova.Game
import com.papi.nova.R

/**
 * A pairing that saved the host's certificate, and a connection the player asked for, are each
 * confirmed once (#386). Nova's Haptic Feedback toggle gates the view's own feedback, and the
 * system's touch feedback setting can still veto it. A device without a vibrator feels nothing;
 * this is never controller rumble. Pairing's words travel to the screen it opens on a one-shot extra. A
 * connection's are the stream's setup overlay title, which goes before the game is shown.
 */
object NovaConfirmation {
    /** "Paired with <host>", on the Library or App list launch a pairing opens. */
    const val EXTRA_PAIRED_WITH = "NovaPairedWith"

    /** One firm click: the ordinary click CONFIRM plays was barely felt on the RP6. */
    fun haptic(view: View?) {
        view?.let { NovaHaptics.play(it.context, it, NovaHapticCue.CONNECTED) }
    }

    /** The host's name as its record has it. A host with none is confirmed by the haptic alone. */
    fun pairedWith(context: Context, hostName: String?): String? =
        hostName?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.nova_paired_with, it) }

    /** The setup overlay's input_ready title. Empty keeps the overlay's own "Ready". */
    fun connectedTo(context: Context, hostName: String?): String =
        hostName?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.nova_connected_to, it) }.orEmpty()

    /**
     * The pairing a screen was opened with, taken off [intent] so it is said once. recreate() gives
     * the new instance this same Intent, so there is nothing left for it to say. A restore after the
     * process was killed brings the original extras back, with [savedInstanceState], and so does a
     * relaunch from Recents. Neither is a pairing.
     */
    fun takePairedWith(intent: Intent?, savedInstanceState: Bundle?): String? {
        if (intent == null) return null
        val message = intent.getStringExtra(EXTRA_PAIRED_WITH) ?: return null
        intent.removeExtra(EXTRA_PAIRED_WITH)
        val replayed = savedInstanceState != null ||
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        return message.takeUnless { replayed || it.isBlank() }
    }

    /**
     * One Game's connection confirmation. A launch the player asked for arms it. A replacement
     * stream, for Reconnect or the resilience path, carries [Game.EXTRA_RECONNECT_REQUESTED] and
     * does not. Only the first connectionStarted takes it.
     */
    class Connection {
        private var due = false

        fun arm(launch: Intent?) {
            due = launch != null && !launch.getBooleanExtra(Game.EXTRA_RECONNECT_REQUESTED, false)
        }

        fun take(): Boolean = due.also { due = false }
    }
}

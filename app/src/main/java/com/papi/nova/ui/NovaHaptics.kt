package com.papi.nova.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.preference.PreferenceManager

/**
 * The few moments Nova says something by feel, each with its own pattern (#421). A reconnect says
 * nothing: a stream replacing another one neither confirms nor refuses by touch.
 */
enum class NovaHapticCue {
    /** A connection or pairing the player asked for: one firm click. */
    CONNECTED,

    /** The host confirmed the End the player asked for: one soft long pulse. */
    END_CONFIRMED,

    /** An error or refusal the player is about to read: a short double. */
    REFUSED,
}

/** How a cue is played: the system's own feedback where it has one, otherwise a vibration. */
internal sealed interface NovaHapticFeel {
    data class Feedback(val constant: Int) : NovaHapticFeel

    /** Off and on times in turn, from an off time, as [VibrationEffect.createWaveform] takes them. */
    data class Pulses(val timingsMs: List<Long>, val amplitude: Int) : NovaHapticFeel
}

/** Android plays LONG_PRESS as its heavy click and REJECT as its double click, from Android 11. */
internal fun novaHapticFeel(cue: NovaHapticCue, sdk: Int): NovaHapticFeel = when (cue) {
    NovaHapticCue.CONNECTED -> NovaHapticFeel.Feedback(HapticFeedbackConstants.LONG_PRESS)
    NovaHapticCue.END_CONFIRMED -> NovaHapticFeel.Pulses(listOf(0L, 240L), amplitude = 96)
    NovaHapticCue.REFUSED ->
        if (sdk >= Build.VERSION_CODES.R) NovaHapticFeel.Feedback(HapticFeedbackConstants.REJECT)
        else NovaHapticFeel.Pulses(listOf(0L, 30L, 70L, 30L), amplitude = 255)
}

/** Nova's taps, menus and confirmations; keyboard/controller feedback has its own settings. */
object NovaHaptics {
    // Match preferences.xml. Read on each request so an open surface sees a settings change.
    private fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("nova_haptic_feedback", true)

    fun perform(view: View?, feedbackConstant: Int) {
        if (view != null && isEnabled(view.context)) {
            // No override flags: Android still decides whether the feedback is allowed.
            view.performHapticFeedback(feedbackConstant)
        }
    }

    /**
     * Plays [cue] once. [view] carries the system's own feedback, and can be null for a cue with
     * none: a confirmed End usually arrives after its stream's window has gone.
     */
    fun play(context: Context, view: View?, cue: NovaHapticCue) {
        when (val feel = novaHapticFeel(cue, Build.VERSION.SDK_INT)) {
            is NovaHapticFeel.Feedback -> perform(view, feel.constant)
            is NovaHapticFeel.Pulses -> if (isEnabled(context) && systemAllowsTouchFeedback(context)) vibrate(context, feel)
        }
    }

    // A view's feedback gets this check from Android; a vibration has to make it itself.
    private fun systemAllowsTouchFeedback(context: Context): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    private fun vibrate(context: Context, pulses: NovaHapticFeel.Pulses) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val timings = pulses.timingsMs.toLongArray()
        val sonification = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1, sonification)
            return
        }
        val amplitudes = IntArray(timings.size) { if (it % 2 == 0) 0 else pulses.amplitude }
        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, sonification)
        }
    }

    internal fun forCompose(context: Context, delegate: HapticFeedback): HapticFeedback =
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (isEnabled(context)) delegate.performHapticFeedback(hapticFeedbackType)
            }
        }
}

/** Keep Compose's platform feedback and system veto behind the same Nova preference. */
@Composable
fun rememberNovaHapticFeedback(): HapticFeedback {
    val context = LocalContext.current
    val delegate = LocalHapticFeedback.current
    return remember(context, delegate) { NovaHaptics.forCompose(context, delegate) }
}

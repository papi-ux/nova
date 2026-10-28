package com.papi.nova.ui

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.preference.PreferenceManager

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

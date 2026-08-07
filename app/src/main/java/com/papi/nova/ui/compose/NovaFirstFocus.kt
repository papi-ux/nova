package com.papi.nova.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay

/** Long enough for the surface to have been laid out; the request lands on nothing before that. */
const val NOVA_FIRST_FOCUS_SETTLE_MS = 120L

/**
 * Takes focus for the first focusable child once the surface has been laid out.
 *
 * Without it a surface opens with focus wherever the previous one left it, and the first d-pad
 * press either does nothing or dismisses the screen that was just opened. The game detail
 * window worked this out first and kept it to itself; the Polaris Sync sheet had no focus ring
 * at all until this was shared.
 *
 * Attach it to the group that should own the first press, not to the outermost container —
 * where focus lands is a decision about what the surface is for.
 */
@Composable
fun Modifier.novaHoldsFirstFocus(settleMillis: Long = NOVA_FIRST_FOCUS_SETTLE_MS): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(settleMillis)
        runCatching { requester.requestFocus() }
    }
    return focusRequester(requester).focusGroup()
}

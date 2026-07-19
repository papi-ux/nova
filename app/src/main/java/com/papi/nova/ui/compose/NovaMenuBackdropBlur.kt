package com.papi.nova.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.papi.nova.ui.NovaMenuBlur
import kotlin.math.roundToInt

/** Keeps menu content sharp while adaptively blurring the owning Activity beneath it. */
@Composable
fun NovaMenuBackdropBlur() {
    val context = LocalContext.current
    val opacityPercent = (LocalNovaMenuOpacityScale.current * 100f).roundToInt()
    DisposableEffect(context, opacityPercent) {
        val lease = NovaMenuBlur.acquireActivityBackground(context, opacityPercent)
        onDispose {
            lease?.release()
        }
    }
}

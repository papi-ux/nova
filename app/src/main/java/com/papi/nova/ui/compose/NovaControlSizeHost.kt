package com.papi.nova.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import com.papi.nova.ui.NovaControlSize

/** Device appearance only: nested themes provide the choice, never multiply it. */
internal val LocalNovaControlSize = staticCompositionLocalOf { NovaControlSize.Standard }

/** Keep Android's density, font conversion, viewport and minimum touch targets intact. */
@Composable
internal fun NovaControlSizeHost(size: NovaControlSize, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNovaControlSize provides size, content = content)
}

/** A control's visual dimension. Text and accessible hit areas keep their original dimensions. */
@Composable
@ReadOnlyComposable
internal fun novaControlDimension(value: Dp): Dp = value * LocalNovaControlSize.current.layoutScale

@Composable
@ReadOnlyComposable
internal fun novaControlPadding(value: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = novaControlDimension(value.calculateStartPadding(direction)),
        top = novaControlDimension(value.calculateTopPadding()),
        end = novaControlDimension(value.calculateEndPadding(direction)),
        bottom = novaControlDimension(value.calculateBottomPadding()),
    )
}

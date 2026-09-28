package com.papi.nova.ui.compose

/**
 * Shared opacity contract for in-game overlays drawn directly over stream video.
 *
 * Command Center and NovaHUD intentionally use the same glass hierarchy so bright
 * and dark game scenes read as one overlay system instead of separate floating
 * panels with almost-but-not-quite matching alpha values. The Command Center's rows
 * and cards rest as the one row tile every panel uses (novaRowRest), so it names no
 * nested fill of its own.
 */
object NovaInGameOverlayAlpha {
    const val CommandCenterScrim = 0.42f
    const val Border = 0.90f
    const val AccentHandle = 0.74f
    const val AccentDivider = 0.35f
    const val SparklineGuide = 0.20f
    const val SparklineFill = 0.18f
}

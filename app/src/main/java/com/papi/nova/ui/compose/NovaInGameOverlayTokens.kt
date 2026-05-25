package com.papi.nova.ui.compose

/**
 * Shared opacity contract for in-game overlays drawn directly over stream video.
 *
 * Command Center and NovaHUD intentionally use the same glass hierarchy so bright
 * and dark game scenes read as one overlay system instead of separate floating
 * panels with almost-but-not-quite matching alpha values.
 */
object NovaInGameOverlayAlpha {
    const val CommandCenterScrim = 0.42f
    const val GlassPanel = 0.94f
    const val NestedTile = 0.76f
    const val NestedControl = 0.82f
    const val Border = 0.90f
    const val AccentHandle = 0.74f
    const val AccentDivider = 0.35f
    const val SparklineGuide = 0.20f
    const val SparklineFill = 0.18f
}

package com.papi.nova.ui

/**
 * The glass alphas of Nova's panels, one per theme, which [com.papi.nova.ui.compose.NovaComposeTheme]
 * reads for the panel surface.
 *
 * The View sheet and alert chrome that used to live here went with the last View sheet and alert:
 * every Nova overlay is a page in NovaPanelWindow now, whose frame draws the one panel surface,
 * radius and scrim, and whose drawer radius is [com.papi.nova.ui.compose.NovaRadius.drawer].
 */
object NovaSheetChrome {
    /** Default glass opacity for NovaHUD-friendly panels. */
    const val SHEET_GLASS_ALPHA = 0.62f
    const val PORTABLE_CHROME_SHEET_GLASS_ALPHA = 0.58f
    const val MIAMI_SHEET_GLASS_ALPHA = 0.64f
    const val OLED_SHEET_GLASS_ALPHA = 0.70f
    const val MATERIAL_YOU_SHEET_GLASS_ALPHA = 0.60f
    const val HIGH_CONTRAST_SHEET_GLASS_ALPHA = 0.94f
}

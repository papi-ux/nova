package com.papi.nova.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.papi.nova.R

/**
 * Shared Nova bottom-sheet chrome.
 *
 * Keep drawer/sheet surfaces here so theme palettes stay distinct while sheet
 * geometry, scrim, radius, stroke, and D-pad action rows feel like one app.
 */
object NovaSheetChrome {
    const val SHEET_CORNER_RADIUS_DP = 26
    const val LANDSCAPE_WIDTH_FRACTION = 0.70f
    const val SCRIM_ALPHA = 0.22f

    /** Default glass opacity for NovaHUD-friendly drawer overlays. */
    const val SHEET_GLASS_ALPHA = 0.62f
    const val PORTABLE_CHROME_SHEET_GLASS_ALPHA = 0.58f
    const val MIAMI_SHEET_GLASS_ALPHA = 0.64f
    const val OLED_SHEET_GLASS_ALPHA = 0.70f
    const val MATERIAL_YOU_SHEET_GLASS_ALPHA = 0.60f
    const val HIGH_CONTRAST_SHEET_GLASS_ALPHA = 0.94f

    fun createSheetContainer(
        context: Context,
        horizontalPaddingDp: Int = 22,
        topPaddingDp: Int = 18,
        bottomPaddingDp: Int = 26
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(context, horizontalPaddingDp),
                dp(context, topPaddingDp),
                dp(context, horizontalPaddingDp),
                dp(context, bottomPaddingDp)
            )
            background = createSheetBackground(context)
            clipToOutline = true
        }
    }

    fun applyBottomSheetChrome(
        dialog: BottomSheetDialog,
        contentView: View? = null,
        widthFraction: Float = LANDSCAPE_WIDTH_FRACTION,
        minLandscapeWidthDp: Int = 660,
        maxLandscapeWidthDp: Int = 1120,
        maxHeightLandscape: Float = 0.94f,
        maxHeightPortrait: Float = 0.90f
    ) {
        val context = dialog.context
        dialog.window?.let { window ->
            window.setDimAmount(SCRIM_ALPHA)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.background = createSheetBackground(context)
        sheet.clipToOutline = true
        sheet.setPadding(0, 0, 0, 0)
        contentView?.clipToOutline = true

        val measuredView = contentView ?: sheet
        measuredView.post {
            val resources = context.resources
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val density = resources.displayMetrics.density
            val displayWidth = resources.displayMetrics.widthPixels
            val displayHeight = resources.displayMetrics.heightPixels
            val maxHeight = (displayHeight * if (isLandscape) maxHeightLandscape else maxHeightPortrait).toInt()
            val measuredHeight = measuredView.measuredHeight.takeIf { it > 0 } ?: sheet.measuredHeight
            val desiredHeight = measuredHeight.takeIf { it > 0 }?.coerceAtMost(maxHeight) ?: maxHeight
            val minWidth = dp(context, minLandscapeWidthDp)
            val maxWidth = dp(context, maxLandscapeWidthDp)
            val landscapeWidth = (displayWidth * widthFraction).toInt()
                .coerceIn(minWidth.coerceAtMost(displayWidth), maxWidth.coerceAtMost(displayWidth))
                .coerceAtMost(displayWidth - dp(context, 36))
            val desiredWidth = if (isLandscape) landscapeWidth else ViewGroup.LayoutParams.MATCH_PARENT
            val horizontalMargin = if (isLandscape) {
                ((displayWidth - landscapeWidth) / 2).coerceAtLeast(dp(context, 18))
            } else {
                0
            }

            contentView?.layoutParams = contentView.layoutParams?.apply {
                height = if (measuredHeight > maxHeight) desiredHeight else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            sheet.layoutParams = sheet.layoutParams.apply {
                width = desiredWidth
                height = if (measuredHeight > maxHeight) desiredHeight else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            (sheet.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = horizontalMargin
                lp.marginEnd = horizontalMargin
                sheet.layoutParams = lp
            }
            sheet.minimumHeight = 0
            sheet.requestLayout()

            BottomSheetBehavior.from(sheet).apply {
                isFitToContents = true
                skipCollapsed = true
                peekHeight = desiredHeight
                state = BottomSheetBehavior.STATE_EXPANDED
            }

            when (contentView) {
                is NestedScrollView -> contentView.post { contentView.scrollTo(0, 0) }
                is ScrollView -> contentView.post { contentView.scrollTo(0, 0) }
            }
        }
    }

    fun styleSheetTitle(title: TextView) {
        title.setTextColor(NovaThemeManager.getTextPrimaryColor(title.context))
    }

    fun styleSheetAction(action: TextView, destructive: Boolean = false) {
        val context = action.context
        action.setTextColor(
            if (destructive) context.getColor(R.color.nova_error) else NovaThemeManager.getTextPrimaryColor(context)
        )
        action.background = createActionBackground(context)
        action.isClickable = true
        action.isFocusable = true
    }

    fun createSheetBackground(context: Context): GradientDrawable {
        val radius = dp(context, SHEET_CORNER_RADIUS_DP).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(createSheetSurfaceColor(context))
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setStroke(dp(context, 1), getSheetStrokeColor(context))
        }
    }

    fun createSheetSurfaceColor(context: Context): Int {
        val baseSurface = NovaThemeManager.getDialogBackgroundColor(context)
        val alpha = (getSheetGlassAlpha(context) * 255).toInt().coerceIn(0, 255)
        return ColorUtils.setAlphaComponent(baseSurface, alpha)
    }

    fun getSheetGlassAlpha(context: Context): Float {
        return when {
            NovaThemeManager.isHighContrast(context) -> HIGH_CONTRAST_SHEET_GLASS_ALPHA
            NovaThemeManager.isPortableChrome(context) -> PORTABLE_CHROME_SHEET_GLASS_ALPHA
            NovaThemeManager.isMiami(context) -> MIAMI_SHEET_GLASS_ALPHA
            NovaThemeManager.isOled(context) -> OLED_SHEET_GLASS_ALPHA
            NovaThemeManager.isMaterialYou(context) -> MATERIAL_YOU_SHEET_GLASS_ALPHA
            else -> SHEET_GLASS_ALPHA
        }
    }

    fun getSheetStrokeColor(context: Context): Int {
        val surface = NovaThemeManager.getDialogBackgroundColor(context)
        val accent = NovaThemeManager.getAccentColor(context)
        return when {
            NovaThemeManager.isHighContrast(context) -> NovaThemeManager.getDividerColor(context)
            NovaThemeManager.isPortableChrome(context) -> ColorUtils.blendARGB(surface, accent, 0.46f)
            NovaThemeManager.isOled(context) -> ColorUtils.blendARGB(surface, Color.WHITE, 0.12f)
            else -> ColorUtils.blendARGB(surface, accent, 0.32f)
        }
    }

    private fun createActionBackground(context: Context): GradientDrawable {
        val radius = dp(context, 16).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(dp(context, 1), ColorUtils.blendARGB(
                NovaThemeManager.getDialogBackgroundColor(context),
                NovaThemeManager.getAccentColor(context),
                0.18f
            ))
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}

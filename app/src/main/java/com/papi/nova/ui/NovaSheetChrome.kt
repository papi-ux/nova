package com.papi.nova.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.preference.PreferenceManager
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
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
        NovaMenuBlur.attachBehindDialog(dialog, readMenuOpacityPercent(context))
        dialog.window?.let { window ->
            window.setDimAmount(getSheetScrimAlpha(context))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.background = ColorDrawable(Color.TRANSPARENT)
        sheet.clipToOutline = true
        sheet.setPadding(0, 0, 0, 0)
        contentView?.background = createSheetBackground(context)
        contentView?.clipToOutline = true

        val measuredView = contentView ?: sheet
        measuredView.post {
            val resources = context.resources
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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
                isDraggable = false
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


    fun applyMenuOpacityToLegacyAlert(dialog: AlertDialog, destructivePositive: Boolean = false) {
        applyAlertDialogChrome(dialog, destructivePositive)
    }

    fun applyMenuOpacityToLegacyAlert(dialog: AppCompatAlertDialog, destructivePositive: Boolean = false) {
        applyAlertDialogChrome(dialog, destructivePositive)
    }

    fun applyAlertDialogChrome(dialog: AlertDialog, destructivePositive: Boolean = false) {
        val applyChrome = {
            val context = dialog.context
            NovaMenuBlur.attachBehindDialog(dialog, readMenuOpacityPercent(context))
            dialog.window?.let { window ->
                window.setDimAmount(getSheetScrimAlpha(context))
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setBackgroundDrawable(createAlertDialogBackground(context))
            }
            val alertTitleId = context.resources.getIdentifier("alertTitle", "id", "android")
            dialog.findViewById<TextView>(alertTitleId)?.setTextColor(NovaThemeManager.getTextPrimaryColor(context))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(NovaThemeManager.getTextPrimaryColor(context))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { button ->
                button.setTextColor(if (destructivePositive) ContextCompat.getColor(context, R.color.nova_error) else NovaThemeManager.getAccentColor(context))
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { button ->
                button.setTextColor(NovaThemeManager.getTextSecondaryColor(context))
            }
        }
        if (dialog.isShowing) applyChrome() else dialog.setOnShowListener { applyChrome() }
    }

    fun applyAlertDialogChrome(dialog: AppCompatAlertDialog, destructivePositive: Boolean = false) {
        val applyChrome = {
            val context = dialog.context
            NovaMenuBlur.attachBehindDialog(dialog, readMenuOpacityPercent(context))
            dialog.window?.let { window ->
                window.setDimAmount(getSheetScrimAlpha(context))
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setBackgroundDrawable(createAlertDialogBackground(context))
            }
            val alertTitleId = context.resources.getIdentifier("alertTitle", "id", "android")
            dialog.findViewById<TextView>(alertTitleId)?.setTextColor(NovaThemeManager.getTextPrimaryColor(context))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(NovaThemeManager.getTextPrimaryColor(context))
            dialog.getButton(AppCompatAlertDialog.BUTTON_POSITIVE)?.let { button ->
                button.setTextColor(if (destructivePositive) ContextCompat.getColor(context, R.color.nova_error) else NovaThemeManager.getAccentColor(context))
            }
            dialog.getButton(AppCompatAlertDialog.BUTTON_NEGATIVE)?.let { button ->
                button.setTextColor(NovaThemeManager.getTextSecondaryColor(context))
            }
        }
        if (dialog.isShowing) applyChrome() else dialog.setOnShowListener { applyChrome() }
    }

    fun createAlertDialogBackground(context: Context): GradientDrawable {
        val radius = dp(context, SHEET_CORNER_RADIUS_DP).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(createSheetSurfaceColor(context))
            cornerRadius = radius
            setStroke(dp(context, 1), getSheetStrokeColor(context))
        }
    }

    fun styleSheetTitle(title: TextView) {
        title.setTextColor(NovaThemeManager.getTextPrimaryColor(title.context))
    }

    fun styleSheetAction(action: TextView, destructive: Boolean = false) {
        val context = action.context
        action.setTextColor(
            if (destructive) ContextCompat.getColor(context, R.color.nova_error) else NovaThemeManager.getTextPrimaryColor(context)
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
        val opacityPercent = readMenuOpacityPercent(context)
        val usesDarkText = ColorUtils.calculateLuminance(
            NovaThemeManager.getTextPrimaryColor(context)
        ) < 0.5
        val alpha = NovaMenuPreferences.outerSurfaceAlpha(
            opacityPercent = opacityPercent,
            usesDarkText = usesDarkText
        )
        return ColorUtils.setAlphaComponent(baseSurface, (alpha * 255f).toInt().coerceIn(0, 255))
    }

    fun getSheetScrimAlpha(context: Context): Float {
        return NovaMenuPreferences.readabilityScrimAlpha(
            SCRIM_ALPHA,
            readMenuOpacityPercent(context)
        )
    }

    fun getSheetGlassAlpha(context: Context): Float {
        val usesDarkText = ColorUtils.calculateLuminance(
            NovaThemeManager.getTextPrimaryColor(context)
        ) < 0.5
        return NovaMenuPreferences.outerSurfaceAlpha(
            opacityPercent = readMenuOpacityPercent(context),
            usesDarkText = usesDarkText
        )
    }

    fun getSheetStrokeColor(context: Context): Int {
        val surface = NovaThemeManager.getDialogBackgroundColor(context)
        val accent = NovaThemeManager.getAccentColor(context)
        val themedStroke = when {
            NovaThemeManager.isHighContrast(context) -> NovaThemeManager.getDividerColor(context)
            NovaThemeManager.isPortableChrome(context) -> ColorUtils.blendARGB(surface, accent, 0.46f)
            NovaThemeManager.isOled(context) -> ColorUtils.blendARGB(surface, Color.WHITE, 0.12f)
            else -> ColorUtils.blendARGB(surface, accent, 0.32f)
        }
        val opacityPercent = readMenuOpacityPercent(context)
        if (opacityPercent == NovaMenuPreferences.MAX_OPACITY_PERCENT) {
            return themedStroke
        }
        val scaledAlpha = NovaMenuPreferences.alphaByte(
            Color.alpha(themedStroke) / 255f,
            opacityPercent
        )
        return ColorUtils.setAlphaComponent(themedStroke, scaledAlpha)
    }

    private fun readMenuOpacityPercent(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return NovaMenuPreferences.readOpacityPercent(prefs)
    }

    fun createActionBackground(context: Context): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                createActionStateBackground(context, fillAccentBlend = 0.24f, strokeAccentBlend = 0.58f)
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                createActionStateBackground(context, fillAccentBlend = 0.18f, strokeAccentBlend = 0.50f)
            )
            addState(
                intArrayOf(),
                createActionStateBackground(context, fillAccentBlend = 0f, strokeAccentBlend = 0.18f)
            )
        }
    }

    fun createHandleBackground(context: Context): GradientDrawable {
        val radius = dp(context, 4).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ColorUtils.setAlphaComponent(NovaThemeManager.getAccentColor(context), 0xA8))
            cornerRadius = radius
        }
    }

    fun attachHandleDragToDismiss(handle: View, dialog: BottomSheetDialog) {
        val touchSlop = ViewConfiguration.get(handle.context).scaledTouchSlop
        val dismissThreshold = dp(handle.context, 42).toFloat()
        var downY = 0f
        var consumedDrag = false
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    consumedDrag = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dragDistance = event.rawY - downY
                    if (dragDistance > touchSlop) {
                        consumedDrag = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dragDistance = event.rawY - downY
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragDistance >= dismissThreshold) {
                        dialog.dismiss()
                    } else if (!consumedDrag) {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
    }

    private fun createActionStateBackground(
        context: Context,
        fillAccentBlend: Float,
        strokeAccentBlend: Float
    ): GradientDrawable {
        val radius = dp(context, 16).toFloat()
        val surface = createSheetSurfaceColor(context)
        val accent = NovaThemeManager.getAccentColor(context)
        val preservesFocusCue = fillAccentBlend > 0f
        val menuOpacityScale = NovaMenuPreferences.opacityScale(readMenuOpacityPercent(context))
        val effectiveStrokeBlend = if (preservesFocusCue) {
            strokeAccentBlend
        } else {
            strokeAccentBlend * menuOpacityScale
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (fillAccentBlend > 0f) ColorUtils.blendARGB(surface, accent, fillAccentBlend) else Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(dp(context, 1), ColorUtils.blendARGB(surface, accent, effectiveStrokeBlend))
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}

package com.papi.nova.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.papi.nova.R
import com.papi.nova.utils.MouseModeOption
import com.papi.nova.utils.UiHelper

/** Nova's stream sheet, with the active mode visible and focused before the first press. */
internal object NovaMouseModePicker {
    fun create(
        context: Context,
        options: List<MouseModeOption>,
        currentModeIndex: Int,
        onSelect: (MouseModeOption) -> Unit,
    ): BottomSheetDialog {
        val sheet = BottomSheetDialog(context)
        val content = NovaSheetChrome.createSheetContainer(context)
        val scroll = ScrollView(context)
        val title = TextView(context).apply {
            setText(R.string.game_menu_select_mouse_mode)
            textSize = 20f
            NovaSheetChrome.styleSheetTitle(this)
        }
        content.addView(title)
        val actions = options.map { option ->
            TextView(context).apply {
                id = View.generateViewId()
                tag = "nova-mouse-mode-${option.index}"
                text = option.label
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(context, 48)
                setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
                NovaSheetChrome.styleSheetAction(this)
                isSelected = option.index == currentModeIndex
                contentDescription = if (isSelected) {
                    "${option.label}, ${context.getString(R.string.nova_settings_current_badge)}"
                } else option.label
                if (isSelected) {
                    val check = ContextCompat.getDrawable(context, R.drawable.ic_check)?.mutate()
                    check?.let { DrawableCompat.setTint(it, NovaThemeManager.getAccentColor(context)) }
                    setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, check, null)
                    compoundDrawablePadding = dp(context, 12)
                }
                setOnClickListener {
                    sheet.dismiss()
                    onSelect(option)
                }
                content.addView(this, actionLayout(context))
            }
        }.toMutableList()
        val cancel = TextView(context).apply {
            id = View.generateViewId()
            tag = "nova-mouse-mode-cancel"
            setText(R.string.game_menu_cancel)
            gravity = Gravity.CENTER
            minHeight = dp(context, 48)
            NovaSheetChrome.styleSheetAction(this)
            setOnClickListener { sheet.cancel() }
        }
        content.addView(cancel, actionLayout(context))
        actions.add(cancel)
        actions.forEachIndexed { index, action ->
            action.nextFocusUpId = actions[(index - 1).coerceAtLeast(0)].id
            action.nextFocusDownId = actions[(index + 1).coerceAtMost(actions.lastIndex)].id
        }
        scroll.addView(content)
        sheet.setContentView(scroll)
        val initialAction = actions.firstOrNull { it.isSelected } ?: actions.first()
        sheet.setOnShowListener {
            NovaSheetChrome.applyBottomSheetChrome(sheet, scroll, minLandscapeWidthDp = 520, maxLandscapeWidthDp = 820)
            NovaStreamSheetFocus.onShow(sheet, initialAction)
        }
        return sheet
    }

    private fun dp(context: Context, value: Int): Int = UiHelper.dpToPx(context, value.toFloat()).toInt()

    private fun actionLayout(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(context, 6) }
}

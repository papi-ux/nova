package com.papi.nova.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.papi.nova.R

/**
 * Shared companion-display chrome. The view fills its parent only as a transparent layout host;
 * interactive chrome is constrained to the top status strip and bottom action rail so the
 * underlying [ExternalControllerView] remains the touchpad owner everywhere else.
 */
class NovaCompanionCommandDeckView(
    context: Context,
    private val onAction: (NovaCompanionCommandActionId) -> Unit,
) : FrameLayout(context) {
    private val statusRow = LinearLayout(context)
    private val actionRail = LinearLayout(context)
    private val actionViews = linkedMapOf<NovaCompanionCommandActionId, View>()

    private val touchpadText = createStatusText()
    private val sessionText = createStatusText()
    private val displayText = createStatusText()
    private val fpsText = createStatusText()
    private val targetFpsText = createStatusText()
    private val latencyText = createStatusText()
    private val bitrateText = createStatusText()
    private val codecText = createStatusText()
    private val resolutionText = createStatusText()
    private val profileText = createStatusText()

    private var renderedActionOrder = emptyList<NovaCompanionCommandActionId>()
    private var initialFocusRequested = false
    private var latestState: NovaCompanionCommandDeckState? = null

    init {
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false

        val statusScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            isFocusable = false
            background = stripBackground()
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        statusRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            listOf(
                touchpadText,
                sessionText,
                displayText,
                fpsText,
                targetFpsText,
                latencyText,
                bitrateText,
                codecText,
                resolutionText,
                profileText,
            ).forEach { addView(it) }
        }
        statusScroll.addView(
            statusRow,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            statusScroll,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP),
        )

        val actionScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            background = stripBackground()
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        actionRail.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionScroll.addView(
            actionRail,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            actionScroll,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112), Gravity.BOTTOM),
        )
    }

    fun render(state: NovaCompanionCommandDeckState) {
        // This runs once per perf interval for as long as an external display is
        // attached, and most intervals change nothing. Ten getString + setText calls and
        // the layout pass they trigger are not free on a handheld that is also decoding a
        // video stream. The state is a data class, so an unchanged interval costs one
        // comparison.
        val unchanged = latestState == state
        latestState = state
        if (unchanged) {
            requestInitialFocus(state)
            return
        }
        alpha = if (state.dimmed) 0.55f else 1f
        touchpadText.text = if (state.touchpadActive) {
            context.getString(R.string.companion_deck_touchpad_active)
        } else {
            context.getString(R.string.companion_deck_touchpad)
        }
        sessionText.text = context.getString(R.string.companion_deck_status_session, state.session)
        displayText.text = context.getString(R.string.companion_deck_status_display, state.displayRole)
        fpsText.text = context.getString(R.string.companion_deck_status_fps, state.actualFps)
        targetFpsText.text = context.getString(R.string.companion_deck_status_target_fps, state.targetFps)
        latencyText.text = context.getString(R.string.companion_deck_status_latency, state.latency)
        bitrateText.text = context.getString(R.string.companion_deck_status_bitrate, state.bitrate)
        codecText.text = context.getString(R.string.companion_deck_status_codec, state.codec)
        resolutionText.text = context.getString(R.string.companion_deck_status_resolution, state.resolution)
        profileText.text = context.getString(R.string.companion_deck_status_profile, state.profile)

        val actionOrder = state.actions.map { it.id }
        if (renderedActionOrder != actionOrder) {
            rebuildActionRail(state.actions)
            renderedActionOrder = actionOrder
        } else {
            state.actions.forEach { action ->
                actionViews[action.id]?.let { view -> updateActionView(view, action) }
            }
        }
        requestInitialFocus(state)
    }

    fun restoreSafeActionFocus() {
        initialFocusRequested = false
        latestState?.let(::requestInitialFocus)
    }

    fun requestInitialFocus(state: NovaCompanionCommandDeckState) {
        if (initialFocusRequested) return
        val initialView = state.initialFocusActionId()?.let(actionViews::get) ?: return
        initialFocusRequested = true
        initialView.post {
            if (!initialView.isAttachedToWindow || !initialView.isEnabled) {
                initialFocusRequested = false
            } else if (!initialView.hasFocus() && !initialView.requestFocus()) {
                initialFocusRequested = false
            }
        }
    }

    private fun rebuildActionRail(actions: List<NovaCompanionCommandAction>) {
        actionRail.removeAllViews()
        actionViews.clear()
        actions.forEach { action ->
            val actionView = createActionView(action)
            actionViews[action.id] = actionView
            actionRail.addView(
                actionView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dp(6)
                },
            )
        }
        actionViews.values.toList().forEachIndexed { index, view ->
            view.nextFocusLeftId = actionViews.values.elementAtOrNull(index - 1)?.id ?: View.NO_ID
            view.nextFocusRightId = actionViews.values.elementAtOrNull(index + 1)?.id ?: View.NO_ID
        }
    }

    private fun createActionView(action: NovaCompanionCommandAction): View {
        val labelRes = actionLabel(action.id)
        val tint = if (action.destructive) {
            NovaThemeManager.getErrorColor(context)
        } else {
            NovaThemeManager.getAccentColor(context)
        }
        return LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = dp(108)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            isEnabled = action.enabled
            alpha = if (action.enabled) 1f else 0.4f
            contentDescription = context.getString(labelRes)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = actionBackground(tint)
            setPadding(dp(10), dp(8), dp(10), dp(6))

            addView(
                ImageView(context).apply {
                    setImageResource(actionIcon(action.id))
                    imageTintList = ColorStateList.valueOf(tint)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(28), dp(28)),
            )
            addView(
                TextView(context).apply {
                    setText(labelRes)
                    setTextColor(NovaThemeManager.getTextPrimaryColor(context))
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                },
            )
            setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onAction(action.id)
            }
            updateActionView(this, action)
        }
    }

    private fun updateActionView(view: View, action: NovaCompanionCommandAction) {
        view.isEnabled = action.enabled
        view.isSelected = action.selected
        view.alpha = if (action.enabled) 1f else 0.4f
        val reportsSelection = when (action.id) {
            NovaCompanionCommandActionId.ANDROID_KEYBOARD,
            NovaCompanionCommandActionId.NOVA_KEYBOARD,
            NovaCompanionCommandActionId.NOVA_HUD,
            NovaCompanionCommandActionId.ZOOM_PAN,
            -> true
            else -> false
        }
        ViewCompat.setStateDescription(
            view,
            if (reportsSelection) {
                context.getString(
                    if (action.selected) R.string.companion_deck_state_active
                    else R.string.companion_deck_state_inactive,
                )
            } else {
                null
            },
        )
    }

    private fun createStatusText(): TextView = TextView(context).apply {
        setTextColor(NovaThemeManager.getTextPrimaryColor(context))
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun stripBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(NovaThemeManager.getCardBackgroundColor(context))
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), NovaThemeManager.getDividerColor(context))
    }

    private fun actionBackground(accent: Int): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            roundedBackground(NovaThemeManager.getAccentSurfaceColor(context), accent, dp(3)),
        )
        addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedBackground(NovaThemeManager.getAccentSurfaceColor(context), accent, dp(2)),
        )
        addState(
            intArrayOf(android.R.attr.state_selected),
            roundedBackground(NovaThemeManager.getAccentSurfaceColor(context), accent, dp(2)),
        )
        addState(
            intArrayOf(),
            roundedBackground(
                NovaThemeManager.getCardBackgroundColor(context),
                NovaThemeManager.getDividerColor(context),
                dp(1),
            ),
        )
    }

    private fun roundedBackground(fill: Int, stroke: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(12).toFloat()
            setStroke(strokeWidth, stroke)
        }

    private fun actionLabel(id: NovaCompanionCommandActionId): Int = when (id) {
        NovaCompanionCommandActionId.ANDROID_KEYBOARD -> R.string.companion_deck_android_keyboard
        NovaCompanionCommandActionId.NOVA_KEYBOARD -> R.string.companion_deck_nova_keyboard
        NovaCompanionCommandActionId.QUICK_KEYS -> R.string.companion_deck_quick_keys
        NovaCompanionCommandActionId.NOVA_HUD -> R.string.companion_deck_nova_hud
        NovaCompanionCommandActionId.ZOOM_PAN -> R.string.companion_deck_zoom_pan
        NovaCompanionCommandActionId.COMMAND_CENTER -> R.string.companion_deck_command_center
        NovaCompanionCommandActionId.HIDE_COMPANION -> R.string.companion_deck_hide_companion
        NovaCompanionCommandActionId.DISCONNECT -> R.string.companion_deck_disconnect
        NovaCompanionCommandActionId.END_SESSION -> R.string.companion_deck_end_session
    }

    private fun actionIcon(id: NovaCompanionCommandActionId): Int = when (id) {
        NovaCompanionCommandActionId.ANDROID_KEYBOARD -> R.drawable.ic_android_keyboard
        NovaCompanionCommandActionId.NOVA_KEYBOARD -> R.drawable.ic_fullscreen_keyboard
        NovaCompanionCommandActionId.QUICK_KEYS -> R.drawable.ic_keyboard_setting
        NovaCompanionCommandActionId.NOVA_HUD -> R.drawable.ic_hud_bg
        NovaCompanionCommandActionId.ZOOM_PAN -> R.drawable.ic_zoom_toggle
        NovaCompanionCommandActionId.COMMAND_CENTER -> R.drawable.ic_menu_external
        NovaCompanionCommandActionId.HIDE_COMPANION -> R.drawable.ic_menu_collapse
        NovaCompanionCommandActionId.DISCONNECT -> R.drawable.ic_close_external
        NovaCompanionCommandActionId.END_SESSION -> R.drawable.ic_close
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

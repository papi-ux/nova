package com.papi.nova.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.papi.nova.R
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.panel.NovaSplitConfirm
import com.papi.nova.ui.panel.NovaSplitConfirmState
import com.papi.nova.ui.panel.NovaSplitShape

/**
 * Shared companion-display chrome. The view fills its parent only as a transparent layout host;
 * interactive chrome is constrained to the top status strip and bottom action rail so the
 * underlying [ExternalControllerView] remains the touchpad owner everywhere else.
 *
 * The strip and the rail wrap onto more lines rather than scrolling sideways, so no status line
 * or tile is ever cut at the edge at rest (R13). Each is capped to a share of the deck's height
 * and scrolls up and down past it, which only an extreme font scale on a tiny display reaches.
 *
 * End Session confirms in its own tile: A or a tap splits it into Stay and End Session, and End
 * ignores activation for a moment after arming. [endSessionSplit] is hoisted so the deck's owner
 * can disarm it from its own Back handling. A companion window is not a ComponentActivity, so
 * [composeOwner] lends the tile its lifecycle and saved state.
 */
class NovaCompanionCommandDeckView(
    context: Context,
    private val endSessionSplit: NovaSplitConfirmState = NovaSplitConfirmState(),
    private val composeOwner: ComponentActivity? = null,
    private val onAction: (NovaCompanionCommandActionId) -> Unit,
) : FrameLayout(context) {
    // Status lines carry their own side padding, so they sit edge to edge.
    private val statusRow = NovaDeckFlowLayout(context, gapPx = 0)
    // Tiles share a row in equal cells, at least a tile's width each, so the deck's tiles take two
    // rows on a handheld's companion screen and one on a monitor.
    private val actionRail = NovaDeckFlowLayout(context, gapPx = dp(TILE_GAP_DP), cellMinWidthPx = dp(TILE_MIN_WIDTH_DP))
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
    private val endSessionEnabled = mutableStateOf(true)

    init {
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false

        val statusScroll = NovaDeckCappedScroll(context, STATUS_HEIGHT_SHARE).apply {
            isFocusable = false
            background = stripBackground()
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        statusRow.apply {
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            statusScroll,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )

        val actionScroll = NovaDeckCappedScroll(context, RAIL_HEIGHT_SHARE).apply {
            isFocusable = false
            background = stripBackground()
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        actionScroll.addView(
            actionRail,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            actionScroll,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
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
        endSessionSplit.disarm(restoreFocus = false)
        actions.forEach { action ->
            val actionView = createActionView(action)
            actionViews[action.id] = actionView
            // Every tile is at least a split tile tall and takes one cell of its row; the End
            // Session tile takes the whole row while it is armed (see createEndSessionTile).
            actionRail.addView(
                actionView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        actionViews.values.toList().forEachIndexed { index, view ->
            view.nextFocusLeftId = actionViews.values.elementAtOrNull(index - 1)?.id ?: View.NO_ID
            view.nextFocusRightId = actionViews.values.elementAtOrNull(index + 1)?.id ?: View.NO_ID
        }
    }

    private fun createActionView(action: NovaCompanionCommandAction): View {
        if (action.id == NovaCompanionCommandActionId.END_SESSION) return createEndSessionTile(action)
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
            minimumWidth = dp(TILE_MIN_WIDTH_DP)
            minimumHeight = dp(TILE_MIN_HEIGHT_DP)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            isEnabled = action.enabled
            alpha = if (action.enabled) 1f else 0.4f
            contentDescription = context.getString(labelRes)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = actionBackground(tint)
            setPadding(dp(6), dp(8), dp(6), dp(6))

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
                    // A label wraps inside its cell on as many lines as it takes, never cut.
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

    /**
     * End Session as a split tile. Its own touch and keys confirm it; the deck forwards nothing
     * to it. On confirm it runs the deck's action, which ends the session without asking again.
     * At rest it is a tile in its cell; armed, it takes the rail's whole row, as a split does when
     * its halves would be narrower than 96dp (R3), and the tiles around it reflow.
     */
    private fun createEndSessionTile(action: NovaCompanionCommandAction): View = ComposeView(context).apply {
        id = View.generateViewId()
        tag = END_SESSION_TILE_TAG
        composeOwner?.let { owner ->
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
        }
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        val label = context.getString(R.string.companion_deck_end_session)
        endSessionEnabled.value = action.enabled
        setContent {
            NovaComposeTheme {
                val armed = endSessionSplit.armed
                LaunchedEffect(armed) { spanRail(this@apply, armed) }
                NovaSplitConfirm(
                    label = label,
                    confirmLabel = label,
                    onConfirm = {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onAction(NovaCompanionCommandActionId.END_SESSION)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = actionIcon(NovaCompanionCommandActionId.END_SESSION),
                    shape = NovaSplitShape.Tile,
                    enabled = endSessionEnabled.value,
                    state = endSessionSplit,
                )
            }
        }
    }

    private fun spanRail(tile: View, armed: Boolean) {
        val params = tile.layoutParams ?: return
        val width = if (armed) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        if (params.width == width) return
        params.width = width
        tile.layoutParams = params
        // A row of its own can take the rail past its share of a small display, where the rail
        // scrolls: bring the armed pair into view once it has its row.
        if (armed) tile.post { tile.requestRectangleOnScreen(Rect(0, 0, tile.width, tile.height), true) }
    }

    private fun updateActionView(view: View, action: NovaCompanionCommandAction) {
        if (view.tag == END_SESSION_TILE_TAG) {
            endSessionEnabled.value = action.enabled
            if (!action.enabled) endSessionSplit.disarm(restoreFocus = false)
            return
        }
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

    // A status line wraps inside its line of the strip rather than being cut at its edge.
    private fun createStatusText(): TextView = TextView(context).apply {
        setTextColor(NovaThemeManager.getTextPrimaryColor(context))
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun stripBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(NovaThemeManager.getCardBackgroundColor(context))
        cornerRadius = resources.getDimension(R.dimen.nova_radius_row)
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

    // The row corner of the panel foundation's tiles, the same corner the End tile has at rest.
    private fun roundedBackground(fill: Int, stroke: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = resources.getDimension(R.dimen.nova_radius_row)
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

    companion object {
        /** Tag of the End Session tile, a ComposeView holding its split. */
        const val END_SESSION_TILE_TAG = "nova-deck-end-session"

        /**
         * A tile's narrowest cell: five across a handheld's companion screen, so its nine tiles
         * take two rows and a label's longest word still fits a line.
         */
        private const val TILE_MIN_WIDTH_DP = 72

        /** A split tile's height, which every tile keeps so the rail's rows read as rows. */
        private const val TILE_MIN_HEIGHT_DP = 72

        /** Between tiles, across a row and between rows. */
        private const val TILE_GAP_DP = 6

        /**
         * The most of the deck's height the status strip and the rail may take before they scroll:
         * room for the strip's lines and for two rows of tiles on a handheld's companion screen,
         * while the middle of the deck always stays the touchpad's.
         */
        private const val STATUS_HEIGHT_SHARE = 0.25f
        private const val RAIL_HEIGHT_SHARE = 0.45f
    }
}

/**
 * Lays its children out in rows, start to end, beginning a new row where the next child would
 * not fit and centring each row, so no child is ever cut at an edge (R13). A child is never
 * measured wider than a row, and one whose width is MATCH_PARENT takes a row of its own. With a
 * [cellMinWidthPx], every other child takes one of the equal cells that fit a row, each at least
 * that wide; without one, each takes its own width. The tallest child sets its row's height.
 */
internal class NovaDeckFlowLayout(
    context: Context,
    private val gapPx: Int,
    private val cellMinWidthPx: Int = 0,
) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val available = if (bounded) {
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        } else {
            Int.MAX_VALUE
        }
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val cell = if (bounded) cellWidth(available) else 0
        for (child in visibleChildren()) {
            val requestedHeight = child.layoutParams.height
            val heightSpec = if (requestedHeight >= 0) {
                MeasureSpec.makeMeasureSpec(requestedHeight, MeasureSpec.EXACTLY)
            } else {
                unspecified
            }
            val requested = child.layoutParams.width
            val widthSpec = when {
                requested == LayoutParams.MATCH_PARENT && bounded -> MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY)
                requested >= 0 && bounded -> MeasureSpec.makeMeasureSpec(minOf(requested, available), MeasureSpec.EXACTLY)
                requested >= 0 -> MeasureSpec.makeMeasureSpec(requested, MeasureSpec.EXACTLY)
                cell > 0 -> MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
                bounded -> MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
                else -> unspecified
            }
            child.measure(widthSpec, heightSpec)
        }
        val rows = rows(available)
        val contentWidth = rows.maxOfOrNull { rowWidth(it) } ?: 0
        val contentHeight = rows.sumOf { row -> row.maxOf { it.measuredHeight } } + gapPx * (rows.size - 1).coerceAtLeast(0)
        setMeasuredDimension(
            resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var y = paddingTop
        for (row in rows(available)) {
            val height = row.maxOf { it.measuredHeight }
            var x = paddingLeft + (available - rowWidth(row)) / 2
            for (child in if (rtl) row.asReversed() else row) {
                val top = y + (height - child.measuredHeight) / 2
                child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
                x += child.measuredWidth + gapPx
            }
            y += height + gapPx
        }
    }

    /** The width of each of the equal cells that fit [available], or 0 without a cell minimum. */
    private fun cellWidth(available: Int): Int {
        if (cellMinWidthPx <= 0) return 0
        val columns = ((available + gapPx) / (cellMinWidthPx + gapPx)).coerceAtLeast(1)
        return ((available - gapPx * (columns - 1)) / columns).coerceAtLeast(0)
    }

    private fun visibleChildren(): List<View> =
        (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }

    private fun rows(available: Int): List<List<View>> {
        val rows = mutableListOf<MutableList<View>>()
        var width = 0
        for (child in visibleChildren()) {
            val row = rows.lastOrNull()
            if (row == null || width + gapPx + child.measuredWidth > available) {
                rows += mutableListOf(child)
                width = child.measuredWidth
            } else {
                row += child
                width += gapPx + child.measuredWidth
            }
        }
        return rows
    }

    private fun rowWidth(row: List<View>): Int = row.sumOf { it.measuredWidth } + gapPx * (row.size - 1)
}

/**
 * A vertical scroll that takes at most [maxShare] of the height its parent offers, so a strip
 * that wraps onto many lines on a tiny display still leaves the touchpad most of the screen.
 */
internal class NovaDeckCappedScroll(context: Context, private val maxShare: Float) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) * maxShare).toInt(), MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, capped)
    }
}

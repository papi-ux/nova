package com.papi.nova.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Hosts' View consumer of Control Size. Text size and physical density remain the context's. */
internal class NovaHostsViewMetrics(context: Context) {
    private val hitFloor = (48 * context.resources.displayMetrics.density).roundToInt()
    private data class Baseline(
        val start: Int, val top: Int, val end: Int, val bottom: Int,
        val marginStart: Int, val marginTop: Int, val marginEnd: Int, val marginBottom: Int,
        val width: Int, val height: Int, val minWidth: Int, val minHeight: Int,
        val radius: Float?, val iconPadding: Int, val insetTop: Int, val insetBottom: Int,
    )
    private val originals = IdentityHashMap<View, Baseline>()

    /** Capture once, before styling/rail arrangement. Each new inflation gets its own originals. */
    fun capture(root: View) {
        if (!originals.containsKey(root)) {
            val margins = root.layoutParams as? ViewGroup.MarginLayoutParams
            val button = root as? MaterialButton
            originals[root] = Baseline(root.paddingStart, root.paddingTop, root.paddingEnd, root.paddingBottom,
                margins?.marginStart ?: 0, margins?.topMargin ?: 0, margins?.marginEnd ?: 0, margins?.bottomMargin ?: 0,
                root.layoutParams?.width ?: -2, root.layoutParams?.height ?: -2, root.minimumWidth, root.minimumHeight,
                button?.cornerRadius?.takeIf { it >= 0 }?.toFloat() ?: (root as? MaterialCardView)?.radius,
                button?.iconPadding ?: 0, button?.insetTop ?: 0, button?.insetBottom ?: 0)
        }
        if (root is ViewGroup) for (index in 0 until root.childCount) capture(root.getChildAt(index))
    }

    fun apply(size: NovaControlSize) {
        val factor = size.layoutScale
        for ((view, base) in originals) {
            fun scaled(value: Int) = (value * factor).roundToInt()
            // The 1dp focus bridge is a navigation marker, not a button. Turning a plain View
            // from fixed height into wrap_content fills its entire AT_MOST parent on Android.
            val target = view.isClickable || view is MaterialButton
            val button = view as? MaterialButton
            var insetTop = 0
            var insetBottom = 0
            if (button != null) {
                // A short visual button lives inside its own >=48dp slot, never an overlapping
                // delegate. Preserve its original visual height through background insets.
                val visualHeight = (if (base.height > 0) base.height else base.minHeight)
                    .minus(base.insetTop + base.insetBottom).coerceAtLeast(0)
                val scaledVisual = scaled(visualHeight)
                val extra = (hitFloor - scaledVisual).coerceAtLeast(0)
                insetTop = scaled(base.insetTop) + extra / 2
                insetBottom = scaled(base.insetBottom) + extra - extra / 2
                button.insetTop = insetTop
                button.insetBottom = insetBottom
                base.radius?.let { button.cornerRadius = scaled(it.roundToInt()) }
                button.iconPadding = scaled(base.iconPadding)
            } else if (view is MaterialCardView) {
                base.radius?.let { view.radius = it * factor }
            }
            view.setPaddingRelative(scaled(base.start), scaled(base.top - base.insetTop) + insetTop,
                scaled(base.end), scaled(base.bottom - base.insetBottom) + insetBottom)
            view.minimumWidth = max(scaled(base.minWidth), if (target) hitFloor else 0)
            val originalHeight = if (base.height > 0) base.height else base.minHeight
            view.minimumHeight = max(scaled(originalHeight), if (target) hitFloor else 0)
            val params = view.layoutParams ?: continue
            if (target && base.width > 0) params.width = max(scaled(base.width), hitFloor)
            val containsTargets = view is ViewGroup && (0 until view.childCount).any {
                view.getChildAt(it).let { child -> child.isClickable || child is MaterialButton }
            }
            if ((target || containsTargets || view is TextView) && base.height > 0) {
                // Wrap text if Android text needs more height; the original shape sets a floor.
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                view.minimumHeight = max(view.minimumHeight, max(scaled(base.height), if (target || containsTargets) hitFloor else 0))
            }
            if (params is ViewGroup.MarginLayoutParams) {
                params.marginStart = scaled(base.marginStart)
                params.topMargin = scaled(base.marginTop)
                params.marginEnd = scaled(base.marginEnd)
                params.bottomMargin = scaled(base.marginBottom)
            }
            view.layoutParams = params
        }
    }

    /** Actual padding delta, excluding text; the card retains its independent text-width rule. */
    fun horizontalPaddingDelta(vararg views: View?): Int = views.filterNotNull().sumOf { view ->
        val base = originals[view] ?: return@sumOf 0
        view.paddingStart + view.paddingEnd - base.start - base.end
    }

    fun originalPaddingStart(view: View): Int = originals[view]?.start ?: view.paddingStart

    fun originalPaddingEnd(view: View): Int = originals[view]?.end ?: view.paddingEnd
}

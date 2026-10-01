package com.papi.nova.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.AdapterView
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import java.util.WeakHashMap
import kotlin.math.ceil

/** Bounded Hosts control/header reflow; collection positioning and outer side edges stay intact. */
internal object NovaCameraViewAvoidance {
    fun install(root: View, cameraBounds: () -> List<Rect> = {
        ViewCompat.getRootWindowInsets(root)?.displayCutout?.boundingRects.orEmpty()
    }) {
        val margins = WeakHashMap<View, Margin>()
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val cameras = cameraBounds()
            val wanted = linkedMapOf<View, Margin>()
            visit(root) { view ->
                if (view.visibility != View.VISIBLE || isViewport(view)) return@visit
                val interactive = view.isClickable || view.isFocusable
                if (!interactive && (view !is TextView || interactiveAncestor(view, root))) return@visit
                val slot = verticalFlowSlot(view, root) ?: return@visit
                val params = slot.layoutParams as? ViewGroup.MarginLayoutParams ?: return@visit
                val previous = margins[slot]
                val base = if (previous != null && params.topMargin == previous.base + previous.camera) previous.base else params.topMargin
                val ownCamera = if (previous != null && params.topMargin == previous.base + previous.camera) previous.camera else 0
                val at = IntArray(2)
                view.getLocationInWindow(at)
                val bounds = Rect(at[0], at[1] - ownCamera, at[0] + view.width, at[1] - ownCamera + view.height)
                if (interactive) {
                    val floor = ceil(48 * view.resources.displayMetrics.density).toInt()
                    val horizontal = ((floor - view.width).coerceAtLeast(0) + 1) / 2
                    val vertical = ((floor - view.height).coerceAtLeast(0) + 1) / 2
                    bounds.inset(-horizontal, -vertical)
                }
                // A plain label's allocated width can include empty space beside the camera. Its
                // actual glyph bounds matter; a clickable button keeps its entire target clear.
                if (!interactive && view is TextView && view.layout != null && view.layout.lineCount > 0) {
                    val text = view.layout
                    bounds.left += view.totalPaddingLeft + (0 until text.lineCount).minOf { text.getLineLeft(it) }.toInt()
                    bounds.right = at[0] + view.totalPaddingLeft + ceil((0 until text.lineCount).maxOf { text.getLineRight(it) }).toInt()
                    bounds.top += view.totalPaddingTop
                    bounds.bottom = bounds.top + text.height
                }
                val clearance = cameras.filter { Rect.intersects(bounds, it) }
                    .maxOfOrNull { (it.bottom - bounds.top).coerceAtLeast(0) } ?: 0
                wanted[slot] = Margin(base, maxOf(clearance, wanted[slot]?.camera ?: 0))
            }
            // Several controls can share a header's flow slot. One clearance moves that slot and
            // every descendant; nested slots must not add the same camera height again.
            for ((slot, wantedMargin) in wanted) {
                var ancestor = slot.parent as? View
                var covered = false
                while (ancestor != null && ancestor !== root) {
                    if ((wanted[ancestor]?.camera ?: 0) > 0) covered = true
                    ancestor = ancestor.parent as? View
                }
                val result = if (covered) wantedMargin.copy(camera = 0) else wantedMargin
                margins[slot] = result
                val params = slot.layoutParams as ViewGroup.MarginLayoutParams
                if (params.topMargin != result.base + result.camera) {
                    params.topMargin = result.base + result.camera
                    slot.layoutParams = params
                }
            }
        }
        // Scrolling changes target window coordinates without requiring a layout pass.
        val scrollListener = ViewTreeObserver.OnScrollChangedListener { listener.onGlobalLayout() }
        var observer: ViewTreeObserver? = null
        fun attach() {
            observer = root.viewTreeObserver.also {
                it.addOnGlobalLayoutListener(listener)
                it.addOnScrollChangedListener(scrollListener)
            }
        }
        if (ViewCompat.isAttachedToWindow(root)) attach()
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { attach() }
            override fun onViewDetachedFromWindow(view: View) {
                observer?.takeIf { it.isAlive }?.let {
                    it.removeOnGlobalLayoutListener(listener)
                    it.removeOnScrollChangedListener(scrollListener)
                }
                observer = null
            }
        })
    }

    private fun verticalFlowSlot(view: View, root: View): View? {
        var slot = view
        var localMargin: View? = null
        while (slot !== root) {
            val parent = slot.parent as? ViewGroup ?: return null
            // A collection owns item positioning. Never climb through it and insert a camera
            // gap before the whole list; keep clearance inside this item's nearest flow slot.
            if (isViewport(parent)) return localMargin
                ?: slot.takeIf { it.layoutParams is ViewGroup.MarginLayoutParams }
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) return slot
            if (localMargin == null && slot.layoutParams is ViewGroup.MarginLayoutParams) localMargin = slot
            slot = parent
        }
        return localMargin
    }

    private data class Margin(val base: Int, val camera: Int)

    // Scroll views can take focus for navigation, but are not bounded action targets. Their
    // children keep independent camera protection; their viewport position must stay unchanged.
    private fun isViewport(view: View): Boolean = view is RecyclerView || view is AdapterView<*> ||
        view is NestedScrollView || view is ScrollView || view is HorizontalScrollView

    private fun interactiveAncestor(view: View, root: View): Boolean {
        var ancestor = view.parent as? View
        while (ancestor != null && ancestor !== root) {
            if (isViewport(ancestor)) return false
            if (ancestor.isClickable || ancestor.isFocusable) return true
            ancestor = ancestor.parent as? View
        }
        return false
    }

    private fun visit(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), action)
    }
}

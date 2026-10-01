package com.papi.nova.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import java.util.WeakHashMap
import kotlin.math.ceil

/** A local camera slot in the legacy Hosts rail/header; neither the surface nor its side moves. */
internal object NovaCameraViewAvoidance {
    fun install(root: View, cameraBounds: () -> List<Rect> = {
        ViewCompat.getRootWindowInsets(root)?.displayCutout?.boundingRects.orEmpty()
    }) {
        val margins = WeakHashMap<View, Margin>()
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val cameras = cameraBounds()
            val wanted = linkedMapOf<View, Margin>()
            visit(root) { view ->
                if (view.visibility != View.VISIBLE) return@visit
                val slot = verticalFlowSlot(view, root) ?: return@visit
                val params = slot.layoutParams as? ViewGroup.MarginLayoutParams ?: return@visit
                val previous = margins[slot]
                val base = if (previous != null && params.topMargin == previous.base + previous.camera) previous.base else params.topMargin
                val ownCamera = if (previous != null && params.topMargin == previous.base + previous.camera) previous.camera else 0
                val at = IntArray(2)
                view.getLocationInWindow(at)
                val bounds = Rect(at[0], at[1] - ownCamera, at[0] + view.width, at[1] - ownCamera + view.height)
                // A plain label's allocated width can include empty space beside the camera. Its
                // actual glyph bounds matter; a clickable button keeps its entire target clear.
                if (!view.isClickable && view.layout != null && view.layout.lineCount > 0) {
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
        var observer: ViewTreeObserver? = null
        fun attach() {
            observer = root.viewTreeObserver.also { it.addOnGlobalLayoutListener(listener) }
        }
        if (ViewCompat.isAttachedToWindow(root)) attach()
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { attach() }
            override fun onViewDetachedFromWindow(view: View) {
                observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
                observer = null
            }
        })
    }

    private fun verticalFlowSlot(view: View, root: View): View? {
        var slot = view
        while (slot !== root) {
            val parent = slot.parent as? ViewGroup ?: return null
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) return slot
            slot = parent
        }
        return null
    }

    private data class Margin(val base: Int, val camera: Int)

    private fun visit(view: View, action: (TextView) -> Unit) {
        if (view is TextView) action(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), action)
    }
}

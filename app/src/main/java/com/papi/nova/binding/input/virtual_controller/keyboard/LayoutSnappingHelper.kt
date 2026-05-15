package com.papi.nova.binding.input.virtual_controller.keyboard

import android.view.View
import android.widget.FrameLayout

class LayoutSnappingHelper private constructor() {
    class SnapResult(
        @JvmField var newX: Int,
        @JvmField var newY: Int,
        @JvmField var newWidth: Int,
        @JvmField var newHeight: Int,
        @JvmField var didSnap: Boolean,
        @JvmField var didResize: Boolean,
        @JvmField var didAdjustSpacing: Boolean
    )

    companion object {
        private const val SNAP_THRESHOLD = 10
        private const val OVERLAP_THRESHOLD = 0.5f
        private const val SPACING_MIN = 4
        private const val SPACING_THRESHOLD = 30

        private fun isOverlapping(view1: View, x1: Int, y1: Int, view2: View, x2: Int, y2: Int): Boolean {
            val right1 = x1 + view1.width
            val bottom1 = y1 + view1.height
            val right2 = x2 + view2.width
            val bottom2 = y2 + view2.height

            val overlapX = minOf(right1, right2) - maxOf(x1, x2)
            val overlapY = minOf(bottom1, bottom2) - maxOf(y1, y2)
            if (overlapX <= 0 || overlapY <= 0) return false

            val overlapArea = overlapX * overlapY.toFloat()
            val view1Area = view1.width * view1.height.toFloat()
            val view2Area = view2.width * view2.height.toFloat()
            val overlapPercentage = if (view1Area > view2Area) {
                overlapArea / view2Area
            } else {
                overlapArea / view1Area
            }

            return overlapPercentage >= OVERLAP_THRESHOLD
        }

        private fun hasParallelEdges(edge1Start: Int, edge1End: Int, edge2Start: Int, edge2End: Int): Boolean {
            val overlapStart = maxOf(edge1Start, edge2Start)
            val overlapEnd = minOf(edge1End, edge2End)
            return overlapEnd - overlapStart > minOf(edge1End - edge1Start, edge2End - edge2Start) * 0.5
        }

        @JvmStatic
        fun calculateSnappedPosition(
            movingView: View,
            otherViews: Array<View>,
            proposedX: Int,
            proposedY: Int
        ): SnapResult {
            var snappedX = proposedX
            var snappedY = proposedY
            var newWidth = (movingView.layoutParams as FrameLayout.LayoutParams).width
            var newHeight = (movingView.layoutParams as FrameLayout.LayoutParams).height
            var didSnap = false
            var didResize = false
            var didAdjustSpacing = false

            val movingParams = movingView.layoutParams as FrameLayout.LayoutParams
            val movingWidth = movingParams.width
            val movingHeight = movingParams.height

            for (otherView in otherViews) {
                if (otherView === movingView || otherView.visibility != View.VISIBLE) {
                    continue
                }

                val otherParams = otherView.layoutParams as FrameLayout.LayoutParams

                if (isOverlapping(movingView, proposedX, proposedY, otherView, otherParams.leftMargin, otherParams.topMargin)) {
                    newWidth = otherView.width
                    newHeight = otherView.height
                    didResize = true
                }

                if (hasParallelEdges(proposedY, proposedY + movingHeight, otherParams.topMargin, otherParams.topMargin + otherView.height)) {
                    val leftDistance = kotlin.math.abs(proposedX - (otherParams.leftMargin + otherView.width))
                    if (leftDistance > SPACING_MIN && leftDistance < SPACING_THRESHOLD) {
                        snappedX = otherParams.leftMargin + otherView.width + SPACING_MIN
                        didAdjustSpacing = true
                    }

                    val rightDistance = kotlin.math.abs((proposedX + movingWidth) - otherParams.leftMargin)
                    if (rightDistance > SPACING_MIN && rightDistance < SPACING_THRESHOLD) {
                        snappedX = otherParams.leftMargin - SPACING_MIN - movingWidth
                        didAdjustSpacing = true
                    }
                }

                if (hasParallelEdges(proposedX, proposedX + movingWidth, otherParams.leftMargin, otherParams.leftMargin + otherView.width)) {
                    val topDistance = kotlin.math.abs(proposedY - (otherParams.topMargin + otherView.height))
                    if (topDistance > SPACING_MIN && topDistance < SPACING_THRESHOLD) {
                        snappedY = otherParams.topMargin + otherView.height + SPACING_MIN
                        didAdjustSpacing = true
                    }

                    val bottomDistance = kotlin.math.abs((proposedY + movingHeight) - otherParams.topMargin)
                    if (bottomDistance > SPACING_MIN && bottomDistance < SPACING_THRESHOLD) {
                        snappedY = otherParams.topMargin - SPACING_MIN - movingHeight
                        didAdjustSpacing = true
                    }
                }

                if (kotlin.math.abs(proposedX - otherParams.leftMargin) < SNAP_THRESHOLD) {
                    snappedX = otherParams.leftMargin
                    didSnap = true
                }
                if (kotlin.math.abs((proposedX + movingWidth) - (otherParams.leftMargin + otherView.width)) < SNAP_THRESHOLD) {
                    snappedX = otherParams.leftMargin + otherView.width - movingWidth
                    didSnap = true
                }
                if (kotlin.math.abs(proposedY - otherParams.topMargin) < SNAP_THRESHOLD) {
                    snappedY = otherParams.topMargin
                    didSnap = true
                }
                if (kotlin.math.abs((proposedY + movingHeight) - (otherParams.topMargin + otherView.height)) < SNAP_THRESHOLD) {
                    snappedY = otherParams.topMargin + otherView.height - movingHeight
                    didSnap = true
                }
            }

            return SnapResult(snappedX, snappedY, newWidth, newHeight, didSnap, didResize, didAdjustSpacing)
        }
    }
}

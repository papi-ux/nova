package com.papi.nova.utils

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.papi.nova.Game
import com.papi.nova.preferences.PreferenceConfiguration
import kotlin.math.max
import kotlin.math.min

class PanZoomHandler(
    context: Context,
    private val game: Game,
    private val streamView: View,
    private var parent: View?,
    prefConfig: PreferenceConfiguration
) {
    private val isTopMode: Boolean = prefConfig.alignDisplayTopCenter
    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private var scaleFactorValue = 1.0f
    private var childXValue = 0.0f
    private var childYValue = 0.0f
    private var parentWidth = 0.0f
    private var parentHeight = 0.0f
    private var childWidth = 0.0f
    private var childHeight = 0.0f

    init {
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())

        // Everything gets easier with 0,0 as the pivot point.
        streamView.pivotX = 0.0f
        streamView.pivotY = 0.0f
    }

    fun handleTouchEvent(motionEvent: MotionEvent) {
        scaleGestureDetector.onTouchEvent(motionEvent)
        gestureDetector.onTouchEvent(motionEvent)
    }

    private fun updateDimensions() {
        childHeight = streamView.height * scaleFactorValue
        childWidth = streamView.width * scaleFactorValue
        parentWidth = parent!!.width.toFloat()
        parentHeight = parent!!.height.toFloat()
    }

    private fun constrainToBounds() {
        updateDimensions()

        childXValue = if (parentWidth >= childWidth) {
            (parentWidth - childWidth) / 2.0f
        } else {
            val boundaryX = parentWidth - childWidth
            max(boundaryX, min(childXValue, 0.0f))
        }

        childYValue = if (parentHeight >= childHeight) {
            if (isTopMode) {
                0.0f
            } else {
                (parentHeight - childHeight) / 2.0f
            }
        } else {
            val boundaryY = parentHeight - childHeight
            max(boundaryY, min(childYValue, 0.0f))
        }

        streamView.x = childXValue
        streamView.y = childYValue
    }

    fun handleSurfaceChange() {
        if (childWidth == 0.0f || parent == null) {
            // Retrieve parent, should handle both built-in display and external display.
            parent = streamView.parent as View
            return
        }

        val prevChildWidth = childWidth
        val prevChildHeight = childHeight
        val prevParentWidth = parentWidth
        val prevParentHeight = parentHeight

        updateDimensions()

        val viewScaleX = childWidth / prevChildWidth
        val viewScaleY = childHeight / prevChildHeight

        val dPivotX1 = childXValue - prevParentWidth / 2.0f
        val dPivotY1 = childYValue - prevParentHeight / 2.0f

        val dPivotX2 = dPivotX1 * viewScaleX
        val dPivotY2 = dPivotY1 * viewScaleY

        childXValue = dPivotX2 + parentWidth / 2.0f
        childYValue = dPivotY2 + parentHeight / 2.0f

        streamView.x = childXValue
        streamView.y = childYValue

        constrainToBounds()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var newScaleFactor = scaleFactorValue * detector.scaleFactor
            newScaleFactor = max(1.0f, min(newScaleFactor, MAX_SCALE))

            val focusX = detector.focusX
            val focusY = detector.focusY

            val dPivotX = (childXValue - focusX) / scaleFactorValue * newScaleFactor
            val dPivotY = (childYValue - focusY) / scaleFactorValue * newScaleFactor

            childXValue = focusX + dPivotX
            childYValue = focusY + dPivotY

            scaleFactorValue = newScaleFactor

            streamView.scaleX = scaleFactorValue
            streamView.scaleY = scaleFactorValue

            streamView.x = childXValue
            streamView.y = childYValue

            constrainToBounds()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            game.updatePipAutoEnter()
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            childXValue = streamView.x - distanceX
            childYValue = streamView.y - distanceY

            streamView.x = childXValue
            streamView.y = childYValue

            constrainToBounds()
            return true
        }
    }

    fun setInitialZoomAndPan(scale: Float, offsetX: Float, offsetY: Float) {
        scaleFactorValue = scale
        streamView.scaleX = scaleFactorValue
        streamView.scaleY = scaleFactorValue
        childXValue = offsetX
        childYValue = offsetY
        streamView.x = childXValue
        streamView.y = childYValue
    }

    fun getScaleFactor(): Float = scaleFactorValue

    fun getChildX(): Float = childXValue

    fun getChildY(): Float = childYValue

    companion object {
        private const val MAX_SCALE = 10.0f
    }
}

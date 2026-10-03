package com.papi.nova.binding.input.touch

import android.os.Handler
import android.os.Looper
import android.view.View
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.MouseButtonPacket
import com.papi.nova.preferences.PreferenceConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class RelativeTouchContext(
    private val conn: NvConnection,
    private val actionIndex: Int,
    private val referenceWidth: Int,
    private val referenceHeight: Int,
    private val targetView: View,
    private val prefConfig: PreferenceConfiguration,
) : TouchContext {
    private var lastTouchX = 0
    private var lastTouchY = 0
    private var originalTouchX = 0
    private var originalTouchY = 0
    private var originalTouchTime = 0L
    private var cancelled = false
    private var confirmedMove = false
    private var confirmedDrag = false
    private var confirmedScroll = false
    private var distanceMoved = 0.0
    private var xFactor = 0.0
    private var yFactor = 0.0
    // Final relative travel is an exact signed numerator in stream pixels / (view pixels * 100).
    // Capture geometry at touch-down, like xFactor/yFactor; a resize applies to the next gesture.
    private var relativeDenominatorX = 0L
    private var relativeDenominatorY = 0L
    private var relativeTravelX = 0L
    private var relativeTravelY = 0L
    private var relativeLastTouchX = 0
    private var relativeLastTouchY = 0
    private var relativeRoute = false
    private var pointerCount = 0
    private var maxPointerCountInGesture = 0

    private val handler = Handler(Looper.getMainLooper())

    private val dragTimerRunnable = Runnable {
        if (confirmedMove) {
            return@Runnable
        }

        // The drag should only be processed for the primary finger.
        if (actionIndex != maxPointerCountInGesture - 1) {
            return@Runnable
        }

        confirmedDrag = true
        conn.sendMouseButtonDown(mouseButtonIndex())
    }

    // Indexed by MouseButtonPacket.BUTTON_XXX - 1.
    private val buttonUpRunnables =
        arrayOf(
            Runnable { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT) },
            Runnable { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE) },
            Runnable { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT) },
            Runnable { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1) },
            Runnable { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2) },
        )

    override fun getActionIndex(): Int = actionIndex

    override fun touchDownEvent(
        eventX: Int,
        eventY: Int,
        eventTime: Long,
        isNewFinger: Boolean,
    ): Boolean {
        xFactor = referenceWidth / targetView.width.toDouble()
        yFactor = referenceHeight / targetView.height.toDouble()
        relativeDenominatorX = targetView.width.toLong() * 100L
        relativeDenominatorY = targetView.height.toLong() * 100L
        relativeLastTouchX = eventX
        relativeLastTouchY = eventY
        resetRelativeTravel()
        relativeRoute = isRelativeRoute()

        originalTouchX = eventX
        lastTouchX = eventX
        originalTouchY = eventY
        lastTouchY = eventY

        if (isNewFinger) {
            maxPointerCountInGesture = pointerCount
            originalTouchTime = eventTime
            cancelled = false
            confirmedDrag = false
            confirmedMove = false
            confirmedScroll = false
            distanceMoved = 0.0

            if (actionIndex == 0) {
                startDragTimer()
            }
        }

        return true
    }

    override fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long) {
        resetRelativeTravel()
        if (cancelled) {
            return
        }

        cancelDragTimer()

        val buttonIndex = mouseButtonIndex()

        if (confirmedDrag) {
            conn.sendMouseButtonUp(buttonIndex)
        } else if (isTap(eventTime)) {
            conn.sendMouseButtonDown(buttonIndex)

            val buttonUpRunnable = buttonUpRunnables[buttonIndex.toInt() - 1]
            handler.removeCallbacks(buttonUpRunnable)
            handler.postDelayed(buttonUpRunnable, 100)
        }
    }

    override fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean {
        if (cancelled) {
            return true
        }

        val nextRelativeRoute = isRelativeRoute()
        if (nextRelativeRoute != relativeRoute) {
            resetRelativeTravel()
            relativeRoute = nextRelativeRoute
        }

        if (eventX != lastTouchX || eventY != lastTouchY) {
            checkForConfirmedMove(eventX, eventY)
            checkForConfirmedScroll()

            if (actionIndex == 0) {
                var deltaX = eventX - lastTouchX
                var deltaY = eventY - lastTouchY

                deltaX = (abs(deltaX) * xFactor).roundToInt()
                deltaY = (abs(deltaY) * yFactor).roundToInt()

                if (eventX < lastTouchX) {
                    deltaX = -deltaX
                }
                if (eventY < lastTouchY) {
                    deltaY = -deltaY
                }

                if (pointerCount == 2) {
                    if (confirmedScroll) {
                        conn.sendMouseHighResScroll((deltaY * SCROLL_SPEED_FACTOR).toShort())
                    }
                } else {
                    if (prefConfig.absoluteMouseMode) {
                        conn.sendMouseMoveAsMousePosition(
                            deltaX.toShort(),
                            deltaY.toShort(),
                            targetView.width.toShort(),
                            targetView.height.toShort(),
                        )
                    }
                }

                // Wait to update zero-rounded axes until a non-zero scaled delta arrives.
                if (deltaX != 0) {
                    lastTouchX = eventX
                }
                if (deltaY != 0) {
                    lastTouchY = eventY
                }
            } else {
                lastTouchX = eventX
                lastTouchY = eventY
            }
        }

        if (relativeRoute) {
            sendRelativeMotion(eventX, eventY)
        }
        // Track every raw sample separately from the existing gesture/scroll/absolute bookkeeping.
        relativeLastTouchX = eventX
        relativeLastTouchY = eventY
        return true
    }

    override fun cancelTouch() {
        cancelled = true
        resetRelativeTravel()

        cancelDragTimer()

        if (confirmedDrag) {
            conn.sendMouseButtonUp(mouseButtonIndex())
        }
    }

    override fun isCancelled(): Boolean = cancelled

    override fun setPointerCount(pointerCount: Int) {
        this.pointerCount = pointerCount
        val nextRelativeRoute = isRelativeRoute()
        if (nextRelativeRoute != relativeRoute) {
            resetRelativeTravel()
            relativeRoute = nextRelativeRoute
        }

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount
        }
    }

    private fun isRelativeRoute(): Boolean =
        actionIndex == 0 && pointerCount != 2 && !prefConfig.absoluteMouseMode

    private fun resetRelativeTravel() {
        relativeTravelX = 0L
        relativeTravelY = 0L
    }

    private fun accumulateRelativeTravel(
        pending: Long,
        delta: Long,
        reference: Int,
        sensitivity: Int,
        denominator: Long,
    ): Long {
        if (denominator <= 0 || reference <= 0 || sensitivity < 0) {
            return 0L
        }
        // Convert before multiplying; these two positive factors are at most Int.MAX_VALUE.
        // Bounds checks also work on minSdk 21, without API-24 Math.*Exact methods.
        val referenceFactor = reference.toLong()
        if (delta > Long.MAX_VALUE / referenceFactor || delta < Long.MIN_VALUE / referenceFactor) {
            return 0L
        }
        val geometry = delta * referenceFactor
        val sensitivityFactor = sensitivity.toLong()
        if (sensitivityFactor != 0L &&
            (geometry > Long.MAX_VALUE / sensitivityFactor || geometry < Long.MIN_VALUE / sensitivityFactor)) {
            return 0L
        }
        val scaled = geometry * sensitivityFactor
        if ((scaled > 0 && pending > Long.MAX_VALUE - scaled) ||
            (scaled < 0 && pending < Long.MIN_VALUE - scaled)) {
            return 0L
        }
        return pending + scaled
    }

    private fun sendRelativeMotion(eventX: Int, eventY: Int) {
        relativeTravelX = accumulateRelativeTravel(
            relativeTravelX, eventX.toLong() - relativeLastTouchX.toLong(),
            referenceWidth, prefConfig.touchPadSensitivity, relativeDenominatorX,
        )
        relativeTravelY = accumulateRelativeTravel(
            relativeTravelY, eventY.toLong() - relativeLastTouchY.toLong(),
            referenceHeight, prefConfig.touchPadYSensitity, relativeDenominatorY,
        )
        val deltaX = if (relativeDenominatorX > 0) relativeTravelX / relativeDenominatorX else 0L
        val deltaY = if (relativeDenominatorY > 0) relativeTravelY / relativeDenominatorY else 0L
        val packetX = deltaX.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
        val packetY = deltaY.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
        // One bounded packet per callback keeps normal cadence. Retain signed excess and fraction;
        // a later callback (even at the same position) can drain it. Gesture end discards any excess.
        relativeTravelX -= packetX * relativeDenominatorX
        relativeTravelY -= packetY * relativeDenominatorY
        if (packetX != 0L || packetY != 0L) {
            conn.sendMouseMove(packetX.toShort(), packetY.toShort())
        }
    }

    private fun isWithinTapBounds(touchX: Int, touchY: Int): Boolean {
        val xDelta = abs(touchX - originalTouchX)
        val yDelta = abs(touchY - originalTouchY)
        return xDelta <= TAP_MOVEMENT_THRESHOLD && yDelta <= TAP_MOVEMENT_THRESHOLD
    }

    private fun isTap(eventTime: Long): Boolean {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false
        }

        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false
        }

        val timeDelta = eventTime - originalTouchTime
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD
    }

    private fun mouseButtonIndex(): Byte =
        if (actionIndex == 1) {
            MouseButtonPacket.BUTTON_RIGHT
        } else {
            MouseButtonPacket.BUTTON_LEFT
        }

    private fun startDragTimer() {
        cancelDragTimer()
        handler.postDelayed(dragTimerRunnable, DRAG_TIME_THRESHOLD.toLong())
    }

    private fun cancelDragTimer() {
        handler.removeCallbacks(dragTimerRunnable)
    }

    private fun checkForConfirmedMove(eventX: Int, eventY: Int) {
        if (confirmedMove || confirmedDrag) {
            return
        }

        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true
            cancelDragTimer()
            return
        }

        distanceMoved += hypot((eventX - lastTouchX).toDouble(), (eventY - lastTouchY).toDouble())
        if (distanceMoved >= TAP_DISTANCE_THRESHOLD) {
            confirmedMove = true
            cancelDragTimer()
        }
    }

    private fun checkForConfirmedScroll() {
        confirmedScroll = actionIndex == 0 && pointerCount == 2 && confirmedMove
    }

    private companion object {
        private const val TAP_MOVEMENT_THRESHOLD = 20
        private const val TAP_DISTANCE_THRESHOLD = 25
        private const val TAP_TIME_THRESHOLD = 250
        private const val DRAG_TIME_THRESHOLD = 650
        private const val SCROLL_SPEED_FACTOR = 5
    }
}

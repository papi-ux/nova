package com.papi.nova.binding.input.touch

import android.os.Handler
import android.os.Looper
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.input.MouseButtonPacket
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

class TrackpadContext(
    private val conn: NvConnection,
    private val actionIndex: Int,
) : TouchContext {
    private var pendingDeltaX = 0.0
    private var pendingDeltaY = 0.0
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
    private var pointerCount = 0
    private var clickedMiddle = false
    private var maxPointerCountInGesture = 0
    private var isClickPending = false
    private var isDblClickPending = false
    private var isFlicking = false
    private var velocityX = 0.0
    private var velocityY = 0.0
    private var lastMoveTime = 0L
    private var isScrollTransitioning = false

    private val handler = Handler(Looper.getMainLooper())

    private var swapAxis = false
    private var sensitivityX = 1f
    private var sensitivityY = 1f

    constructor(
        conn: NvConnection,
        actionIndex: Int,
        swapAxis: Boolean,
        sensitivityX: Int,
        sensitivityY: Int,
    ) : this(conn, actionIndex) {
        this.swapAxis = swapAxis
        this.sensitivityX = sensitivityX.toFloat() / 100
        this.sensitivityY = sensitivityY.toFloat() / 100
    }

    private val scrollTransitionRunnable = Runnable {
        isScrollTransitioning = false
    }

    private val momentumRunnable =
        object : Runnable {
            override fun run() {
                if (!isFlicking) {
                    return
                }

                pendingDeltaX += velocityX * MOMENTUM_FRAME_INTERVAL_MS
                pendingDeltaY += velocityY * MOMENTUM_FRAME_INTERVAL_MS

                val intDeltaX = pendingDeltaX.toInt().toShort()
                val intDeltaY = pendingDeltaY.toInt().toShort()

                if (intDeltaX.toInt() != 0 || intDeltaY.toInt() != 0) {
                    conn.sendMouseMove(intDeltaX, intDeltaY)
                    pendingDeltaX -= intDeltaX.toDouble()
                    pendingDeltaY -= intDeltaY.toDouble()
                }

                velocityX *= FLICK_FRICTION
                velocityY *= FLICK_FRICTION

                if (hypot(velocityX, velocityY) * MOMENTUM_FRAME_INTERVAL_MS < 0.5) {
                    isFlicking = false
                    if (confirmedDrag) {
                        conn.sendMouseButtonUp(mouseButtonIndex())
                        confirmedDrag = false
                    }
                }

                if (isFlicking) {
                    handler.postDelayed(this, MOMENTUM_FRAME_INTERVAL_MS.toLong())
                }
            }
        }

    private val scrollMomentumRunnable =
        object : Runnable {
            override fun run() {
                if (!isFlicking) {
                    return
                }

                val frameVelocityX = velocityX * MOMENTUM_FRAME_INTERVAL_MS
                val frameVelocityY = velocityY * MOMENTUM_FRAME_INTERVAL_MS

                if (abs(frameVelocityX) > abs(frameVelocityY)) {
                    conn.sendMouseHighResHScroll((-frameVelocityX * SCROLL_SPEED_FACTOR_X).toInt().toShort())
                    if (abs(frameVelocityY) * 1.05 > abs(frameVelocityX)) {
                        conn.sendMouseHighResScroll((frameVelocityY * SCROLL_SPEED_FACTOR_Y).toInt().toShort())
                    }
                } else {
                    conn.sendMouseHighResScroll((frameVelocityY * SCROLL_SPEED_FACTOR_Y).toInt().toShort())
                    if (abs(frameVelocityX) * 1.05 >= abs(frameVelocityY)) {
                        conn.sendMouseHighResHScroll((-frameVelocityX * SCROLL_SPEED_FACTOR_X).toInt().toShort())
                    }
                }

                velocityX *= FLICK_FRICTION
                velocityY *= FLICK_FRICTION

                if (hypot(velocityX, velocityY) * MOMENTUM_FRAME_INTERVAL_MS < 0.5) {
                    isFlicking = false
                }

                if (isFlicking) {
                    handler.postDelayed(this, MOMENTUM_FRAME_INTERVAL_MS.toLong())
                }
            }
        }

    override fun getActionIndex(): Int = actionIndex

    override fun touchDownEvent(
        eventX: Int,
        eventY: Int,
        eventTime: Long,
        isNewFinger: Boolean,
    ): Boolean {
        if (isFlicking) {
            isFlicking = false
            handler.removeCallbacksAndMessages(null)
        }

        originalTouchX = eventX
        lastTouchX = eventX
        originalTouchY = eventY
        lastTouchY = eventY

        pendingDeltaX = 0.0
        pendingDeltaY = 0.0

        if (isNewFinger) {
            // A completely new gesture has started. Cancel any pending scroll->move transition.
            clickedMiddle = false
            handler.removeCallbacks(scrollTransitionRunnable)
            isScrollTransitioning = false
            maxPointerCountInGesture = pointerCount
            originalTouchTime = eventTime
            cancelled = false
            confirmedMove = false
            confirmedScroll = false
            distanceMoved = 0.0
            velocityX = 0.0
            velocityY = 0.0
            lastMoveTime = eventTime
            if (isClickPending) {
                isClickPending = false
                isDblClickPending = true
                confirmedDrag = true
            }
        } else {
            if (pointerCount == 2 && !confirmedMove) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE)
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
                isClickPending = false
                isDblClickPending = false
                confirmedDrag = false
                clickedMiddle = true
            } else if (pointerCount == 1 && !confirmedMove && !clickedMiddle) {
                // Second finger released, should trigger right click immediately.
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                isClickPending = false
                isDblClickPending = false
                confirmedDrag = false
            }
        }

        originalTouchX = eventX
        lastTouchX = eventX
        originalTouchY = eventY
        lastTouchY = eventY

        pendingDeltaX = 0.0
        pendingDeltaY = 0.0

        return true
    }

    override fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long) {
        if (cancelled) {
            return
        }

        // Decay velocity based on time since last move to avoid flicks after a pause.
        val timeSinceLastMove = eventTime - lastMoveTime
        if (timeSinceLastMove > 0) {
            val decay = max(0.0, 1.0 - timeSinceLastMove.toDouble() / FLICK_VELOCITY_DECAY_TIMEOUT_MS)
            velocityX *= decay
            velocityY *= decay
        }

        val buttonIndex = mouseButtonIndex()

        if (isDblClickPending) {
            handler.removeCallbacksAndMessages(null)
            conn.sendMouseButtonUp(buttonIndex)
            conn.sendMouseButtonDown(buttonIndex)
            conn.sendMouseButtonUp(buttonIndex)
            isClickPending = false
            confirmedDrag = false
        } else if (confirmedDrag) {
            handler.removeCallbacksAndMessages(null)

            val speed = hypot(velocityX, velocityY)
            if (speed > FLICK_THRESHOLD) {
                isFlicking = true
                handler.post(momentumRunnable)
            } else {
                conn.sendMouseButtonUp(buttonIndex)
                confirmedDrag = false
            }
        } else if (isTap(eventTime)) {
            conn.sendMouseButtonDown(buttonIndex)
            isClickPending = true

            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(
                {
                    if (isClickPending) {
                        conn.sendMouseButtonUp(buttonIndex)
                        isClickPending = false
                    }
                    isDblClickPending = false
                },
                CLICK_RELEASE_DELAY.toLong(),
            )
        } else if (confirmedMove) {
            val speed = hypot(velocityX, velocityY)
            if (speed > FLICK_THRESHOLD) {
                isFlicking = true
                if (confirmedScroll) {
                    handler.post(scrollMomentumRunnable)
                } else if (maxPointerCountInGesture == 1) {
                    // A 1-finger move can flick. Multi-finger non-scroll moves should not.
                    handler.post(momentumRunnable)
                }
            }
        }
    }

    override fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean {
        if (cancelled) {
            return true
        }

        if (eventX != lastTouchX || eventY != lastTouchY) {
            val deltaTime = eventTime - lastMoveTime

            checkForConfirmedMove(eventX, eventY)

            if (isDblClickPending) {
                isDblClickPending = false
                confirmedDrag = true
            }

            val rawDeltaX = eventX - lastTouchX
            val rawDeltaY = eventY - lastTouchY

            val absDeltaX: Int
            val absDeltaY: Int

            val magnitude = sqrt((rawDeltaX * rawDeltaX + rawDeltaY * rawDeltaY).toDouble())
            val precisionMultiplier = Math.cbrt(magnitude / ACCELERATION_THRESHOLD)

            var deltaX: Float
            var deltaY: Float
            if (swapAxis) {
                deltaY = rawDeltaX.toFloat()
                deltaX = rawDeltaY.toFloat()

                absDeltaX = abs(rawDeltaY)
                absDeltaY = abs(rawDeltaX)
            } else {
                deltaX = rawDeltaX.toFloat()
                deltaY = rawDeltaY.toFloat()

                absDeltaX = abs(rawDeltaX)
                absDeltaY = abs(rawDeltaY)
            }

            deltaX = (deltaX * precisionMultiplier).toFloat()
            deltaY = (deltaY * precisionMultiplier).toFloat()

            deltaX *= sensitivityX
            deltaY *= sensitivityY

            if (deltaTime > 0 && (confirmedMove || confirmedDrag)) {
                val currentVelocityX = (deltaX / deltaTime).toDouble()
                val currentVelocityY = (deltaY / deltaTime).toDouble()

                if (velocityX == 0.0 && velocityY == 0.0) {
                    velocityX = currentVelocityX
                    velocityY = currentVelocityY
                } else {
                    velocityX = velocityX * 0.8 + currentVelocityX * 0.2
                    velocityY = velocityY * 0.8 + currentVelocityY * 0.2
                }
            }

            lastMoveTime = eventTime

            pendingDeltaX += deltaX.toDouble()
            pendingDeltaY += deltaY.toDouble()

            lastTouchX = eventX
            lastTouchY = eventY

            val sendDeltaX = pendingDeltaX.toInt().toShort()
            val sendDeltaY = pendingDeltaY.toInt().toShort()

            if (pointerCount == 1) {
                if (!isScrollTransitioning && (sendDeltaX.toInt() != 0 || sendDeltaY.toInt() != 0)) {
                    conn.sendMouseMove(sendDeltaX, sendDeltaY)
                }
            } else if (actionIndex == 1) {
                if (confirmedDrag) {
                    if (sendDeltaX.toInt() != 0 || sendDeltaY.toInt() != 0) {
                        conn.sendMouseMove(sendDeltaX, sendDeltaY)
                    }
                } else if (pointerCount == 2) {
                    checkForConfirmedScroll()
                    if (confirmedScroll) {
                        if (absDeltaX > absDeltaY) {
                            conn.sendMouseHighResHScroll((-sendDeltaX * SCROLL_SPEED_FACTOR_X).toShort())
                            if (absDeltaY * 1.05 > absDeltaX) {
                                conn.sendMouseHighResScroll((sendDeltaY * SCROLL_SPEED_FACTOR_Y).toShort())
                            }
                        } else {
                            conn.sendMouseHighResScroll((sendDeltaY * SCROLL_SPEED_FACTOR_Y).toShort())
                            if (absDeltaX * 1.05 >= absDeltaY) {
                                conn.sendMouseHighResHScroll((-sendDeltaX * SCROLL_SPEED_FACTOR_X).toShort())
                            }
                        }
                    }
                }
            }

            pendingDeltaX -= sendDeltaX.toDouble()
            pendingDeltaY -= sendDeltaY.toDouble()
        }

        return true
    }

    override fun cancelTouch() {
        cancelled = true

        if (isFlicking) {
            isFlicking = false
            handler.removeCallbacksAndMessages(null)
        }

        if (confirmedDrag) {
            conn.sendMouseButtonUp(mouseButtonIndex())
        }
    }

    override fun isCancelled(): Boolean = cancelled

    override fun setPointerCount(pointerCount: Int) {
        if (this.pointerCount == 2 && pointerCount == 1) {
            // Block movement briefly after a 2-finger scroll while the remaining finger lifts.
            isScrollTransitioning = true
            handler.postDelayed(scrollTransitionRunnable, SCROLL_TRANSITION_TIMEOUT_MS.toLong())
        } else if (this.pointerCount == 1 && pointerCount == 2) {
            handler.removeCallbacks(scrollTransitionRunnable)
            isScrollTransitioning = false
        }

        if (pointerCount < this.pointerCount && confirmedDrag && !isFlicking) {
            conn.sendMouseButtonUp(mouseButtonIndex())
            confirmedDrag = false
            confirmedMove = false
            confirmedScroll = false
            isClickPending = false
            isDblClickPending = false
        }

        this.pointerCount = pointerCount

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount
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
        when (pointerCount) {
            2 -> MouseButtonPacket.BUTTON_RIGHT
            3 -> {
                clickedMiddle = true
                MouseButtonPacket.BUTTON_MIDDLE
            }
            else -> MouseButtonPacket.BUTTON_LEFT
        }

    private fun checkForConfirmedMove(eventX: Int, eventY: Int) {
        if (confirmedMove || confirmedDrag) {
            return
        }

        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true
            return
        }

        distanceMoved += hypot((eventX - lastTouchX).toDouble(), (eventY - lastTouchY).toDouble())
        if (distanceMoved >= TAP_MOVEMENT_THRESHOLD) {
            confirmedMove = true
        }
    }

    private fun checkForConfirmedScroll() {
        confirmedScroll = actionIndex == 1 && pointerCount == 2 && confirmedMove
    }

    private companion object {
        private const val TAP_MOVEMENT_THRESHOLD = 30
        private const val TAP_TIME_THRESHOLD = 230
        private const val CLICK_RELEASE_DELAY = TAP_TIME_THRESHOLD
        private const val SCROLL_SPEED_FACTOR_X = 2
        private const val SCROLL_SPEED_FACTOR_Y = 3
        private const val ACCELERATION_THRESHOLD = 8.0
        private const val FLICK_FRICTION = 0.93
        private const val FLICK_THRESHOLD = 0.8
        private const val MOMENTUM_FRAME_INTERVAL_MS = 10
        private const val FLICK_VELOCITY_DECAY_TIMEOUT_MS = 50
        private const val SCROLL_TRANSITION_TIMEOUT_MS = 200
    }
}

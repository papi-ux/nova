package com.papi.nova.binding.input.virtual_controller.keyboard

import android.content.Context

class KeyBoardAnalogStickButtonFree(
    controller: KeyBoardController,
    elementId: String,
    context: Context,
    keyInfo: IntArray
) : keyAnalogStickFree(controller, context, elementId) {
    private val stickIndex = IntArray(4)
    private val stickBool = BooleanArray(4)
    private val stickSender = IntArray(5)
    private lateinit var listener: KeyBoardAnalogStickListener

    init {
        for (i in keyInfo.indices) {
            stickSender[i] = keyInfo[i]
        }
        addAnalogStickListener(object : AnalogStickListener {
            override fun onMovement(xf: Float, yf: Float) {
                val x = (xf * 0x7FFE).toInt()
                val y = (yf * 0x7FFE).toInt()

                if (y > 0) {
                    stickIndex[0] = y
                    stickIndex[1] = -1
                } else if (y < 0) {
                    stickIndex[0] = -1
                    stickIndex[1] = -y
                } else {
                    stickIndex[0] = 0
                    stickIndex[1] = -1
                }

                if (x > 0) {
                    stickIndex[2] = -1
                    stickIndex[3] = x
                } else if (x < 0) {
                    stickIndex[2] = -x
                    stickIndex[3] = -1
                } else {
                    stickIndex[2] = 0
                    stickIndex[3] = -1
                }

                val active = x * x + y * y > MIN_CIRCLE_R * MIN_CIRCLE_R
                when {
                    y >= EIGHTH_THREE_PI * x && y >= NEGATIVE_EIGHTH_THREE_PI * x && active -> setStickDirection(true, false, false, false)
                    y < EIGHTH_THREE_PI * x && y < NEGATIVE_EIGHTH_THREE_PI * x && active -> setStickDirection(false, true, false, false)
                    y >= EIGHTH_PI * x && y < NEGATIVE_EIGHTH_PI * x && active -> setStickDirection(false, false, true, false)
                    y < EIGHTH_PI * x && y >= NEGATIVE_EIGHTH_PI * x && active -> setStickDirection(false, false, false, true)
                    y < NEGATIVE_EIGHTH_THREE_PI * x && y >= NEGATIVE_EIGHTH_PI * x && active -> setStickDirection(true, false, true, false)
                    y >= NEGATIVE_EIGHTH_THREE_PI * x && y < NEGATIVE_EIGHTH_PI * x && active -> setStickDirection(false, true, false, true)
                    y >= EIGHTH_PI * x && y < EIGHTH_THREE_PI * x && active -> setStickDirection(true, false, false, true)
                    y < EIGHTH_PI * x && y >= EIGHTH_THREE_PI * x && active -> setStickDirection(false, true, true, false)
                    else -> setStickDirection(false, false, false, false)
                }

                for (i in 0 until 4) {
                    listener.onkeyEvent(stickSender[i], stickBool[i])
                }
            }

            override fun onClick() = Unit

            override fun onDoubleClick() {
                listener.onkeyEvent(stickSender[4], true)
            }

            override fun onRevoke() {
                stickIndex[0] = 0
                stickIndex[1] = -1
                stickIndex[2] = 0
                stickIndex[3] = -1
                setStickDirection(false, false, false, false)
                for (i in 0 until 4) {
                    listener.onkeyEvent(stickSender[i], stickBool[i])
                }
                listener.onkeyEvent(stickSender[4], false)
            }
        })
    }

    fun setListener(listener: KeyBoardAnalogStickListener) {
        this.listener = listener
    }

    private fun setStickDirection(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
        stickBool[0] = up
        stickBool[1] = down
        stickBool[2] = left
        stickBool[3] = right
    }

    interface KeyBoardAnalogStickListener {
        fun onkeyEvent(code: Int, isPress: Boolean)
    }

    companion object {
        private const val MIN_CIRCLE_R = 10000
        private const val EIGHTH_PI = 0.4142f
        private const val EIGHTH_THREE_PI = 2.4142f
        private const val NEGATIVE_EIGHTH_PI = -0.4142f
        private const val NEGATIVE_EIGHTH_THREE_PI = -2.4142f
    }
}

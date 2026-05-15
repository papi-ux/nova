package com.papi.nova.binding.input.evdev

interface EvdevListener {
    fun mouseMove(deltaX: Int, deltaY: Int)

    fun mouseButtonEvent(buttonId: Int, down: Boolean)

    fun mouseVScroll(amount: Byte)

    fun mouseHScroll(amount: Byte)

    fun keyboardEvent(buttonDown: Boolean, keyCode: Short)

    companion object {
        const val BUTTON_LEFT: Int = 1
        const val BUTTON_MIDDLE: Int = 2
        const val BUTTON_RIGHT: Int = 3
        const val BUTTON_X1: Int = 4
        const val BUTTON_X2: Int = 5
    }
}

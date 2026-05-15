package com.papi.nova

class SensitivityBean {
    // Real coordinates.
    private var lastAbsoluteX = -1f
    private var lastAbsoluteY = -1f

    // Coordinates after sensitivity adjustment.
    private var lastRelativelyX = -1f
    private var lastRelativelyY = -1f

    fun getLastAbsoluteX(): Float = lastAbsoluteX

    fun setLastAbsoluteX(lastAbsoluteX: Float) {
        this.lastAbsoluteX = lastAbsoluteX
    }

    fun getLastAbsoluteY(): Float = lastAbsoluteY

    fun setLastAbsoluteY(lastAbsoluteY: Float) {
        this.lastAbsoluteY = lastAbsoluteY
    }

    fun getLastRelativelyX(): Float = lastRelativelyX

    fun setLastRelativelyX(lastRelativelyX: Float) {
        this.lastRelativelyX = lastRelativelyX
    }

    fun getLastRelativelyY(): Float = lastRelativelyY

    fun setLastRelativelyY(lastRelativelyY: Float) {
        this.lastRelativelyY = lastRelativelyY
    }
}

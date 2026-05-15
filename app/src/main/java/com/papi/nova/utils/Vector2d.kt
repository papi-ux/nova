package com.papi.nova.utils

class Vector2d {
    private var x = 0f
    private var y = 0f
    private var magnitude = 0.0

    init {
        initialize(0f, 0f)
    }

    fun initialize(x: Float, y: Float) {
        this.x = x
        this.y = y
        magnitude = kotlin.math.sqrt((x * x + y * y).toDouble())
    }

    fun getMagnitude(): Double = magnitude

    fun getNormalized(vector: Vector2d) {
        vector.initialize((x / magnitude).toFloat(), (y / magnitude).toFloat())
    }

    fun scalarMultiply(factor: Double) {
        initialize((x * factor).toFloat(), (y * factor).toFloat())
    }

    fun setX(x: Float) {
        initialize(x, y)
    }

    fun setY(y: Float) {
        initialize(x, y)
    }

    fun getX(): Float = x

    fun getY(): Float = y

    companion object {
        @JvmField
        val ZERO = Vector2d()
    }
}

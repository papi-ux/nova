package com.papi.nova.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.papi.nova.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated space particle background — port of Polaris SpaceParticles.vue.
 * Renders twinkling stars, occasional shooting stars, and subtle nebulae.
 * Background color is theme-aware: Polaris navy or OLED black.
 */
class SpaceParticleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgColor: Int = NovaThemeManager.getWindowBackgroundColor(context)

    var dense = false
        set(value) { field = value; rebuild() }

    private data class Star(
        var x: Float, var y: Float, var size: Float,
        var speedX: Float, var speedY: Float,
        var opacity: Float, var twinkleSpeed: Float, var twinklePhase: Float,
        var isBright: Boolean
    )

    private data class ShootingStar(
        var x: Float, var y: Float, var speed: Float,
        var dx: Float, var dy: Float,
        var life: Int, var maxLife: Int, var size: Float
    )

    private data class Nebula(
        var x: Float, var y: Float, var radius: Float,
        var r: Int, var g: Int, var b: Int,
        var opacity: Float, var drift: Float
    )

    private var stars = mutableListOf<Star>()
    private var shootingStars = mutableListOf<ShootingStar>()
    private var nebulae = mutableListOf<Nebula>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var running = true

    private fun createStar(): Star {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val isBright = Random.nextFloat() < if (dense) 0.15f else 0.1f
        return Star(
            x = Random.nextFloat() * w,
            y = Random.nextFloat() * h,
            size = if (dense) {
                if (isBright) Random.nextFloat() * 2.5f + 1f else Random.nextFloat() * 1.5f + 0.3f
            } else {
                if (isBright) Random.nextFloat() * 2f + 0.8f else Random.nextFloat() * 1.5f + 0.5f
            },
            speedX = (Random.nextFloat() - 0.5f) * if (dense) 0.08f else 0.12f,
            speedY = (Random.nextFloat() - 0.5f) * 0.1f - if (dense) 0.02f else 0.03f,
            opacity = if (dense) {
                if (isBright) Random.nextFloat() * 0.7f + 0.3f else Random.nextFloat() * 0.4f + 0.05f
            } else {
                if (isBright) Random.nextFloat() * 0.6f + 0.3f else Random.nextFloat() * 0.4f + 0.15f
            },
            twinkleSpeed = Random.nextFloat() * if (dense) 0.008f else 0.005f + 0.002f,
            twinklePhase = Random.nextFloat() * PI.toFloat() * 2f,
            isBright = isBright
        )
    }

    private fun createShootingStar(): ShootingStar {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val angle = Random.nextFloat() * 0.5f + 0.2f
        return ShootingStar(
            x = Random.nextFloat() * w * 0.8f,
            y = Random.nextFloat() * h * 0.4f,
            speed = Random.nextFloat() * 4f + 3f,
            dx = cos(angle), dy = sin(angle),
            life = 0, maxLife = Random.nextInt(20, 60),
            size = Random.nextFloat() * 1.5f + 0.5f
        )
    }

    private fun createNebula(): Nebula {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val purple = Random.nextBoolean()
        return Nebula(
            x = Random.nextFloat() * w, y = Random.nextFloat() * h,
            radius = Random.nextFloat() * 200f + 100f,
            r = if (purple) 140 else 100,
            g = if (purple) 100 else 120,
            b = if (purple) 160 else 180,
            opacity = Random.nextFloat() * 0.03f + 0.01f,
            drift = (Random.nextFloat() - 0.5f) * 0.02f
        )
    }

    private fun rebuild() {
        val count = if (dense) 300 else 120
        stars = MutableList(count) { createStar() }
        nebulae = if (dense) MutableList(6) { createNebula() } else mutableListOf()
        shootingStars.clear()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        if (!running || width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()

        // Theme-aware background (Polaris navy or OLED black)
        canvas.drawColor(bgColor)

        // Nebulae
        for (n in nebulae) {
            n.x += n.drift
            paint.shader = RadialGradient(
                n.x, n.y, n.radius,
                intArrayOf(
                    ((n.opacity * 255).toInt() shl 24) or (n.r shl 16) or (n.g shl 8) or n.b,
                    0x00000000
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(n.x, n.y, n.radius, paint)
            paint.shader = null
        }

        // Stars
        for (p in stars) {
            p.x += p.speedX
            p.y += p.speedY
            if (p.x < -5f) p.x = w + 5f
            if (p.x > w + 5f) p.x = -5f
            if (p.y < -5f) p.y = h + 5f
            if (p.y > h + 5f) p.y = -5f

            p.twinklePhase += p.twinkleSpeed
            val twinkle = sin(p.twinklePhase) * 0.3f + 0.7f
            val alpha = (p.opacity * twinkle * 255).toInt().coerceIn(0, 255)

            // Star core
            paint.color = (alpha shl 24) or 0xC8D6E5
            canvas.drawCircle(p.x, p.y, p.size, paint)

            // Glow
            if (p.isBright && alpha > 76) {
                paint.color = ((alpha * 0.08f).toInt().coerceIn(0, 255) shl 24) or 0xC8D6E5
                canvas.drawCircle(p.x, p.y, p.size * 3f, paint)

                // Cross sparkle
                if (dense && p.size > 1.5f && twinkle > 0.85f) {
                    linePaint.color = ((alpha * 0.3f).toInt().coerceIn(0, 255) shl 24) or 0xC8D6E5
                    linePaint.strokeWidth = 0.5f
                    val len = p.size * 4f
                    canvas.drawLine(p.x - len, p.y, p.x + len, p.y, linePaint)
                    canvas.drawLine(p.x, p.y - len, p.x, p.y + len, linePaint)
                }
            }
        }

        // Shooting stars
        if (dense && Random.nextFloat() < 0.005f && shootingStars.size < 2) {
            shootingStars.add(createShootingStar())
        }

        val iter = shootingStars.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            s.x += s.dx * s.speed
            s.y += s.dy * s.speed
            s.life++

            val progress = s.life.toFloat() / s.maxLife
            val fade = if (progress < 0.3f) progress / 0.3f else 1f - (progress - 0.3f) / 0.7f
            val alpha = (fade * 0.8f * 255).toInt().coerceIn(0, 255)

            // Trail
            linePaint.shader = LinearGradient(
                s.x, s.y, s.x - s.dx * 20f, s.y - s.dy * 20f,
                (alpha shl 24) or 0xC8D6E5, 0x00C8D6E5,
                Shader.TileMode.CLAMP
            )
            linePaint.strokeWidth = s.size
            canvas.drawLine(s.x, s.y, s.x - s.dx * 20f, s.y - s.dy * 20f, linePaint)
            linePaint.shader = null

            // Head
            paint.color = (alpha shl 24) or 0xDCE6F5
            canvas.drawCircle(s.x, s.y, s.size, paint)

            if (s.life >= s.maxLife || s.x > w + 50 || s.y > h + 50) {
                iter.remove()
            }
        }

        // Animate at ~60fps
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    fun pause() {
        running = false
    }

    fun resume() {
        if (!running) {
            running = true
            postInvalidateOnAnimation()
        }
    }
}

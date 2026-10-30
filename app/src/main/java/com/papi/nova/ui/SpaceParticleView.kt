package com.papi.nova.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.core.view.isVisible
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated space particle background, a port of Polaris SpaceParticles.vue: twinkling stars,
 * the odd shooting star, and faint nebulae. Background colour is theme-aware (Polaris navy or
 * OLED black).
 *
 * Every gradient is built once and moved with a matrix, where it used to be allocated per
 * nebula and per shooting star on every frame. The frame loop stops whenever the view cannot
 * be seen (window hidden, view hidden, or covered by an opaque screen) and draws one still
 * frame when the person asked for less motion (power saver, or animations scaled to zero).
 *
 * It also holds the sky's few hidden moments (#424): a supernova, a meteor shower and its peak
 * nights, one star brightening, and the North Star. With less motion each is a still image for as
 * long as it lasts, and none of them starts while a stream runs ([NovaSkyQuiet]).
 */
class SpaceParticleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgColor: Int = NovaThemeManager.getActivityWindowSurfaceColor(context)
    private val starRgb: Int = NovaThemeManager.getTextPrimaryColor(context) and 0x00FFFFFF
    private val ambientAccents: IntArray = NovaThemeManager.getAmbientAccentColors(context)

    var dense = false
        set(value) { field = value; rebuild() }

    /**
     * When true the view fills its canvas with the opaque theme surface colour before
     * drawing particles. Layers stacked above cinematic artwork must set this to false,
     * otherwise the fill erases the artwork underneath.
     */
    var paintsOpaqueBackground = true
        set(value) { field = value; invalidate() }

    private data class Star(
        var x: Float, var y: Float, var size: Float,
        var speedX: Float, var speedY: Float,
        var opacity: Float, var twinkleSpeed: Float, var twinklePhase: Float,
        var isBright: Boolean
    )

    private class ShootingStar(
        var x: Float, var y: Float, val speed: Float,
        val dx: Float, val dy: Float,
        var life: Int, val maxLife: Int, val size: Float,
        /** Head at the origin, tail 20 px behind; the paint's alpha carries the fade. */
        val trail: LinearGradient,
    )

    private class Nebula(
        var x: Float, var y: Float, val radius: Float,
        /** Centred on the origin; translated to the nebula each frame. */
        val shader: RadialGradient,
        val drift: Float,
    )

    private var stars = mutableListOf<Star>()
    private val shootingStars = mutableListOf<ShootingStar>()
    private var nebulae = listOf<Nebula>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shaderMatrix = Matrix()
    private var running = false
    private var paused = false
    private var covered = false
    private var stillFrame = false

    /** The local date's peak meteor night, looked up when the view is shown. */
    private var meteorNight = false
    internal var today: () -> Boolean = { NovaEasterEggs.isMeteorNight() }
    private var burstStartedAt = 0L
    private var burstUntil = 0L
    private val burstAngles = FloatArray(BURST_SPARKS)
    private var showerUntil = 0L
    private var brightened: Star? = null
    private var brightenedAt = 0L
    private var brightenedUntil = 0L
    private val redrawWhenAMomentEnds = Runnable { invalidate() }

    /** Lets the North Star glow. Its owner keeps this true only while the host answers. */
    var northStar = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    internal val burstShowing: Boolean get() = SystemClock.uptimeMillis() < burstUntil
    internal val showerShowing: Boolean get() = meteorNight || SystemClock.uptimeMillis() < showerUntil
    internal val brightenedStarShowing: Boolean get() = brightened != null && SystemClock.uptimeMillis() < brightenedUntil
    internal val stillImageOnly: Boolean get() = stillFrame

    /** SUPERNOVA: the burst, three seconds of it. */
    fun playSupernova() {
        if (NovaSkyQuiet.active) return
        val now = SystemClock.uptimeMillis()
        burstStartedAt = now
        burstUntil = now + BURST_MS
        for (i in burstAngles.indices) {
            burstAngles[i] = (i + Random.nextFloat()) / burstAngles.size * PI.toFloat() * 2f
        }
        showMoment(BURST_MS)
    }

    /** LEONIDS: a few seconds of what a peak night looks like. */
    fun previewMeteors() {
        if (NovaSkyQuiet.active) return
        showerUntil = SystemClock.uptimeMillis() + SHOWER_PREVIEW_MS
        showMoment(SHOWER_PREVIEW_MS)
    }

    /** A host came online: one star brightens briefly. */
    fun brightenOneStar() {
        if (NovaSkyQuiet.active) return
        val star = stars.filter { it.isBright }.randomOrNull() ?: stars.randomOrNull() ?: return
        val now = SystemClock.uptimeMillis()
        brightened = star
        brightenedAt = now
        brightenedUntil = now + BRIGHTEN_MS
        showMoment(BRIGHTEN_MS)
    }

    /** The frame loop carries a moment while it runs; a still image needs one frame now and one after. */
    private fun showMoment(lastsMs: Long) {
        invalidate()
        removeCallbacks(redrawWhenAMomentEnds)
        postDelayed(redrawWhenAMomentEnds, lastsMs)
    }

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
            // The 0.002 floor applies to both densities; precedence used to fold it into
            // the sparse case only, so dense stars could twinkle at almost zero speed.
            twinkleSpeed = Random.nextFloat() * (if (dense) 0.008f else 0.005f) + 0.002f,
            twinklePhase = Random.nextFloat() * PI.toFloat() * 2f,
            isBright = isBright
        )
    }

    private fun createShootingStar(): ShootingStar {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val angle = Random.nextFloat() * 0.5f + 0.2f
        val dx = cos(angle)
        val dy = sin(angle)
        return ShootingStar(
            x = Random.nextFloat() * w * 0.8f,
            y = Random.nextFloat() * h * 0.4f,
            speed = Random.nextFloat() * 4f + 3f,
            dx = dx, dy = dy,
            life = 0, maxLife = Random.nextInt(20, 60),
            size = Random.nextFloat() * 1.5f + 0.5f,
            trail = LinearGradient(
                0f, 0f, -dx * 20f, -dy * 20f,
                (0xFF shl 24) or starRgb, starRgb,
                Shader.TileMode.CLAMP
            ),
        )
    }

    private fun createNebula(index: Int): Nebula {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        // One accent means every node is that accent, which is what this did before the
        // palette existed. Portable Chrome supplies four, so the nodes cycle them and no
        // two consecutive nebulae share a hue.
        val tint = ambientAccents[index % ambientAccents.size]
        val radius = Random.nextFloat() * 200f + 100f
        val opacity = Random.nextFloat() * 0.03f + 0.01f
        val core = ((opacity * 255).toInt() shl 24) or (Color.red(tint) shl 16) or (Color.green(tint) shl 8) or Color.blue(tint)
        return Nebula(
            x = Random.nextFloat() * w, y = Random.nextFloat() * h,
            radius = radius,
            shader = RadialGradient(
                0f, 0f, radius,
                intArrayOf(core, 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            ),
            drift = (Random.nextFloat() - 0.5f) * 0.02f,
        )
    }

    private fun rebuild() {
        val count = if (dense) 300 else 120
        stars = MutableList(count) { createStar() }
        nebulae = if (dense) List(6) { createNebula(it) } else emptyList()
        shootingStars.clear()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (!running && !stillFrame) return
        val animate = running && !stillFrame
        val w = width.toFloat()
        val h = height.toFloat()

        // Theme-aware background (Polaris navy or OLED black). Skipped when the view
        // is an overlay so cinematic artwork below stays visible.
        if (paintsOpaqueBackground) {
            canvas.drawColor(bgColor)
        }

        // Nebulae
        for (n in nebulae) {
            if (animate) n.x += n.drift
            shaderMatrix.setTranslate(n.x, n.y)
            n.shader.setLocalMatrix(shaderMatrix)
            paint.shader = n.shader
            canvas.drawCircle(n.x, n.y, n.radius, paint)
            paint.shader = null
        }

        // Stars
        for (p in stars) {
            if (animate) {
                p.x += p.speedX
                p.y += p.speedY
                if (p.x < -5f) p.x = w + 5f
                if (p.x > w + 5f) p.x = -5f
                if (p.y < -5f) p.y = h + 5f
                if (p.y > h + 5f) p.y = -5f
                p.twinklePhase += p.twinkleSpeed
            }
            val twinkle = sin(p.twinklePhase) * 0.3f + 0.7f
            val alpha = (p.opacity * twinkle * 255).toInt().coerceIn(0, 255)

            // Star core
            paint.color = (alpha shl 24) or starRgb
            canvas.drawCircle(p.x, p.y, p.size, paint)

            // Glow
            if (p.isBright && alpha > 76) {
                paint.color = ((alpha * 0.08f).toInt().coerceIn(0, 255) shl 24) or starRgb
                canvas.drawCircle(p.x, p.y, p.size * 3f, paint)

                // Cross sparkle
                if (dense && p.size > 1.5f && twinkle > 0.85f) {
                    linePaint.color = ((alpha * 0.3f).toInt().coerceIn(0, 255) shl 24) or starRgb
                    linePaint.strokeWidth = 0.5f
                    val len = p.size * 4f
                    canvas.drawLine(p.x - len, p.y, p.x + len, p.y, linePaint)
                    canvas.drawLine(p.x, p.y - len, p.x, p.y + len, linePaint)
                }
            }
        }

        val now = SystemClock.uptimeMillis()
        val quiet = NovaSkyQuiet.active
        if (!quiet) {
            if (northStar) drawNorthStar(canvas, w, h)
            if (now < brightenedUntil) drawBrightened(canvas, now, animate)
            if (now < burstUntil) drawBurst(canvas, now, w, h, animate)
        }
        val shower = !quiet && (meteorNight || now < showerUntil)

        if (!animate) {
            if (shower) drawStillMeteors(canvas, w, h)
            return
        }

        // Shooting stars
        val spawnChance = when {
            shower -> SHOWER_SPAWN_CHANCE
            dense -> 0.005f
            else -> 0f
        }
        if (Random.nextFloat() < spawnChance && shootingStars.size < if (shower) SHOWER_MAX_METEORS else 2) {
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

            // Trail: the fixed gradient moved to the head; the paint alpha fades it.
            shaderMatrix.setTranslate(s.x, s.y)
            s.trail.setLocalMatrix(shaderMatrix)
            linePaint.shader = s.trail
            linePaint.color = (alpha shl 24) or 0x00FFFFFF
            linePaint.strokeWidth = s.size
            canvas.drawLine(s.x, s.y, s.x - s.dx * 20f, s.y - s.dy * 20f, linePaint)
            linePaint.shader = null

            // Head
            paint.color = (alpha shl 24) or starRgb
            canvas.drawCircle(s.x, s.y, s.size, paint)

            if (s.life >= s.maxLife || s.x > w + 50 || s.y > h + 50) {
                iter.remove()
            }
        }

        // Animate at ~60fps
        postInvalidateOnAnimation()
    }

    private fun drawNorthStar(canvas: Canvas, w: Float, h: Float) {
        val x = w * NORTH_STAR_X
        val y = h * NORTH_STAR_Y
        paint.color = (0x26 shl 24) or starRgb
        canvas.drawCircle(x, y, NORTH_STAR_RADIUS * 4f, paint)
        paint.color = (0xFF shl 24) or starRgb
        canvas.drawCircle(x, y, NORTH_STAR_RADIUS, paint)
        linePaint.color = (0x80 shl 24) or starRgb
        linePaint.strokeWidth = 1f
        val len = NORTH_STAR_RADIUS * 6f
        canvas.drawLine(x - len, y, x + len, y, linePaint)
        canvas.drawLine(x, y - len, x, y + len, linePaint)
    }

    private fun drawBrightened(canvas: Canvas, now: Long, animate: Boolean) {
        val star = brightened ?: return
        // In and out over its time; a still image holds it at its brightest.
        val strength = if (animate) sin((now - brightenedAt).toFloat() / BRIGHTEN_MS * PI.toFloat()) else 1f
        val alpha = (strength.coerceIn(0f, 1f) * 255).toInt()
        paint.color = ((alpha * 0.3f).toInt() shl 24) or starRgb
        canvas.drawCircle(star.x, star.y, star.size * 8f, paint)
        paint.color = (alpha shl 24) or starRgb
        canvas.drawCircle(star.x, star.y, star.size * 2.5f, paint)
    }

    private fun drawBurst(canvas: Canvas, now: Long, w: Float, h: Float, animate: Boolean) {
        val progress = if (animate) (now - burstStartedAt).toFloat() / BURST_MS else BURST_STILL_PROGRESS
        val fade = 1f - progress.coerceIn(0f, 1f)
        val cx = w / 2f
        val cy = h / 2f
        val reach = hypot(w, h) / 2f
        val tint = ambientAccents[0] and 0x00FFFFFF
        paint.color = ((fade * 0.9f * 255).toInt() shl 24) or starRgb
        canvas.drawCircle(cx, cy, reach * (0.03f + 0.05f * progress), paint)
        paint.color = ((fade * 0.25f * 255).toInt() shl 24) or tint
        canvas.drawCircle(cx, cy, reach * (0.08f + 0.2f * progress), paint)
        linePaint.color = ((fade * 0.6f * 255).toInt() shl 24) or tint
        linePaint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, reach * progress, linePaint)
        linePaint.color = ((fade * 0.8f * 255).toInt() shl 24) or starRgb
        linePaint.strokeWidth = 1.5f
        for (angle in burstAngles) {
            val inner = reach * progress * 0.55f
            val outer = reach * progress * 0.8f
            canvas.drawLine(cx + cos(angle) * inner, cy + sin(angle) * inner, cx + cos(angle) * outer, cy + sin(angle) * outer, linePaint)
        }
    }

    /** A shower with the motion left out: a few streaks fixed across the sky's upper part. */
    private fun drawStillMeteors(canvas: Canvas, w: Float, h: Float) {
        linePaint.color = (0xB3 shl 24) or starRgb
        linePaint.strokeWidth = 1.5f
        for ((fx, fy) in STILL_METEORS) {
            val x = fx * w
            val y = fy * h
            canvas.drawLine(x, y, x - STILL_METEOR_LENGTH, y - STILL_METEOR_LENGTH * 0.5f, linePaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshMotionGate()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(redrawWhenAMomentEnds)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    /** An opaque screen is drawn over this view; stop the frame loop until it goes away. */
    fun setCovered(covered: Boolean) {
        if (this.covered == covered) return
        this.covered = covered
        updateRunning()
    }

    fun pause() {
        paused = true
        updateRunning()
    }

    fun resume() {
        paused = false
        refreshMotionGate()
        updateRunning()
    }

    private fun updateRunning() {
        val visible = isAttachedToWindow && windowVisibility == VISIBLE && isVisible && !covered && !paused
        if (visible == running) return
        running = visible
        if (visible) postInvalidateOnAnimation()
    }

    /** One still frame instead of a loop when the person asked for less motion. */
    private fun refreshMotionGate() {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val powerSave = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        stillFrame = scale == 0f || powerSave
        meteorNight = today()
    }

    private companion object {
        const val BURST_MS = 3_000L
        const val BURST_SPARKS = 36
        /** Where a still image of the burst stands: well spread and still bright. */
        const val BURST_STILL_PROGRESS = 0.35f
        const val SHOWER_PREVIEW_MS = 6_000L
        const val SHOWER_SPAWN_CHANCE = 0.03f
        const val SHOWER_MAX_METEORS = 4
        const val BRIGHTEN_MS = 1_500L
        const val NORTH_STAR_X = 0.78f
        const val NORTH_STAR_Y = 0.18f
        const val NORTH_STAR_RADIUS = 3f
        const val STILL_METEOR_LENGTH = 90f
        val STILL_METEORS = listOf(0.28f to 0.22f, 0.55f to 0.34f, 0.82f to 0.14f)
    }
}

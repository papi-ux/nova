package com.papi.nova.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.waterfall
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceManager
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Physical window coordinates, independent of layout direction or a screen's letterbox. */
internal data class NovaCameraWindow(val bounds: Rect, val cameras: List<Rect>)
internal data class NovaCameraPadding(
    val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f,
)

internal fun novaCameraPadding(
    target: Rect,
    window: NovaCameraWindow,
    minimumContentWidth: Float = 0f,
): NovaCameraPadding {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f
    for (camera in window.cameras) {
        if (camera.isEmpty || target.isEmpty || !target.overlaps(camera)) continue
        val edge = listOf(
            camera.left - window.bounds.left,
            camera.top - window.bounds.top,
            window.bounds.right - camera.right,
            window.bounds.bottom - camera.bottom,
        ).withIndex().minBy { it.value }.index
        when (edge) {
            0 -> left = maxOf(left, camera.right - target.left)
            1 -> top = maxOf(top, camera.bottom - target.top)
            2 -> right = maxOf(right, target.right - camera.left)
            3 -> bottom = maxOf(bottom, target.bottom - camera.top)
        }
    }
    // A narrow control must keep its touch-width floor. Let its wrapping region grow below the
    // camera instead of consuming all its width. Only that intersecting region gains height.
    if (left + right > 0f && target.width - left - right < minimumContentWidth) {
        top = maxOf(top, window.cameras.filter { target.overlaps(it) }.maxOf { it.bottom - target.top })
        left = 0f
        right = 0f
    }
    return NovaCameraPadding(left.coerceAtLeast(0f), top.coerceAtLeast(0f), right.coerceAtLeast(0f), bottom.coerceAtLeast(0f))
}

/** Test fixtures can supply camera geometry while exercising the real modifier and controls. */
internal val LocalNovaCameraWindow = compositionLocalOf<NovaCameraWindow?> { null }

/** One observer per Compose host; it never replaces Android/Compose's insets listener. */
private class CameraWindowTracker(view: View) {
    private val source = WeakReference(view)
    var window by mutableStateOf(NovaCameraWindow(Rect.Zero, emptyList()))
        private set
    private var users = 0
    private var observer: ViewTreeObserver? = null
    private val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { observe() }
        override fun onViewDetachedFromWindow(view: View) { stopObserving() }
    }

    private fun observe() {
        stopObserving()
        observer = source.get()?.viewTreeObserver?.also { it.addOnGlobalLayoutListener(listener) }
        update()
    }

    private fun stopObserving() {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        observer = null
    }

    fun update() {
        val root = source.get()?.rootView ?: return
        val location = IntArray(2)
        root.getLocationInWindow(location)
        val bounds = Rect(location[0].toFloat(), location[1].toFloat(),
            (location[0] + root.width).toFloat(), (location[1] + root.height).toFloat())
        val cameras = ViewCompat.getRootWindowInsets(root)?.displayCutout?.boundingRects.orEmpty().map {
            Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        }
        window = NovaCameraWindow(bounds, cameras)
    }

    fun retain() {
        if (users++ == 0) {
            source.get()?.let {
                it.addOnAttachStateChangeListener(attachListener)
                if (ViewCompat.isAttachedToWindow(it)) observe()
            }
            update()
        }
    }

    fun release() {
        if (--users == 0) {
            stopObserving()
            source.get()?.removeOnAttachStateChangeListener(attachListener)
        }
    }
}

private val cameraWindows = WeakHashMap<View, CameraWindowTracker>()

/**
 * Clears only this bounded control/text region. Put it before the region's size/click modifiers,
 * never on an entire menu or full-width header. The observed region includes its clearance,
 * while the text/target is inside that padding. Parents must wrap the region's height; actual
 * toolbar fixtures check that centered placement settles and keeps the inner target intact.
 */
@Composable
fun Modifier.novaAvoidCameraCutout(touchTarget: Boolean = false): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val minimumTouch = LocalViewConfiguration.current.minimumTouchTargetSize
    val touchWidth = if (touchTarget) with(density) { minimumTouch.width.toPx() } else 0f
    val touchHeight = if (touchTarget) with(density) { minimumTouch.height.toPx() } else 0f
    val tracker = remember(view) { cameraWindows.getOrPut(view) { CameraWindowTracker(view) } }
    DisposableEffect(tracker) {
        tracker.retain()
        onDispose { tracker.release() }
    }
    val cutout = WindowInsets.displayCutout
    // Insets can change before the next layout, and rotation/window-size changes invalidate the
    // physical coordinates even when a camera's dimensions are unchanged.
    LaunchedEffect(configuration, cutout.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr),
        cutout.getTop(density), cutout.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr), cutout.getBottom(density)) {
        tracker.update()
    }
    var target by remember { mutableStateOf(Rect.Zero) }
    val window = LocalNovaCameraWindow.current ?: tracker.window
    val padding = novaCameraPadding(target, window, with(density) { 48.dp.toPx() })
    return this
        .onGloballyPositioned { coordinates ->
            val at = coordinates.positionInWindow()
            // Foundation expands a small clickable control to its physical minimum target.
            // Account for that same floor around the inner control, excluding our own padding
            // from its visual size so repeated layouts cannot shrink/grow the floor estimate.
            val contentWidth = (coordinates.size.width - padding.left - padding.right).coerceAtLeast(0f)
            val contentHeight = (coordinates.size.height - padding.top - padding.bottom).coerceAtLeast(0f)
            val horizontalReach = ((touchWidth - contentWidth) / 2f).coerceAtLeast(0f)
            val verticalReach = ((touchHeight - contentHeight) / 2f).coerceAtLeast(0f)
            target = Rect(at.x - horizontalReach, at.y - verticalReach,
                at.x + coordinates.size.width + horizontalReach, at.y + coordinates.size.height + verticalReach)
        }
        .absolutePadding(
            left = with(density) { padding.left.toDp() }, top = with(density) { padding.top.toDp() },
            right = with(density) { padding.right.toDp() }, bottom = with(density) { padding.bottom.toDp() },
        )
}

/** Visible bars, IME and waterfall remain safety areas. A small camera is handled per control. */
@Composable
fun Modifier.novaScreenInsets(
    sides: WindowInsetsSides = WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical,
): Modifier {
    val context = LocalView.current.context
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var hidden by remember(context) { mutableStateOf(NovaSystemBars.isHidden(context)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == NovaSystemBars.KEY_HIDE_SYSTEM_BARS) hidden = NovaSystemBars.isHidden(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val stream = context.cameraActivity()?.let(NovaSystemBars::isStream) == true
    // A swipe's transient bars overlay hidden-bar content without changing its geometry. Desktop
    // caption bars remain real chrome, and ordinary visible bars still reserve their reported area.
    val bars = if (hidden || stream) WindowInsets.captionBar else WindowInsets.systemBars
    return windowInsetsPadding(bars.union(WindowInsets.ime).union(WindowInsets.waterfall).only(sides))
}

private fun Context.cameraActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? Activity
}

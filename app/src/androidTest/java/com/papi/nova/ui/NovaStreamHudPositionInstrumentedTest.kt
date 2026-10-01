package com.papi.nova.ui

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.utils.UiHelper
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Activity/window/ComposeView placement, with no Game, stream or fake inset root.
 * The public HUD accessibility actions reach NovaStreamHud's actual persistence and layout.
 */
@RunWith(AndroidJUnit4::class)
class NovaStreamHudPositionInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val root get() = rule.activity.findViewById<ViewGroup>(android.R.id.content)
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(rule.activity)
    private var hud: NovaStreamHud? = null
    private var originalPrefs = emptyMap<String, Any?>()
    private var originalRootSize: Pair<Int, Int>? = null
    private var originalSystemUi = 0
    private var originalStatusColor = 0
    private var originalNavigationColor = 0
    private var originalBarBehavior = 0
    private var originalStatusVisible = true
    private var originalNavigationVisible = true

    @Before fun prepareActualWindow() {
        rule.setContent {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(ComposeColor(0xFF172737))
                val stripe = size.width / 12f
                repeat(12) { index ->
                    drawRect(if (index % 2 == 0) ComposeColor(0xFFB7783F) else ComposeColor(0xFF294A64),
                        topLeft = Offset(index * stripe, size.height / 3f),
                        size = androidx.compose.ui.geometry.Size(stripe, size.height * 2f / 3f))
                }
            }
        }
        rule.runOnUiThread {
            originalPrefs = KEYS.associateWith { prefs.all[it] }
            val window = rule.activity.window
            val decor = window.decorView
            originalRootSize = root.layoutParams.let { it.width to it.height }
            @Suppress("DEPRECATION")
            originalSystemUi = decor.systemUiVisibility
            @Suppress("DEPRECATION")
            originalStatusColor = window.statusBarColor
            @Suppress("DEPRECATION")
            originalNavigationColor = window.navigationBarColor
            originalBarBehavior = WindowCompat.getInsetsController(window, decor).systemBarsBehavior
            ViewCompat.getRootWindowInsets(decor)?.let {
                originalStatusVisible = it.isVisible(WindowInsetsCompat.Type.statusBars())
                originalNavigationVisible = it.isVisible(WindowInsetsCompat.Type.navigationBars())
            }
            val edit = prefs.edit()
            KEYS.forEach { edit.remove(it) }
            edit.putString(MODE, "minimal").putInt(NovaHudPreferences.KEY_OPACITY, 100).commit()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            run { window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT }
        }
        rule.waitForIdle()
    }

    @After fun restoreFixtureState() {
        rule.runOnUiThread {
            hud?.dismiss()
            hud = null
            originalRootSize?.let { size ->
                root.layoutParams = root.layoutParams.apply { width = size.first; height = size.second }
            }
            prefs.edit().apply {
                KEYS.forEach { remove(it) }
                originalPrefs.forEach { (key, value) -> restoreValue(key, value) }
            }.commit()
            val window = rule.activity.window
            // ComponentActivity's test-manifest window starts with the default fitted content.
            WindowCompat.setDecorFitsSystemWindows(window, true)
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = originalStatusColor
                window.navigationBarColor = originalNavigationColor
                window.decorView.systemUiVisibility = originalSystemUi
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = originalBarBehavior
                if (originalStatusVisible) show(WindowInsetsCompat.Type.statusBars()) else hide(WindowInsetsCompat.Type.statusBars())
                if (originalNavigationVisible) show(WindowInsetsCompat.Type.navigationBars()) else hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
        rule.waitForIdle()
    }

    @Test fun hiddenBarsPlaceAllFourCornersWithoutAnExtraBarOffset() {
        setBars(visible = false)
        showHud()
        val initial = snapshot()
        assertTrue("the owned fixture has real potential bar insets to discriminate hidden-bar offsets",
            initial.potentialTop > 0 || initial.potentialBottom > 0 || initial.potentialLeft > 0 || initial.potentialRight > 0)
        for (corner in NovaHudCorner.entries) {
            moveByActualAction(corner)
            val actual = assertCorner(corner)
            assertEquals("hidden bars consume no top space", 0, actual.barTop)
            assertEquals("hidden bars consume no bottom space", 0, actual.barBottom)
            if (corner == NovaHudCorner.TOP_LEFT) {
                assertEquals("top-left has only the safety margin and any actual cutout", actual.safeLeft.toFloat(), actual.hudX, 1f)
                assertEquals("top-left has no extra hidden status-bar offset", actual.safeTop.toFloat(), actual.hudY, 1f)
            }
            capture("hidden-${corner.name.lowercase()}", actual)
        }
    }

    @Test fun visibleBarsKeepEveryCornerInsideTheWindowSafeArea() {
        setBars(visible = true)
        showHud()
        assertTrue("real visible system bars obstruct part of the window", snapshot().let {
            it.barLeft + it.barTop + it.barRight + it.barBottom > 0
        })
        for (corner in NovaHudCorner.entries) {
            moveByActualAction(corner)
            capture("visible-${corner.name.lowercase()}", assertCorner(corner))
        }
    }

    @Test fun resetClearsLegacyCoordinatesAndSurvivesANewHud() {
        setBars(visible = false)
        showHud()
        moveByActualAction(NovaHudCorner.BOTTOM_RIGHT)
        replaceHud()
        capture("persisted-bottom-right", assertCorner(NovaHudCorner.BOTTOM_RIGHT))
        rule.runOnUiThread { prefs.edit().putFloat(LEGACY_X, 900f).putFloat(LEGACY_Y, 700f).commit() }
        action("Reset HUD position")
        capture("reset-top-left", assertCorner(NovaHudCorner.TOP_LEFT))
        rule.runOnUiThread {
            assertFalse("Reset discards legacy X", prefs.contains(LEGACY_X))
            assertFalse("Reset discards legacy Y", prefs.contains(LEGACY_Y))
            assertEquals(0f, prefs.getFloat(X_FRACTION, Float.NaN), 0f)
            assertEquals(0f, prefs.getFloat(Y_FRACTION, Float.NaN), 0f)
        }
        replaceHud()
        capture("reset-restored-top-left", assertCorner(NovaHudCorner.TOP_LEFT))
    }

    @Test fun chosenCornerSurvivesContentResizeAndReturnsWhenRestored() {
        setBars(visible = false)
        showHud()
        moveByActualAction(NovaHudCorner.BOTTOM_RIGHT)
        val before = assertCorner(NovaHudCorner.BOTTOM_RIGHT)
        capture("resize-before", before)
        val width = (before.rootWidth * .8f).toInt()
        val height = (before.rootHeight * .72f).toInt()
        assertTrue("the bounded resize leaves room for the actual HUD", width > before.hudWidth + before.safeLeft + before.safeRight &&
            height > before.hudHeight + before.safeTop + before.safeBottom)
        rule.runOnUiThread { root.layoutParams = root.layoutParams.apply { this.width = width; this.height = height } }
        rule.waitUntil(10_000) { snapshot().let { it.rootWidth == width && it.rootHeight == height } }
        val smaller = assertCorner(NovaHudCorner.BOTTOM_RIGHT)
        assertTrue("layout listener actually moved the HUD horizontally", smaller.hudX < before.hudX - 1f)
        assertTrue("layout listener actually moved the HUD vertically", smaller.hudY < before.hudY - 1f)
        capture("resize-small", smaller)
        rule.runOnUiThread {
            val size = checkNotNull(originalRootSize)
            root.layoutParams = root.layoutParams.apply { this.width = size.first; this.height = size.second }
        }
        rule.waitUntil(10_000) { snapshot().let { it.rootWidth == before.rootWidth && it.rootHeight == before.rootHeight } }
        val restored = assertCorner(NovaHudCorner.BOTTOM_RIGHT)
        assertEquals(before.hudX, restored.hudX, 1f)
        assertEquals(before.hudY, restored.hudY, 1f)
        capture("resize-restored", restored)
    }

    @Test fun fittedContentDoesNotCountVisibleSystemBarsTwice() {
        rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, true) }
        setBars(visible = true)
        showHud()
        val fitted = snapshot()
        assertTrue("native fitted content already excludes a visible bar", fitted.rootTop >= fitted.barTop && fitted.rootTop > 0 ||
            fitted.decorHeight - fitted.rootTop - fitted.rootHeight >= fitted.barBottom && fitted.barBottom > 0)
        for (corner in listOf(NovaHudCorner.TOP_LEFT, NovaHudCorner.BOTTOM_RIGHT)) {
            moveByActualAction(corner)
            capture("fitted-${corner.name.lowercase()}", assertCorner(corner))
        }
    }

    private fun setBars(visible: Boolean) {
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).apply {
                if (visible) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        rule.waitUntil(10_000) {
            var ready = false
            rule.runOnUiThread {
                val insets = ViewCompat.getRootWindowInsets(root)
                val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
                ready = bars != null && if (visible) bars.left + bars.top + bars.right + bars.bottom > 0
                    else bars.left + bars.top + bars.right + bars.bottom == 0
            }
            ready
        }
        rule.waitForIdle()
    }

    private fun showHud() {
        rule.runOnUiThread { hud = NovaStreamHud(rule.activity).also { it.show() } }
        rule.waitUntil(10_000) { snapshotOrNull()?.let { it.hudWidth > 0 && it.hudHeight > 0 } == true }
        rule.waitForIdle()
    }

    private fun replaceHud() {
        rule.runOnUiThread { hud?.dismiss() }
        rule.waitUntil(10_000) { actualHudView() == null }
        showHud()
    }

    private fun actualHudView(): ComposeView? {
        var view: ComposeView? = null
        rule.runOnUiThread {
            // setContent owns the first ComposeView; NovaStreamHud attaches the second directly.
            view = (0 until root.childCount).map(root::getChildAt).filterIsInstance<ComposeView>().drop(1).singleOrNull()
        }
        return view
    }

    private fun action(label: String) {
        val actions = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Stream statistics"))
            .fetchSemanticsNode().config[SemanticsProperties.CustomActions]
        val selected = actions.single { it.label == label }
        rule.runOnUiThread { assertTrue("the actual HUD action answers $label", selected.action()) }
        rule.waitForIdle()
    }

    private fun moveByActualAction(corner: NovaHudCorner) = action("Move HUD to ${corner.label}")

    private fun assertCorner(corner: NovaHudCorner): Geometry {
        val right = corner == NovaHudCorner.TOP_RIGHT || corner == NovaHudCorner.BOTTOM_RIGHT
        val bottom = corner == NovaHudCorner.BOTTOM_LEFT || corner == NovaHudCorner.BOTTOM_RIGHT
        fun correct(g: Geometry): Boolean {
            val x = if (right) g.rootWidth - g.hudWidth - g.safeRight else g.safeLeft
            val y = if (bottom) g.rootHeight - g.hudHeight - g.safeBottom else g.safeTop
            return kotlin.math.abs(g.hudX - x) <= 1f && kotlin.math.abs(g.hudY - y) <= 1f
        }
        rule.waitUntil(10_000) { snapshotOrNull()?.let(::correct) == true }
        val actual = snapshot()
        assertTrue("actual laid-out HUD matches $corner", correct(actual))
        assertTrue("HUD left stays inside safe content", actual.hudX >= actual.safeLeft - 1f)
        assertTrue("HUD top stays inside safe content", actual.hudY >= actual.safeTop - 1f)
        assertTrue("HUD right stays inside safe content", actual.hudX + actual.hudWidth <= actual.rootWidth - actual.safeRight + 1f)
        assertTrue("HUD bottom stays inside safe content", actual.hudY + actual.hudHeight <= actual.rootHeight - actual.safeBottom + 1f)
        rule.runOnUiThread {
            assertEquals(corner, checkNotNull(hud).positionCorner)
            assertEquals(if (right) 1f else 0f, prefs.getFloat(X_FRACTION, Float.NaN), 0f)
            assertEquals(if (bottom) 1f else 0f, prefs.getFloat(Y_FRACTION, Float.NaN), 0f)
        }
        return actual
    }

    private fun snapshot(): Geometry = checkNotNull(snapshotOrNull()) { "Actual HUD is not attached to the content window" }

    private fun snapshotOrNull(): Geometry? {
        val view = actualHudView() ?: return null
        var result: Geometry? = null
        rule.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(root) ?: return@runOnUiThread
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val potential = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())
            val location = IntArray(2).also(root::getLocationInWindow)
            val decor = rule.activity.window.decorView
            val density = rule.activity.resources.displayMetrics.density
            val tv = UiHelper.isTvDevice(rule.activity)
            // Independent window-space oracle: safety edges intersect the actual content rectangle.
            val marginX = ((if (tv) 48f else 12f) * density).toInt()
            val marginY = ((if (tv) 27f else 12f) * density).toInt()
            val safeLeft = (maxOf(bars.left, cutout.left) - location[0]).coerceAtLeast(0) + marginX
            val safeTop = (maxOf(bars.top, cutout.top) - location[1]).coerceAtLeast(0) + marginY
            val safeRight = (location[0] + root.width - (decor.width - maxOf(bars.right, cutout.right))).coerceAtLeast(0) + marginX
            val safeBottom = (location[1] + root.height - (decor.height - maxOf(bars.bottom, cutout.bottom))).coerceAtLeast(0) + marginY
            assertTrue("overlay is the actual attached HUD ComposeView", view.isAttachedToWindow && view.parent === root)
            result = Geometry(root.width, root.height, location[0], location[1], decor.width, decor.height,
                view.width, view.height, view.x, view.y, safeLeft, safeTop, safeRight, safeBottom,
                bars.left, bars.top, bars.right, bars.bottom, potential.left, potential.top, potential.right, potential.bottom)
        }
        return result
    }

    private fun capture(name: String, geometry: Geometry) {
        val suffix = InstrumentationRegistry.getArguments().getString("shotSuffix", "native")
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "hud-position")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "$name-$suffix.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        File(directory, "$name-$suffix.json").writeText(JSONObject().apply {
            put("fixture", "actual NovaStreamHud in ComponentActivity; no Game or stream")
            put("geometry", JSONObject(geometry.asMap()))
        }.toString(2))
    }

    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is String -> putString(key, value)
            is Float -> putFloat(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Boolean -> putBoolean(key, value)
            else -> error("Unexpected fixture preference type for $key")
        }
    }

    private data class Geometry(val rootWidth: Int, val rootHeight: Int, val rootLeft: Int, val rootTop: Int,
        val decorWidth: Int, val decorHeight: Int, val hudWidth: Int, val hudHeight: Int, val hudX: Float, val hudY: Float,
        val safeLeft: Int, val safeTop: Int, val safeRight: Int, val safeBottom: Int,
        val barLeft: Int, val barTop: Int, val barRight: Int, val barBottom: Int,
        val potentialLeft: Int, val potentialTop: Int, val potentialRight: Int, val potentialBottom: Int) {
        fun asMap(): Map<String, Any> = mapOf("rootWidth" to rootWidth, "rootHeight" to rootHeight,
            "rootLeft" to rootLeft, "rootTop" to rootTop, "decorWidth" to decorWidth, "decorHeight" to decorHeight,
            "hudWidth" to hudWidth, "hudHeight" to hudHeight, "hudX" to hudX, "hudY" to hudY,
            "safeLeft" to safeLeft, "safeTop" to safeTop, "safeRight" to safeRight, "safeBottom" to safeBottom,
            "barLeft" to barLeft, "barTop" to barTop, "barRight" to barRight, "barBottom" to barBottom,
            "potentialLeft" to potentialLeft, "potentialTop" to potentialTop, "potentialRight" to potentialRight, "potentialBottom" to potentialBottom)
    }

    private companion object {
        const val MODE = "nova_polaris_hud_mode"
        const val X_FRACTION = "nova_polaris_hud_position_x_fraction"
        const val Y_FRACTION = "nova_polaris_hud_position_y_fraction"
        const val LEGACY_X = "nova_polaris_hud_x"
        const val LEGACY_Y = "nova_polaris_hud_y"
        val KEYS = listOf(MODE, X_FRACTION, Y_FRACTION, LEGACY_X, LEGACY_Y, NovaHudPreferences.KEY_OPACITY)
    }
}

package com.papi.nova.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.panel.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fixture-only visual proof: no host, stream, pairing, device preferences or network writes. */
@RunWith(AndroidJUnit4::class)
class NovaOverlayAppearanceInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "native-smoke/overlay-appearance")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun allHudModesAndLargeTextDrawWithoutLabelBoxes() {
        var mode by mutableStateOf(NovaHudMode.DEBUG)
        var opacity by mutableStateOf(0.64f)
        var fontScale by mutableStateOf(1f)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NovaComposeTheme {
                    Box(Modifier.fillMaxSize()) {
                        backdrop()
                        NovaStreamHudContent(NovaHudUiState.preview(mode),
                            Modifier.testTag("hud"), opacityScale = opacity)
                    }
                }
            }
        }
        for (choice in NovaHudMode.entries) {
            rule.runOnIdle { mode = choice }
            rule.waitForIdle()
            if (choice == NovaHudMode.DEBUG) {
                val host = rule.onNodeWithText("HOST").getUnclippedBoundsInRoot()
                val client = rule.onNodeWithText("CLIENT").getUnclippedBoundsInRoot()
                assertEquals("normal text keeps all three layers together", host.top.value, client.top.value, 1f)
                val loss = mutableListOf<TextLayoutResult>()
                rule.onNodeWithText("0%", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(loss) }
                assertEquals("the loss value stays together", 1, loss.single().lineCount)
            }
            shot("hud-${choice.name.lowercase()}-64")
        }
        rule.runOnIdle { mode = NovaHudMode.DEBUG; fontScale = 1.3f }
        rule.waitForIdle()
        val host = rule.onNodeWithText("HOST").getUnclippedBoundsInRoot()
        val client = rule.onNodeWithText("CLIENT").getUnclippedBoundsInRoot()
        assertEquals("833dp emulator has room for three accessible columns", host.top.value, client.top.value, 1f)
        shot("hud-debug-large-text-64")
        rule.runOnIdle { opacity = 0f }
        rule.waitForIdle()
        shot("hud-debug-large-text-clear")
    }

    @Test fun actualStreamPanelDrawsEveryOpacityOverABusyBackground() {
        var percent by mutableStateOf(100)
        rule.setContent {
            NovaComposeTheme(menuOpacityPercent = percent) {
                Box(Modifier.fillMaxSize()) {
                    backdrop()
                    NovaPanelFrame(NovaEdge.Start, NovaPanelWidth.Standard, true, {}, {},
                        scrim = NovaScrim.Stream, overStream = true) {
                        val state = remember { MutableStateFlow(NovaQuickMenuUiState.preview(rule.activity).copy(advancedExpanded = true)) }
                        val panel = remember { NovaPanelState().apply { open(CommandCenterPage.Root("Command Center")) } }
                        NovaPageStackHost(state = panel) { NovaQuickMenuContent(state = state, callbacks = NovaQuickMenuCallbacks()) }
                    }
                }
            }
        }
        val samples = mutableListOf<PanelPixels>()
        for (choice in listOf(100, 64, 25, 0)) {
            rule.runOnIdle { percent = choice }
            rule.waitForIdle()
            samples += panelShot(choice)
        }
        assertPanelOpacity(samples)
    }

    private data class RegionPixels(val body: Rect, val backdrop: Rect,
        val bodyRgb: List<Double>, val backdropRgb: List<Double>) {
        val distance get() = bodyRgb.zip(backdropRgb).sumOf { (a, b) -> abs(a - b) } / 3.0
    }

    private data class PanelPixels(val percent: Int, val regions: List<RegionPixels>)

    private data class NativeFrame(val bitmap: Bitmap, val screenLocation: IntArray,
        val committedFrames: Int, val pixelCopyResult: Int)

    private data class DisplayCapture(val bitmap: Bitmap, val attempts: Int,
        val matchingFrames: Int, val lastPixelDelta: Int, val maximumObservedPixelDelta: Int)

    /** A committed hardware frame may still precede display presentation. Copy it first. */
    private fun committedWindowFrame(): NativeFrame {
        val decor = rule.activity.window.decorView
        val location = IntArray(2)
        var width = 0
        var height = 0
        rule.runOnUiThread {
            assertTrue("native frame proof requires an attached focused hardware window",
                decor.isAttachedToWindow && decor.isHardwareAccelerated && decor.hasWindowFocus())
            decor.getLocationOnScreen(location)
            width = decor.width; height = decor.height
        }
        repeat(2) {
            val committed = CountDownLatch(1)
            val callback = Runnable { committed.countDown() }
            rule.runOnUiThread {
                decor.viewTreeObserver.registerFrameCommitCallback(callback)
                decor.postInvalidateOnAnimation()
            }
            if (!committed.await(2, TimeUnit.SECONDS)) {
                rule.runOnUiThread { decor.viewTreeObserver.unregisterFrameCommitCallback(callback) }
                fail("actual window frame was not committed within two seconds")
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = -1
        rule.runOnUiThread {
            PixelCopy.request(rule.activity.window, bitmap, { code ->
                result = code
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        if (!copied.await(2, TimeUnit.SECONDS) || result != PixelCopy.SUCCESS) {
            bitmap.recycle()
            fail("committed native window PixelCopy was unavailable: $result")
        }
        return NativeFrame(bitmap, location, 2, result)
    }

    /** Compare only bare panel/backdrop pixels, excluding headers, text and SystemUI. */
    private fun presentedScreenshot(frame: NativeFrame, regions: List<Rect>): DisplayCapture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 4_000
        var attempts = 0
        var matching = 0
        var maximum = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            attempts++
            var delta = 0
            for (region in regions) {
                assertTrue("display sample must lie within both native captures: $region",
                    region.left >= frame.screenLocation[0] && region.top >= frame.screenLocation[1] &&
                        region.right <= frame.screenLocation[0] + frame.bitmap.width &&
                        region.bottom <= frame.screenLocation[1] + frame.bitmap.height &&
                        region.right <= bitmap.width && region.bottom <= bitmap.height)
                for (y in region.top until region.bottom) for (x in region.left until region.right) {
                    val displayed = bitmap.getPixel(x, y)
                    val committed = frame.bitmap.getPixel(x - frame.screenLocation[0], y - frame.screenLocation[1])
                    for (shift in listOf(16, 8, 0)) {
                        delta = maxOf(delta, abs(((displayed shr shift) and 255) - ((committed shr shift) and 255)))
                    }
                }
            }
            maximum = maxOf(maximum, delta)
            matching = if (delta <= 2) matching + 1 else 0
            if (matching >= 2) return DisplayCapture(bitmap, attempts, matching, delta, maximum)
            bitmap.recycle()
            SystemClock.sleep(16)
        }
        error("display panel body did not match the committed window in four seconds; attempts=$attempts maxDelta=$maximum")
    }

    /** Actual display pixels in the panel's empty outer gutter, below the header/SystemUI. */
    private fun panelShot(percent: Int): PanelPixels {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "native-smoke/overlay-appearance")
        check(directory.mkdirs() || directory.isDirectory)
        val name = "menu-opacity-$percent"
        val placement = rule.onNode(SemanticsMatcher.keyIsDefined(NovaPanelPlacementKey),
            useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("the fixture must use the actual landscape start panel",
            NovaPanelSide.Left, placement.config[NovaPanelPlacementKey].side)
        assertEquals("the actual Compose surface must have the requested opacity before its native draw",
            percent / 100f, placement.config[NovaPanelPlacementKey].fill.alpha, 0.01f)
        val panel = placement.boundsInRoot
        val location = IntArray(2)
        var density = 0f
        var rootWidth = 0
        rule.runOnUiThread {
            val root = rule.activity.findViewById<View>(android.R.id.content)
            root.getLocationOnScreen(location)
            density = root.resources.displayMetrics.density
            rootWidth = root.width
        }
        // Production content starts at its 12dp padding; 4..8dp is bare panel body.
        // The backdrop repeats every two stripes. Read the same orange stripe outside
        // the panel, retaining the scrim in both samples instead of assuming its alpha.
        val left = location[0] + ceil(panel.left + 4f * density).toInt()
        val right = location[0] + floor(panel.left + 8f * density).toInt()
        val stripe = rootWidth / 12f
        val referenceStripe = (2..10 step 2).firstOrNull {
            it * stripe + panel.left + 4f * density > panel.right + 16f * density
        }
        checkNotNull(referenceStripe) { "no matching unobstructed backdrop stripe" }
        val offset = (referenceStripe * stripe).roundToInt()
        val bounds = listOf(0.42f to 0.52f, 0.61f to 0.71f).map { (start, end) ->
            val top = location[1] + ceil(panel.top + panel.height * start).toInt()
            val bottom = location[1] + floor(panel.top + panel.height * end).toInt()
            Rect(left, top, right, bottom) to Rect(left + offset, top, right + offset, bottom)
        }
        val frame = committedWindowFrame()
        try {
            File(directory, "$name-window.png").outputStream().use {
                assertTrue("the committed actual window PNG must be retained", frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val capture = presentedScreenshot(frame, bounds.flatMap { listOf(it.first, it.second) })
            val bitmap = capture.bitmap
            try {
                File(directory, "$name.png").outputStream().use {
                    assertTrue("the actual display PNG must be retained", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val regions = bounds.map { (body, backdrop) ->
                    RegionPixels(body, backdrop, regionRgb(bitmap, body), regionRgb(bitmap, backdrop))
                }
                val sample = PanelPixels(percent, regions)
                File(directory, "$name.json").writeText(JSONObject().apply {
                    put("fixture", "actual NovaPanelFrame display pixels in ComponentActivity; no Game or stream")
                    put("opacityPercent", percent)
                    put("capture", "two displayed panel/backdrop pixel matches after native frame commits and window PixelCopy")
                    put("frameCommitCallbacks", frame.committedFrames); put("pixelCopyResult", frame.pixelCopyResult)
                    put("windowWidth", frame.bitmap.width); put("windowHeight", frame.bitmap.height)
                    put("windowScreenX", frame.screenLocation[0]); put("windowScreenY", frame.screenLocation[1])
                    put("displayCaptureAttempts", capture.attempts); put("consecutiveMatchingCaptures", capture.matchingFrames)
                    put("lastPixelDelta", capture.lastPixelDelta); put("maximumObservedPixelDelta", capture.maximumObservedPixelDelta)
                    put("width", bitmap.width); put("height", bitmap.height)
                    put("contentX", location[0]); put("contentY", location[1]); put("density", density)
                    put("panelBoundsInRoot", JSONArray(listOf(panel.left, panel.top, panel.right, panel.bottom)))
                    put("regions", JSONArray(regions.map { region -> JSONObject().apply {
                        put("body", JSONArray(listOf(region.body.left, region.body.top, region.body.right, region.body.bottom)))
                        put("backdrop", JSONArray(listOf(region.backdrop.left, region.backdrop.top, region.backdrop.right, region.backdrop.bottom)))
                        put("bodyRgb", JSONArray(region.bodyRgb)); put("backdropRgb", JSONArray(region.backdropRgb))
                        put("distance", region.distance)
                    } }))
                }.toString(2))
                return sample
            } finally {
                bitmap.recycle()
            }
        } finally {
            frame.bitmap.recycle()
        }
    }

    private fun regionRgb(bitmap: Bitmap, region: Rect): List<Double> {
        assertTrue("body/backdrop sample must be wholly inside the actual display: $region",
            region.width() > 0 && region.height() > 0 && region.left >= 0 && region.top >= 0 &&
                region.right <= bitmap.width && region.bottom <= bitmap.height)
        val pixels = IntArray(region.width() * region.height())
        bitmap.getPixels(pixels, 0, region.width(), region.left, region.top, region.width(), region.height())
        return listOf(16, 8, 0).map { shift ->
            val values = pixels.map { (it shr shift) and 255 }
            assertTrue("sample must be unobstructed bare body/backdrop, not text, a card or SystemUI: $region",
                values.max() - values.min() <= 3)
            values.sumOf { it.toDouble() } / values.size
        }
    }

    private fun assertPanelOpacity(samples: List<PanelPixels>) {
        assertEquals(listOf(100, 64, 25, 0), samples.map { it.percent })
        for (region in 0..1) {
            val distances = samples.map { it.regions[region].distance }
            assertTrue("0% must reveal the matching backdrop in panel body region $region: $distances",
                distances.last() <= 2.0)
            assertTrue("100% must visibly cover the backdrop in panel body region $region: $distances",
                distances.first() >= 25.0)
            for (index in 0..2) {
                val before = samples[index]
                val after = samples[index + 1]
                val change = before.regions[region].bodyRgb.zip(after.regions[region].bodyRgb)
                    .sumOf { (a, b) -> abs(a - b) } / 3.0
                assertTrue("${before.percent}/${after.percent}% must render distinct panel body pixels in region $region; change=$change distances=$distances",
                    change >= 3.0)
                assertTrue("less opacity must expose more backdrop in panel body region $region: $distances",
                    distances[index] - distances[index + 1] >= 3.0)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun backdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFFB5CADB))
        val stripe = size.width / 12
        repeat(12) { index ->
            drawRect(if (index % 2 == 0) Color(0xFFDA853B) else Color(0xFF183346),
                topLeft = Offset(index * stripe, size.height / 3),
                size = androidx.compose.ui.geometry.Size(stripe, size.height * 2 / 3))
        }
    }
}

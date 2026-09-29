package com.papi.nova.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaComposeTheme
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaFormFactor
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaFocusReturn
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageContent
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelPlacement
import com.papi.nova.ui.panel.NovaPanelPlacementKey
import com.papi.nova.ui.panel.NovaPanelSide
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaScrim
import com.papi.nova.ui.panel.NovaShoulder
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.NovaSurfaces
import com.papi.nova.ui.panel.NovaSurfacesLayer
import com.papi.nova.utils.UiHelper
import java.io.File
import kotlin.math.abs

internal typealias NovaVisualRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** Thrown by a surface that does not apply to this display, such as the landscape strip in portrait. */
internal class NovaVisualSkip(reason: String) : RuntimeException(reason)

/** Runs [command] as the shell and returns what it printed. */
internal fun novaShell(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
}

/**
 * The stage one surface of the visual gate is opened on: a ComponentActivity dressed as Nova's
 * screens are (edge to edge, the bars as the device keeps them, the content inset as
 * UiHelper.notifyNewRootView insets it), Nova's panel window over it for panels and state pages,
 * and injected keys, so focus moves and splits arm the way a pad's presses do.
 *
 * The clock is the test's: animations run only when [settle] advances it, so a surface is read
 * and shot at rest, and a spinner cannot keep the walk from ever going idle.
 */
internal class NovaVisualStage(val rule: NovaVisualRule) {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val arguments: Bundle = InstrumentationRegistry.getArguments()
    val activity: ComponentActivity get() = rule.activity

    /**
     * `-e novaFormFactor tv` draws surfaces in the activity for a television, for an emulator only
     * sized as one. The panel window takes the device's own form factor, so a real television
     * image is still the one that proves those.
     */
    val forcedTelevision: Boolean = arguments.getString("novaFormFactor") == "tv"
    val television: Boolean get() = forcedTelevision || UiHelper.isTvDevice(activity)

    private var windowSurfaces: NovaSurfaces? = null

    /** The activity's panel window, as every Screen placement opens it. */
    val surfaces: NovaSurfaces
        get() = windowSurfaces ?: NovaSurfaces.of(activity).also { windowSurfaces = it }

    private val cleanups = mutableListOf<() -> Unit>()

    /** Another activity a surface opened, such as one on a second display, to close with the stage. */
    val otherActivities = mutableListOf<ActivityScenario<*>>()

    /** Screenshots a surface took itself, such as a second display's, keyed by a name suffix. */
    val extraShots = mutableMapOf<String, Bitmap>()

    fun string(id: Int, vararg args: Any): String = activity.getString(id, *args)

    val landscape: Boolean
        get() = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    fun onClose(cleanup: () -> Unit) {
        cleanups += cleanup
    }

    /**
     * The test clock stops, the window leaves touch mode as a pad's first press does, and the window is
     * dressed. `-e novaHideBars true` sets Hide System Bars first, as a handheld's first run seeds it
     * (the RP6 hides them by default; an emulator stands in for it without a built-in pad, so its own
     * default would show them), and `false` shows them.
     */
    fun prepare() {
        rule.mainClock.autoAdvance = false
        instrumentation.setInTouchMode(false)
        arguments.getString("novaHideBars")?.toBooleanStrictOrNull()?.let { hidden ->
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity).edit()
                .putBoolean(NovaSystemBars.KEY_HIDE_SYSTEM_BARS, hidden)
                .commit()
        }
        rule.runOnUiThread { dressWindow(activity.window) }
    }

    private fun dressWindow(window: Window) {
        // As NovaThemeManager dresses every Nova screen: edge to edge, transparent bars, and the
        // cutout filled by backdrops, with controls kept clear of it by the insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The window behind the bars is the screen's surface, so an inset screen shows it there too.
        // Nova's surface for the chosen theme: this activity wears the test's light theme, whose
        // colorBackground is what getActivityWindowSurfaceColor would read.
        window.decorView.setBackgroundColor(NovaThemeManager.getWindowBackgroundColor(activity))
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Marked as NovaThemeManager marks every Nova screen, so its panel window adopts the same
        // bars: unmarked, the panel window brought the status bar back over a screen that hid it.
        NovaSystemBars.markManaged(activity)
        NovaSystemBars.apply(activity)
    }

    /**
     * Draws [content] as a Nova screen draws it: under the theme, on the window colour. With
     * [inset], the content is inset as UiHelper.notifyNewRootView insets a screen's root; the
     * stream is not, so the Command Center's stand-in passes false, and neither is the game detail,
     * which never calls it and whose Play Setup panel keeps clear of the bars itself.
     */
    fun screen(inset: Boolean = true, content: @Composable () -> Unit) {
        rule.setContent {
            NovaVisualTheme(forcedTelevision) {
                Box(Modifier.fillMaxSize().background(LocalNovaComposeColors.current.window)) { content() }
            }
        }
        if (inset) {
            rule.runOnUiThread {
                UiHelper.notifyNewRootView(activity)
                activity.window.decorView.requestApplyInsets()
            }
        }
        settle()
    }

    /** A screen with nothing on it to read: a stand-in for the library grid behind a panel. */
    fun backdrop() = screen { NovaVisualBackdrop(stream = false) }

    /**
     * The Command Center's stand-in: [panel] drawn by the same layer the panel window draws, over
     * a stand-in for the stream, with the stream's scrim. Stream placement needs a Game with a
     * live stream, so the layer sits in the activity; it has no backdrop blur over the stream in
     * the real window either.
     */
    fun streamPanel(panel: NovaPanelState, hints: List<NovaControllerHint> = emptyList(), content: NovaPageContent) {
        screen(inset = false) {
            NovaVisualBackdrop(stream = true)
            NovaSurfacesLayer(
                panel = panel,
                states = emptyList(),
                scrim = NovaScrim.Stream,
                pageContent = content,
                onIdle = {},
                hints = hints,
            )
        }
    }

    /** Opens [root] in the activity's panel window, as the screens do. */
    fun openPanel(
        root: NovaPage,
        edge: NovaEdge = NovaEdge.End,
        hints: List<NovaControllerHint> = emptyList(),
        onShoulder: ((NovaShoulder) -> Unit)? = null,
        content: NovaPageContent = {},
    ) {
        rule.runOnUiThread { surfaces.open(root, edge, NovaFocusReturn.None, hints, onShoulder, content) }
        windowShown()
    }

    /** Pushes [page] onto [panel], or onto the panel window's panel. */
    fun push(page: NovaPage, panel: NovaPanelState = surfaces.panel) {
        rule.runOnUiThread { panel.push(page) }
        settle()
    }

    /** Shows [page] in the panel window, above everything. */
    fun showState(page: NovaStatePage) {
        rule.runOnUiThread { surfaces.show(page) }
        windowShown()
    }

    private fun windowShown() {
        settle()
        // The window arrives and takes input focus on the real clock.
        Thread.sleep(WindowSettleMillis)
        instrumentation.waitForIdleSync()
        settle()
    }

    /** Presses each key in turn, as a pad does: down and up, then a few frames. */
    fun press(vararg keyCodes: Int) {
        keyCodes.forEach { code ->
            instrumentation.sendKeyDownUpSync(code)
            frames(6)
        }
        settle(ShortSettleMillis)
    }

    /** Moves focus to the first node [matcher] finds, as a pad's moves would have. */
    fun focus(matcher: SemanticsMatcher) {
        rule.onAllNodes(matcher).onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        settle(ShortSettleMillis)
    }

    fun isFocused(matcher: SemanticsMatcher): Boolean =
        rule.onAllNodes(matcher and SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    /** Runs the test clock [millis] forward, so motion lands and the surface is at rest. */
    fun settle(millis: Long = SettleMillis) {
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }

    fun frames(count: Int) {
        repeat(count) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    /** The whole display as a person sees it, once the last frame has been drawn on the real clock. */
    fun screenshot(file: File): Boolean {
        Thread.sleep(DrawSettleMillis)
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return false
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return true
    }

    /** Holds the surface on screen a while on request (`-e novaHoldSeconds 20`), for a person to look. */
    fun hold() {
        arguments.getString("novaHoldSeconds")?.toLongOrNull()?.let { Thread.sleep(it * 1_000) }
    }

    fun close() {
        cleanups.asReversed().forEach { runCatching(it) }
        cleanups.clear()
        runCatching { rule.runOnUiThread { windowSurfaces?.dispose() } }
        otherActivities.forEach { runCatching { it.close() } }
        otherActivities.clear()
    }

    companion object {
        const val SettleMillis = 1_500L
        const val ShortSettleMillis = 600L
        const val WindowSettleMillis = 500L
        const val DrawSettleMillis = 700L
    }
}

/** Nova's theme, for a television when [television] asks for one. */
@Composable
internal fun NovaVisualTheme(television: Boolean, content: @Composable () -> Unit) {
    NovaComposeTheme {
        if (television) {
            CompositionLocalProvider(LocalNovaFormFactor provides NovaFormFactor.Television, content = content)
        } else {
            content()
        }
    }
}

/**
 * What lies behind a panel: rows of poster shapes for the library, or a lit frame for the stream.
 * It is drawn, not composed of nodes, so it adds nothing for the walk to read.
 */
@Composable
internal fun NovaVisualBackdrop(stream: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        if (stream) {
            drawRect(Brush.linearGradient(listOf(ComposeColor(0xFF1B2A41), ComposeColor(0xFF8A4B2A), ComposeColor(0xFF203B2B))))
            drawCircle(ComposeColor(0x66FFD27A), radius = size.minDimension / 3f, center = Offset(size.width * 0.7f, size.height * 0.35f))
            return@Canvas
        }
        drawRect(ComposeColor(0xFF10131A))
        val poster = Size(size.width / 7f, size.width / 7f * 1.5f)
        val gap = poster.width / 8f
        var y = gap * 3
        var row = 0
        while (y < size.height) {
            var x = gap
            var column = 0
            while (x < size.width) {
                val hue = ((row * 7 + column * 3) % 12) / 12f
                drawRoundRect(
                    color = ComposeColor.hsv(hue * 360f, 0.35f, 0.42f),
                    topLeft = Offset(x, y),
                    size = poster,
                    cornerRadius = CornerRadius(gap),
                )
                x += poster.width + gap
                column++
            }
            y += poster.height + gap
            row++
        }
    }
}

/** What the walk found on one surface: failures fail it; notes are for the reviewers. */
internal class NovaVisualFindings {
    val failures = mutableListOf<String>()
    val notes = mutableListOf<String>()
    var panels = 0
    var screenshot = false
}

/**
 * Walks every window of the process at rest, for spec 9.4:
 *
 * - every Compose text, through its own layout result: overflowing its box, cut to a count of
 *   lines, ellipsized, or broken inside a word fails (R13); so does every View text the same way;
 * - every node a person reads or acts on, against the bounds of its ancestors and the window: one
 *   cut by a container, by the window, under a status bar, navigation bar or cutout, or outside a
 *   television's title-safe area fails; one cut at the side of a strip that scrolls sideways fails
 *   (a strip ending mid-item); one cut at the top or bottom of a scrolling list fails within one
 *   row of the focused row, and past that is a note for the reviewers, since a list longer than
 *   its panel must end somewhere (`-e novaStrict true` fails those too);
 * - every panel, by the placement its frame publishes: its outer edge on the window's edge at full
 *   height, or a full-width bottom sheet in portrait, rounded with the drawer radius on its inner
 *   edge only (R6), and no more panels on screen than the surface opens.
 */
internal class NovaVisualInspector(private val stage: NovaVisualStage) {
    private val density = stage.activity.resources.displayMetrics.density
    private val strict = stage.arguments.getString("novaStrict") == "true"
    private val findings = NovaVisualFindings()

    fun inspect(expectedPanels: Int): NovaVisualFindings {
        stage.rule.waitForIdle()
        stage.rule.runOnUiThread {
            windowRoots().forEach { window ->
                composeRoots(window).forEach { ComposeWalk(it).run() }
                ViewWalk(window).run()
            }
        }
        when {
            findings.panels > 1 -> fail("${findings.panels} panels are on screen at once; only one may show (R13)")
            findings.panels < expectedPanels -> fail("no panel is on screen where this surface opens one (R6)")
            findings.panels > expectedPanels -> fail("a panel is on screen where this surface opens none (R13)")
        }
        return findings
    }

    private fun fail(what: String) {
        if (what !in findings.failures) findings.failures += what
    }

    private fun note(what: String) {
        if (strict) fail(what) else if (what !in findings.notes) findings.notes += what
    }

    /** Every window of this process on screen, whatever its display, except a finishing activity's. */
    private fun windowRoots(): List<View> {
        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WindowInspector.getGlobalWindowViews()
        } else {
            listOf(stage.activity.window.decorView)
        }
        return views.filter { view ->
            val activity = view.context.findActivity()
            view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0 &&
                (activity == null || !activity.isFinishing)
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext?.findActivity()
        else -> null
    }

    /** The Compose roots in [view]'s tree, including one inside a View inside another, as the deck's End tile is. */
    private fun composeRoots(view: View): List<ViewRootForTest> {
        val found = mutableListOf<ViewRootForTest>()
        fun visit(child: View) {
            if (!child.isShown) return
            if (child is ViewRootForTest) found += child
            if (child is ViewGroup) for (index in 0 until child.childCount) visit(child.getChildAt(index))
        }
        visit(view)
        return found
    }

    private enum class Axis { Vertical, Horizontal }

    private inner class ComposeWalk(host: ViewRootForTest) {
        private val view = host.view
        private val root = host.semanticsOwner.unmergedRootSemanticsNode

        /** The root's own area: the whole panel window, or a screen's inset content. */
        private val area = rectOf(root)
        private val window = Rect(0f, 0f, view.rootView.width.toFloat(), view.rootView.height.toFloat())
        private val bars = barBands(view)
        private val titleSafe = if (stage.television && !stage.forcedTelevision) {
            Rect(
                NovaPanelMetrics.TvSafeHorizontal.value * density,
                NovaPanelMetrics.TvSafeVertical.value * density,
                window.width - NovaPanelMetrics.TvSafeHorizontal.value * density,
                window.height - NovaPanelMetrics.TvSafeVertical.value * density,
            )
        } else {
            null
        }
        private val focused = find(root) { it.config.getOrNull(SemanticsProperties.Focused) == true }
        private val contextPx = run {
            val formFactor = if (stage.television) NovaFormFactor.Television else NovaFormFactor.Handheld
            val panels = NovaPanelMetrics.density(formFactor, stage.activity.resources.configuration.screenHeightDp.dp)
            (NovaPanelMetrics.rowMinHeight(formFactor, panels) + NovaPanelMetrics.rowGap(panels)).value * density
        }

        fun run() = visit(root, reported = false)

        private fun visit(node: SemanticsNode, reported: Boolean) {
            if (!node.layoutInfo.isPlaced) return
            node.config.getOrNull(NovaPanelPlacementKey)?.let { placement(node, it) }
            var cutAbove = reported
            if (node.isMeaningful()) {
                if (!reported && cut(node)) cutAbove = true
                text(node)
                screenEdges(node)
            }
            node.children.forEach { visit(it, cutAbove) }
        }

        /** Reports [node] when something cuts it, and returns whether it did, so its insides are not reported again. */
        private fun cut(node: SemanticsNode): Boolean {
            val whole = rectOf(node)
            if (whole.width < 1f || whole.height < 1f) return false
            val shown = node.boundsInWindow
            val hidden = shown.width < 1f || shown.height < 1f
            val vertical = hidden || shown.top - whole.top > Slack || whole.bottom - shown.bottom > Slack
            val horizontal = hidden || shown.left - whole.left > Slack || whole.right - shown.right > Slack
            if (!vertical && !horizontal) return false
            val name = describe(node)
            val seen = if (hidden) "hidden, laid at ${whole.short()}" else "shown ${shown.short()} of ${whole.short()}"
            val clipper = generateSequence(node.parent) { it.parent }.firstOrNull { !rectOf(it).encloses(whole) }
            val axis = clipper?.scrollAxis()
            when {
                axis != null && hidden -> return false
                axis == Axis.Horizontal && horizontal ->
                    fail("$name is cut at the side of a strip that scrolls sideways: the strip ends mid-item (R13): $seen")
                axis == Axis.Vertical && !horizontal -> {
                    val focus = focused
                    if (focus != null && focus.isInside(clipper)) {
                        val band = rectOf(focus).let { Rect(it.left, it.top - contextPx, it.right, it.bottom + contextPx) }
                        if (whole.top < band.bottom && whole.bottom > band.top) {
                            fail("$name is cut at the edge of its list within one row of the focused row (R13): $seen")
                        } else {
                            note("$name is cut at the edge of a scrolling list, past the focused row and one row of context: $seen")
                        }
                    } else {
                        note("$name is cut at the edge of a scrolling list that holds no focus: $seen")
                    }
                }
                clipper == null -> fail("$name is cut by a layout inside ${node.parent?.let(::describe) ?: "its root"} (R13): $seen")
                clipper.id == root.id -> fail("$name is cut by the edge of the window (R13): $seen")
                else -> fail("$name is cut by ${describe(clipper)} (R13): $seen")
            }
            return true
        }

        private fun text(node: SemanticsNode) {
            if (node.config.contains(SemanticsProperties.EditableText)) return
            val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: return
            if (words.isBlank()) return
            val layout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return
            val results = mutableListOf<TextLayoutResult>()
            layout(results)
            val name = "\"${words.take(90)}\""
            results.forEach { result ->
                val ellipsized = (0 until result.lineCount).any { result.isLineEllipsized(it) }
                when {
                    ellipsized -> fail("$name is ellipsized (R13)")
                    result.multiParagraph.didExceedMaxLines -> fail("$name is cut to ${result.lineCount} line(s) (R13)")
                    // Not hasVisualOverflow: the layout semantics hand back is laid at the width the
                    // text was offered, not the width it took, so that flag reads true for every line
                    // narrower than its row. The lines break where the text did, so a line wider than
                    // the box, or lines taller than it, is the overflow a person sees.
                    overflows(result) -> fail(
                        "$name overflows its box (R13): lines ${widest(result).toInt()}x" +
                            "${result.multiParagraph.height.toInt()} in ${result.size.width}x${result.size.height}",
                    )
                }
                val laid = result.layoutInput.text.text
                for (line in 0 until result.lineCount - 1) {
                    val end = result.getLineEnd(line)
                    if (end in 1 until laid.length && laid[end - 1].isWordLetter() && laid[end].isWordLetter()) {
                        fail("$name breaks inside a word after line ${line + 1} (R13)")
                        break
                    }
                }
            }
        }

        /**
         * The widest line that ends on what it shows. A line wrapped at a space counts that space in
         * its width, so it can read a few pixels past the box it was broken to fit; it fits by
         * construction, and is left out.
         */
        private fun widest(result: TextLayoutResult): Float =
            (0 until result.lineCount)
                .filter { result.getLineEnd(it, visibleEnd = true) == result.getLineEnd(it) }
                .maxOfOrNull { result.multiParagraph.getLineWidth(it) } ?: 0f

        private fun overflows(result: TextLayoutResult): Boolean =
            widest(result) > result.size.width + Slack || result.multiParagraph.height > result.size.height + Slack

        /** A node a person reads or acts on, under the status bar, a cutout, or outside a television's safe area. */
        private fun screenEdges(node: SemanticsNode) {
            val shown = node.boundsInWindow
            if (shown.width < 1f || shown.height < 1f) return
            val whole = rectOf(node)
            // A scrim, or a surface that covers the whole window, is meant to reach under the bars.
            if (whole.width >= window.width - Slack && whole.height >= window.height - Slack) return
            val name = describe(node)
            bars.firstOrNull { (_, band) -> overlap(shown, band) > Slack }?.let { (where, band) ->
                fail("$name is drawn under $where (R13): ${shown.short()} against ${band.short()}")
            }
            titleSafe?.let { safe ->
                if (!safe.encloses(shown)) fail("$name is outside the television's title-safe area (R13): ${shown.short()}")
            }
        }

        private fun placement(node: SemanticsNode, placement: NovaPanelPlacement) {
            findings.panels++
            val rect = rectOf(node)
            val side = placement.side
            val name = "the panel attached to the ${side.name.lowercase()}"
            fun near(a: Float, b: Float) = abs(a - b) <= Slack
            val attached = when (side) {
                NovaPanelSide.Left -> near(rect.left, area.left)
                NovaPanelSide.Right -> near(rect.right, area.right)
                NovaPanelSide.Bottom -> near(rect.bottom, area.bottom) && near(rect.left, area.left) && near(rect.right, area.right)
            }
            if (!attached) fail("$name floats: its outer edge is off the window's edge (R6): ${rect.short()} in ${area.short()}")
            if (side != NovaPanelSide.Bottom && !(near(rect.top, area.top) && near(rect.bottom, area.bottom))) {
                fail("$name is not full height (R6): ${rect.short()} in ${area.short()}")
            }
            if (side == NovaPanelSide.Bottom && rect.height > area.height * NovaPanelMetrics.SheetMaxHeightFraction + Slack) {
                fail("$name is taller than ${(NovaPanelMetrics.SheetMaxHeightFraction * 100).toInt()}% of the window (R6)")
            }
            val shape = placement.shape as? CornerBasedShape
            if (shape == null) {
                fail("$name is cut to a shape the gate cannot read: ${placement.shape}")
                return
            }
            val size = node.size.toSize()
            val corners = Density(density)
            val ltr = view.layoutDirection != View.LAYOUT_DIRECTION_RTL
            val starts = listOf(shape.topStart.toPx(size, corners), shape.bottomStart.toPx(size, corners))
            val ends = listOf(shape.topEnd.toPx(size, corners), shape.bottomEnd.toPx(size, corners))
            val left = if (ltr) starts else ends
            val right = if (ltr) ends else starts
            val drawer = NovaRadius.drawer.value * density
            val (wantLeft, wantRight) = when (side) {
                NovaPanelSide.Left -> listOf(0f, 0f) to listOf(drawer, drawer)
                NovaPanelSide.Right -> listOf(drawer, drawer) to listOf(0f, 0f)
                NovaPanelSide.Bottom -> listOf(drawer, 0f) to listOf(drawer, 0f)
            }
            fun same(a: List<Float>, b: List<Float>) = a.zip(b).all { (x, y) -> abs(x - y) <= 0.5f }
            if (!same(left, wantLeft) || !same(right, wantRight)) {
                fail(
                    "$name is rounded on the wrong corners (R6): left top and bottom $left, right top and bottom $right; " +
                        "wanted $wantLeft and $wantRight",
                )
            }
        }

        private fun find(node: SemanticsNode, predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
            if (node.layoutInfo.isPlaced && predicate(node)) return node
            node.children.forEach { child -> find(child, predicate)?.let { return it } }
            return null
        }

        private fun SemanticsNode.isInside(ancestor: SemanticsNode?): Boolean =
            ancestor != null && generateSequence(this) { it.parent }.any { it.id == ancestor.id }

        private fun SemanticsNode.scrollAxis(): Axis? = when {
            config.contains(SemanticsProperties.VerticalScrollAxisRange) -> Axis.Vertical
            config.contains(SemanticsProperties.HorizontalScrollAxisRange) -> Axis.Horizontal
            else -> null
        }

        private fun SemanticsNode.isMeaningful(): Boolean =
            config.contains(SemanticsProperties.Text) || config.contains(SemanticsProperties.EditableText) ||
                config.contains(SemanticsProperties.ContentDescription) || config.contains(SemanticsActions.OnClick) ||
                config.contains(SemanticsProperties.Focused)

        private fun describe(node: SemanticsNode): String {
            val config = node.config
            config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }
                ?.let { return "\"${it.take(80)}\"" }
            config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotBlank() }
                ?.let { return "[${it.take(80)}]" }
            config.getOrNull(SemanticsProperties.PaneTitle)?.let { return "the page \"$it\"" }
            find(node) { !it.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() }
                ?.let { inner -> return "the element with \"${inner.config[SemanticsProperties.Text].joinToString(" ") { it.text }.take(80)}\"" }
            config.getOrNull(SemanticsProperties.TestTag)?.let { return "#$it" }
            return when {
                node.scrollAxis() != null -> "its scrolling list"
                config.contains(NovaPanelPlacementKey) -> "its panel"
                else -> "an unlabelled container"
            }
        }
    }

    /** The status bar, a tappable navigation bar and any cutout, as bands of [view]'s window. */
    private fun barBands(view: View): List<Pair<String, Rect>> {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return emptyList()
        // Tappable insets are reported as if the bars showed even while Hide System Bars has taken
        // them away, so they count only while a bar is on screen.
        val barsShown = insets.isVisible(WindowInsetsCompat.Type.statusBars()) ||
            insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        val types = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout() or
            if (barsShown) WindowInsetsCompat.Type.tappableElement() else 0
        val bands = insets.getInsets(types)
        val width = view.rootView.width.toFloat()
        val height = view.rootView.height.toFloat()
        return buildList {
            if (bands.top > 0) add("the status bar or a cutout" to Rect(0f, 0f, width, bands.top.toFloat()))
            if (bands.bottom > 0) add("the navigation bar" to Rect(0f, height - bands.bottom, width, height))
            if (bands.left > 0) add("a system bar or cutout on the left" to Rect(0f, 0f, bands.left.toFloat(), height))
            if (bands.right > 0) add("a system bar or cutout on the right" to Rect(width - bands.right, 0f, width, height))
        }
    }

    /** View screens, such as the companion deck: their TextViews, walked the same way. */
    private inner class ViewWalk(private val root: View) {
        fun run() = visit(root, null)

        private fun visit(view: View, scroll: Axis?) {
            if (!view.isShown || view.width == 0 || view.height == 0) return
            if (view is TextView && view !is EditText && !view.text.isNullOrBlank()) textView(view, scroll)
            if (view is ViewGroup) {
                val own = when {
                    view.canScrollHorizontally(1) || view.canScrollHorizontally(-1) -> Axis.Horizontal
                    view.canScrollVertically(1) || view.canScrollVertically(-1) -> Axis.Vertical
                    else -> null
                }
                for (index in 0 until view.childCount) visit(view.getChildAt(index), own ?: scroll)
            }
        }

        private fun textView(view: TextView, scroll: Axis?) {
            val text = view.text.toString()
            val name = "\"${text.take(80)}\""
            val layout = view.layout ?: return
            if ((0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }) fail("$name is ellipsized (R13)")
            val room = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
            if (layout.height > room + Slack) fail("$name is taller than its box, so lines are cut (R13)")
            val roomWide = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
            val scrollsSideways = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && view.isHorizontallyScrollable
            if (!scrollsSideways && (0 until layout.lineCount).any { layout.getLineWidth(it) > roomWide + Slack }) {
                fail("$name is wider than its box (R13)")
            }
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                if (end in 1 until text.length && text[end - 1].isWordLetter() && text[end].isWordLetter()) {
                    fail("$name breaks inside a word after line ${line + 1} (R13)")
                    break
                }
            }
            val visible = android.graphics.Rect()
            if (!view.getGlobalVisibleRect(visible)) return
            val cut = visible.width() < view.width - Slack || visible.height() < view.height - Slack
            if (!cut) return
            val seen = "shown ${visible.width()}x${visible.height()} of ${view.width}x${view.height}"
            when (scroll) {
                Axis.Horizontal -> fail("$name is cut at the side of a strip that scrolls sideways (R13): $seen")
                Axis.Vertical -> note("$name is cut at the edge of a scrolling list: $seen")
                null -> fail("$name is cut by its container (R13): $seen")
            }
        }
    }

    private companion object {
        /** Rounding between layout and pixels: a cut smaller than this is none. */
        const val Slack = 1.5f

        /** Scripts whose words are separated by spaces, where a line breaking between two letters broke a word. */
        val WordScripts = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK)

        fun Char.isWordLetter(): Boolean = isDigit() || (isLetter() && Character.UnicodeScript.of(code) in WordScripts)

        fun rectOf(node: SemanticsNode): Rect = Rect(node.positionInWindow, node.size.toSize())

        fun Rect.encloses(other: Rect): Boolean =
            other.left >= left - Slack && other.top >= top - Slack && other.right <= right + Slack && other.bottom <= bottom + Slack

        fun overlap(a: Rect, b: Rect): Float {
            val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            return if (width > 0f && height > 0f) minOf(width, height) else 0f
        }

        fun Rect.short(): String = "(${left.toInt()}, ${top.toInt()}, ${right.toInt()}, ${bottom.toInt()})"
    }
}

/**
 * Where a run's screenshots and results land: the app's external files under nova-visual/<run>,
 * mirrored to /sdcard/Download/nova-visual/<run> for a host to pull. results.txt holds one line
 * per surface, PASS, FAIL or SKIP, written as each surface finishes, and <surface>.txt its
 * findings, so one surface failing hides nothing about the others.
 */
internal class NovaVisualOutput private constructor(val run: String, val local: File, val shared: String) {
    fun file(name: String): File = File(local, name)

    fun record(surface: NovaVisualSurface, findings: NovaVisualFindings, skipped: String?) {
        val verdict = when {
            skipped != null -> "SKIP"
            findings.failures.isEmpty() -> "PASS"
            else -> "FAIL"
        }
        val line = buildString {
            append(verdict).append(' ').append(surface.name)
            when {
                skipped != null -> append(": ").append(skipped)
                else -> append(": ${findings.failures.size} failure(s), ${findings.notes.size} note(s) for review")
            }
            if (skipped == null && !findings.screenshot) append(", no screenshot")
        }
        val details = buildString {
            appendLine("surface: ${surface.name}")
            appendLine("shows: ${surface.about}")
            appendLine("verdict: $verdict")
            skipped?.let { appendLine("skipped: $it") }
            appendLine("screenshot: ${if (findings.screenshot) "${surface.name}.png" else "none"}")
            appendLine("panels on screen: ${findings.panels}")
            appendLine("failures:")
            findings.failures.ifEmpty { listOf("none") }.forEach { appendLine("  - $it") }
            appendLine("notes for the reviewers:")
            findings.notes.ifEmpty { listOf("none") }.forEach { appendLine("  - $it") }
        }
        file("${surface.name}.txt").writeText(details)
        file("results.txt").appendText(line + "\n")
        mirror("${surface.name}.txt", "${surface.name}.png", "results.txt")
    }

    private fun mirror(vararg names: String) {
        novaShell("mkdir -p $shared")
        names.map(::file).filter { it.exists() }.forEach { novaShell("cp ${it.absolutePath} $shared/${it.name}") }
    }

    companion object {
        private var instance: NovaVisualOutput? = null

        val current: NovaVisualOutput
            get() = instance ?: create().also { instance = it }

        private fun create(): NovaVisualOutput {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val run = (InstrumentationRegistry.getArguments().getString("novaVisualTag") ?: defaultTag(context))
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val local = File(context.getExternalFilesDir(null), "nova-visual/$run").apply { mkdirs() }
            return NovaVisualOutput(run, local, "/sdcard/Download/nova-visual/$run")
        }

        /** The device (an emulator's AVD name), its size in dp and its font scale. */
        private fun defaultTag(context: Context): String {
            val device = novaShell("getprop ro.boot.qemu.avd_name").trim().ifEmpty { Build.MODEL }
            val configuration = context.resources.configuration
            val metrics = context.resources.displayMetrics
            val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                metrics.widthPixels to metrics.heightPixels
            }
            val dp = { px: Int -> (px / metrics.density).toInt() }
            return "$device-${dp(width)}x${dp(height)}dp-fs${configuration.fontScale}"
        }

        /**
         * Starts the run: a walk of every surface starts its results afresh, and a walk of some
         * (`-e novaSurfaces`) adds to them. The first lines say what the run was on.
         */
        fun start(whole: Boolean) {
            val output = current
            if (whole) {
                output.local.listFiles()?.forEach { it.delete() }
                novaShell("rm -rf ${output.shared}")
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val configuration = context.resources.configuration
            val metrics = context.resources.displayMetrics
            val header = buildString {
                appendLine("# Nova visual gate, run ${output.run}")
                appendLine(
                    "# ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, ${metrics.widthPixels}x${metrics.heightPixels} px " +
                        "at ${metrics.densityDpi} dpi (${configuration.screenWidthDp}x${configuration.screenHeightDp} dp), " +
                        "font scale ${configuration.fontScale}, television ${UiHelper.isTvDevice(context)}",
                )
            }
            output.file("results.txt").appendText(header)
            output.mirror("results.txt")
        }
    }
}

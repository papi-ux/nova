package com.papi.nova.ui

import android.app.Activity
import androidx.activity.ComponentActivity
import android.content.res.Configuration
import android.os.Looper
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaCompanionCommandDeckViewTest {
    @Test
    fun initialFocusTargetsFirstSafeActionAndNeverEndSession() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val deck = NovaCompanionCommandDeckView(activity) { }
        activity.setContentView(deck)

        deck.render(state())
        shadowOf(activity.mainLooper).idle()

        val androidKeyboard = requireAction(activity, deck, R.string.companion_deck_android_keyboard)
        val endSession = requireEndTile(deck)
        assertTrue(androidKeyboard.hasFocus())
        assertFalse(endSession.hasFocus())

        endSession.requestFocus()
        assertTrue(endSession.hasFocus())
        deck.restoreSafeActionFocus()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(androidKeyboard.hasFocus())
        assertFalse(endSession.hasFocus())
    }

    @Test
    fun selectedActionsExposeVisibleAndAccessibilityState() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val deck = NovaCompanionCommandDeckView(activity) { }
        activity.setContentView(deck)

        deck.render(
            state().withActionSelections(
                androidKeyboardVisible = true,
                novaKeyboardVisible = false,
                novaHudVisible = true,
                zoomPanEnabled = true,
            ),
        )
        shadowOf(activity.mainLooper).idle()

        val androidKeyboard = requireAction(activity, deck, R.string.companion_deck_android_keyboard)
        val novaKeyboard = requireAction(activity, deck, R.string.companion_deck_nova_keyboard)
        val hud = requireAction(activity, deck, R.string.companion_deck_nova_hud)
        assertTrue(androidKeyboard.isSelected)
        assertEquals("Active", ViewCompat.getStateDescription(androidKeyboard))
        assertFalse(novaKeyboard.isSelected)
        assertEquals("Inactive", ViewCompat.getStateDescription(novaKeyboard))
        assertTrue(hud.isSelected)

        // R9: an on tile carries the one check, and a fill or a border only ever means focus.
        fun mark(tile: View): View =
            requireNotNull(tile.findViewWithTag(NovaCompanionCommandDeckView.TILE_CURRENT_MARK_TAG))
        assertEquals(View.VISIBLE, mark(androidKeyboard).visibility)
        assertEquals(View.VISIBLE, mark(hud).visibility)
        assertEquals("an off tile keeps the mark's room but shows nothing", View.INVISIBLE, mark(novaKeyboard).visibility)
        val background = hud.background as StateListDrawable
        background.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected)
        val selectedLook = background.current
        background.state = intArrayOf(android.R.attr.state_enabled)
        assertSame("an on tile draws the resting fill and hairline, not the accent", background.current, selectedLook)
        background.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
        assertNotSame("focus still fills the tile", background.current, selectedLook)

        // Turning the HUD off takes its mark away without rebuilding the rail.
        deck.render(
            state().withActionSelections(
                androidKeyboardVisible = true,
                novaKeyboardVisible = false,
                novaHudVisible = false,
                zoomPanEnabled = true,
            ),
        )
        shadowOf(activity.mainLooper).idle()
        assertFalse(hud.isSelected)
        assertEquals(View.INVISIBLE, mark(hud).visibility)
    }

    @Test
    fun everyBuiltInPaletteRendersBoundedChromeAndSemanticDestructiveAction() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val originalTheme = NovaThemeManager.getTheme(activity)
        val themes = listOf(
            NovaThemeManager.THEME_POLARIS,
            NovaThemeManager.THEME_PORTABLE_CHROME,
            NovaThemeManager.THEME_OLED,
            NovaThemeManager.THEME_MIAMI,
            NovaThemeManager.THEME_HIGH_CONTRAST,
            NovaThemeManager.THEME_MATERIAL_YOU,
        )
        try {
            themes.forEach { theme ->
                NovaThemeManager.setTheme(activity, theme)
                val deck = NovaCompanionCommandDeckView(activity) { }
                // The End Session tile is a ComposeView, which composes only once it is attached.
                activity.setContentView(deck)
                deck.render(state())
                deck.measure(
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                )
                deck.layout(0, 0, 800, 400)
                assertEquals(800, deck.getChildAt(0).measuredWidth)
                assertEquals(800, deck.getChildAt(1).measuredWidth)
                assertTrue(requireEndTile(deck).isAttachedToWindow)

                val window = NovaThemeManager.getWindowBackgroundColor(activity)
                val card = ColorUtils.compositeColors(NovaThemeManager.getCardBackgroundColor(activity), window)
                val focus = ColorUtils.compositeColors(NovaThemeManager.getAccentSurfaceColor(activity), card)
                val error = NovaThemeManager.getErrorColor(activity)
                val restingContrast = ColorUtils.calculateContrast(error, card)
                val focusedContrast = ColorUtils.calculateContrast(error, focus)
                assertTrue("$theme resting contrast=$restingContrast", restingContrast >= 4.5)
                assertTrue("$theme focused contrast=$focusedContrast", focusedContrast >= 4.5)
            }
        } finally {
            NovaThemeManager.setTheme(activity, originalTheme)
        }
    }

    @Test
    fun transparentCenterPreservesUnderlyingTouchpadOwnership() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = ExternalControllerView(activity)
        val deck = NovaCompanionCommandDeckView(activity) { }
        var touchpadEvents = 0
        root.setOnTouchListener { _, _ ->
            touchpadEvents += 1
            true
        }
        root.addView(deck, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.setContentView(root)
        deck.render(state())

        val density = activity.resources.displayMetrics.density
        val width = (220 * density).toInt()
        val height = (300 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val x = width / 2f
        val y = height / 2f
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0)
        try {
            assertTrue(root.dispatchTouchEvent(down))
            assertTrue(root.dispatchTouchEvent(up))
        } finally {
            down.recycle()
            up.recycle()
        }

        assertEquals(2, touchpadEvents)
    }

    @Test
    fun theStripAndTheRailWrapSoNothingIsCutAtRest() {
        // About the size of a handheld's lower screen: every status line and every tile whole inside
        // its strip, on as many lines as they need, and neither strip scrolls.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val deck = NovaCompanionCommandDeckView(activity) { }
        activity.setContentView(deck)
        deck.render(state())

        val density = activity.resources.displayMetrics.density
        val width = (400 * density).toInt()
        val height = (480 * density).toInt()
        deck.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        deck.layout(0, 0, width, height)

        listOf(deck.getChildAt(0) as ScrollView, deck.getChildAt(1) as ScrollView).forEach { strip ->
            val lines = strip.getChildAt(0) as ViewGroup
            assertTrue("the strip holds all of its lines at rest", lines.measuredHeight <= strip.measuredHeight - strip.paddingTop - strip.paddingBottom)
            for (index in 0 until lines.childCount) {
                val item = lines.getChildAt(index)
                assertTrue("item $index starts inside the strip", item.left >= 0)
                assertTrue("item $index ends inside the strip", item.right <= lines.width)
            }
        }
        val rail = (deck.getChildAt(1) as ScrollView).getChildAt(0) as ViewGroup
        val tileRows = (0 until rail.childCount).map { rail.getChildAt(it).top }.distinct()
        assertTrue("eight tiles at their widths take more than one row here", tileRows.size > 1)
        assertEquals(8, countActions(activity, deck))
    }

    @Test
    fun theFlowStartsANewRowWhereTheNextItemWouldNotFitAndCentresIt() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val flow = NovaDeckFlowLayout(activity, gapPx = 10)
        repeat(3) { flow.addView(View(activity), ViewGroup.LayoutParams(100, 40)) }
        flow.addView(View(activity), ViewGroup.LayoutParams(400, 40))
        flow.measure(
            View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        flow.layout(0, 0, 250, flow.measuredHeight)

        val (first, second, third, wide) = (0 until 4).map(flow::getChildAt)
        assertEquals("two fit on the first row, centred", 20, first.left)
        assertEquals(first.top, second.top)
        assertEquals("the third starts a row of its own, centred", 75, third.left)
        assertEquals(50, third.top)
        assertEquals("an item wider than a row is held to the row", 250, wide.width)
        assertEquals(40 * 3 + 10 * 2, flow.measuredHeight)
    }

    @Test
    fun withCellsTheTilesShareEachRowEquallyAndAWholeRowTileStandsAlone() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val flow = NovaDeckFlowLayout(activity, gapPx = 10, cellMinWidthPx = 100)
        repeat(4) { flow.addView(View(activity), ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40)) }
        flow.addView(View(activity), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40))
        flow.measure(
            View.MeasureSpec.makeMeasureSpec(330, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        flow.layout(0, 0, 330, flow.measuredHeight)

        val cells = (0 until 4).map(flow::getChildAt)
        assertTrue("three cells of at least 100 fill a 330 row", cells.all { it.width == 103 })
        assertEquals(listOf(0, 0, 0, 50), cells.map { it.top })
        val whole = flow.getChildAt(4)
        assertEquals("a whole row tile takes the row", 330, whole.width)
        assertEquals(100, whole.top)
    }

    @Test
    fun compactTwoXFontUsesBoundedScrollableChrome() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val configuration = Configuration(activity.resources.configuration).apply { fontScale = 2f }
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        val deck = NovaCompanionCommandDeckView(activity) { }
        activity.setContentView(deck)
        deck.render(state())

        val density = activity.resources.displayMetrics.density
        val width = (220 * density).toInt()
        val height = (300 * density).toInt()
        deck.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        deck.layout(0, 0, width, height)

        // A tiny display at twice the font: the strips keep to the deck's width and to their share
        // of its height, and the rail, taller than its share, scrolls up and down rather than across.
        val statusViewport = deck.getChildAt(0) as ScrollView
        val actionViewport = deck.getChildAt(1) as ScrollView
        assertEquals(width, statusViewport.measuredWidth)
        assertEquals(width, actionViewport.measuredWidth)
        assertTrue(statusViewport.measuredHeight + actionViewport.measuredHeight < height)
        assertTrue(actionViewport.getChildAt(0).measuredHeight > actionViewport.measuredHeight)
        assertEquals(width - actionViewport.paddingLeft - actionViewport.paddingRight, actionViewport.getChildAt(0).measuredWidth)
        assertEquals(8, countActions(activity, deck))
    }

    private fun state(): NovaCompanionCommandDeckState = NovaCompanionCommandDeckState.from(
        hud = NovaHudUiState.preview(NovaHudMode.DEBUG),
        sessionState = "streaming",
        displayRole = "Companion",
        unavailableLabel = "Unavailable",
    )

    private fun requireAction(activity: Activity, root: View, label: Int): View =
        requireNotNull(findByDescription(root, activity.getString(label)))

    /** End Session is a split tile drawn in Compose, found by its tag rather than a description. */
    private fun requireEndTile(root: View): View =
        requireNotNull(root.findViewWithTag(NovaCompanionCommandDeckView.END_SESSION_TILE_TAG))

    private fun findByDescription(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findByDescription(view.getChildAt(index), description)?.let { return it }
            }
        }
        return null
    }

    private fun countActions(activity: Activity, root: View): Int = listOf(
        R.string.companion_deck_android_keyboard,
        R.string.companion_deck_nova_keyboard,
        R.string.companion_deck_quick_keys,
        R.string.companion_deck_nova_hud,
        R.string.companion_deck_zoom_pan,
        R.string.companion_deck_command_center,
        R.string.companion_deck_disconnect,
    ).count { findByDescription(root, activity.getString(it)) != null } +
        (if (root.findViewWithTag<View>(NovaCompanionCommandDeckView.END_SESSION_TILE_TAG) != null) 1 else 0)
}

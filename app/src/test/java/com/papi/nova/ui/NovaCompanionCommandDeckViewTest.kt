package com.papi.nova.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val deck = NovaCompanionCommandDeckView(activity) { }
        activity.setContentView(deck)

        deck.render(state())
        shadowOf(activity.mainLooper).idle()

        val androidKeyboard = requireAction(activity, deck, R.string.companion_deck_android_keyboard)
        val endSession = requireAction(activity, deck, R.string.companion_deck_end_session)
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
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
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
    }

    @Test
    fun everyBuiltInPaletteRendersBoundedChromeAndSemanticDestructiveAction() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
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
                deck.render(state())
                deck.measure(
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                )
                deck.layout(0, 0, 800, 400)
                assertEquals(800, deck.getChildAt(0).measuredWidth)
                assertEquals(800, deck.getChildAt(1).measuredWidth)
                assertTrue(requireAction(activity, deck, R.string.companion_deck_end_session).background != null)

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
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
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
    fun compactTwoXFontUsesBoundedScrollableChrome() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
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

        val statusViewport = deck.getChildAt(0) as HorizontalScrollView
        val actionViewport = deck.getChildAt(1) as HorizontalScrollView
        assertEquals(width, statusViewport.measuredWidth)
        assertEquals(width, actionViewport.measuredWidth)
        assertTrue(actionViewport.getChildAt(0).measuredWidth > actionViewport.measuredWidth)
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
        R.string.companion_deck_end_session,
    ).count { findByDescription(root, activity.getString(it)) != null }
}

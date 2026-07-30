package com.papi.nova.ui

import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovaCompanionCommandDeckInstrumentationTest {
    @Test
    fun compactDeckKeepsChromeBoundedAndTouchpadCenterOwned() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val baseContext = instrumentation.targetContext
            val context = baseContext.createConfigurationContext(
                Configuration(baseContext.resources.configuration).apply { fontScale = 2f },
            )
            assertEquals(2f, context.resources.configuration.fontScale, 0f)
            val root = ExternalControllerView(context)
            val deck = NovaCompanionCommandDeckView(context) { }
            var touchpadEvents = 0
            root.setOnTouchListener { _, _ ->
                touchpadEvents += 1
                true
            }
            root.addView(
                deck,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            deck.render(state())

            val density = context.resources.displayMetrics.density
            val width = (220 * density).toInt()
            val height = (300 * density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)

            val statusViewport = deck.getChildAt(0) as HorizontalScrollView
            val actionViewport = deck.getChildAt(1) as HorizontalScrollView
            assertEquals(width, statusViewport.measuredWidth)
            assertEquals(width, actionViewport.measuredWidth)
            assertTrue(actionViewport.getChildAt(0).measuredWidth > actionViewport.measuredWidth)

            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, width / 2f, height / 2f, 0)
            val up = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, width / 2f, height / 2f, 0)
            try {
                assertTrue(root.dispatchTouchEvent(down))
                assertTrue(root.dispatchTouchEvent(up))
            } finally {
                down.recycle()
                up.recycle()
            }
            assertEquals(2, touchpadEvents)
        }
    }

    @Test
    fun activeActionsExposeSelectedAndStateDescription() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val deck = NovaCompanionCommandDeckView(context) { }
            deck.render(
                state().withActionSelections(
                    androidKeyboardVisible = true,
                    novaKeyboardVisible = false,
                    novaHudVisible = true,
                    zoomPanEnabled = true,
                ),
            )

            val androidKeyboard = requireAction(deck, context.getString(R.string.companion_deck_android_keyboard))
            val novaKeyboard = requireAction(deck, context.getString(R.string.companion_deck_nova_keyboard))
            val endSession = requireAction(deck, context.getString(R.string.companion_deck_end_session))
            assertTrue(androidKeyboard.isSelected)
            assertEquals("Active", ViewCompat.getStateDescription(androidKeyboard))
            assertFalse(novaKeyboard.isSelected)
            assertEquals("Inactive", ViewCompat.getStateDescription(novaKeyboard))
            assertFalse(endSession.isSelected)
            assertEquals(null, ViewCompat.getStateDescription(endSession))
        }
    }

    private fun state(): NovaCompanionCommandDeckState = NovaCompanionCommandDeckState.from(
        hud = NovaHudUiState.preview(NovaHudMode.DEBUG),
        sessionState = "streaming",
        displayRole = "Companion",
        unavailableLabel = "Unavailable",
    )

    private fun requireAction(root: View, description: String): View =
        requireNotNull(findByDescription(root, description))

    private fun findByDescription(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findByDescription(view.getChildAt(index), description)?.let { return it }
            }
        }
        return null
    }
}

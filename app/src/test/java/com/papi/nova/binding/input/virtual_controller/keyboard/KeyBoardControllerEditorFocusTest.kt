package com.papi.nova.binding.input.virtual_controller.keyboard

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import com.papi.nova.shadows.ShadowMoonBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The on-screen keys editor lives on the stream's own window, where the stream's view holds focus
 * because that focus is how the pad reaches the host. Whatever the editor draws, it cannot take it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
class KeyBoardControllerEditorFocusTest {
    @Test
    fun theEditorNeverTakesFocusFromTheStream() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val frame = FrameLayout(activity)
        activity.setContentView(frame)
        val stream = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        frame.addView(stream)
        assertTrue(stream.requestFocus())

        val controller = KeyBoardController(null, frame, activity)
        val editor = (0 until frame.childCount).map(frame::getChildAt).filterIsInstance<ComposeView>().single()
        editor.visibility = View.VISIBLE
        // Composed, so Clear All and Add Keys are there to be focused if anything could be.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(editor.getChildAt(0) != null)

        assertFalse("the editor cannot take focus", editor.requestFocus())
        assertTrue("the stream keeps it", stream.hasFocus())
        // The Command Center's way in: nothing armed, nothing to take back, and nothing thrown.
        controller.disarmEditControls()
        assertTrue(stream.hasFocus())
    }
}

package com.papi.nova

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The closing step turned the key gate on for every Nova screen (spec section 7): A acts on release
 * and only where it was pressed, and B goes back on release, everywhere but the stream.
 */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class NovaActivityKeyGateTest {
    @Test
    fun aScreenTakesTheGateWithoutAskingForIt() {
        val controller = Robolectric.buildActivity(PlainGatedActivity::class.java).setup()
        val activity = controller.get()
        val backs = activity.countBacks()

        activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
        assertEquals("B does nothing on its way down", 0, backs())
        activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
        assertEquals("and goes back once on release", 1, backs())
        activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
        assertEquals("a release whose press this screen never saw does nothing", 1, backs())
        controller.destroy()
    }

    @Test
    fun bPutsAViewFieldsKeyboardAwayBeforeItGoesBack() {
        val controller = Robolectric.buildActivity(FieldGatedActivity::class.java).setup()
        val activity = controller.get()
        val backs = activity.countBacks()
        assertTrue(activity.field.requestFocus())

        activity.keyboardUp = true
        activity.pressB()
        assertEquals("while the keyboard is up, B only puts it away (R4)", 0, backs())

        activity.keyboardUp = false
        activity.pressB()
        assertEquals("with the keyboard down, B goes back", 1, backs())
        controller.destroy()
    }

    @Test
    fun onlyTheStreamTurnsTheGateOff() {
        val base = File("src/main/java/com/papi/nova/NovaActivity.kt").readText()
        assertTrue("the gate is on unless a screen turns it off", base.contains("protected open val novaKeyGate: Boolean = true"))
        val overrides = File("src/main/java/com/papi/nova").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "NovaActivity.kt" }
            .filter { it.readText().contains("override val novaKeyGate") }
            .map { it.name }
            .toList()
        assertEquals(
            "the stream's A and B are the host's, so Game alone turns the gate off, and no screen has to turn it on",
            listOf("Game.kt"),
            overrides,
        )
        assertTrue(File("src/main/java/com/papi/nova/Game.kt").readText().contains("override val novaKeyGate: Boolean = false"))
    }

    private fun key(action: Int, code: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, code, 0)
    }
}

private fun NovaActivity.countBacks(): () -> Int {
    var backs = 0
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            backs++
        }
    })
    return { backs }
}

private open class PlainGatedActivity : NovaActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }

    fun pressB() {
        val now = SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, 0))
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, 0))
    }
}

private class FieldGatedActivity : PlainGatedActivity() {
    lateinit var field: EditText
    var keyboardUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        field = EditText(this).apply { isFocusableInTouchMode = true }
        setContentView(field)
    }

    override fun isSoftKeyboardUp(field: View): Boolean = keyboardUp
}

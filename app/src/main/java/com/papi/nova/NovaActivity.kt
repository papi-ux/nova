package com.papi.nova

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.papi.nova.ui.NovaControllerTouchMode
import com.papi.nova.ui.NovaFontScalePreferences
import com.papi.nova.ui.panel.NovaKeyGate
import com.papi.nova.ui.panel.NovaSplitConfirmRegistry
import kotlin.math.abs

open class NovaActivity : AppCompatActivity() {
    private var appliedScalePercent = NovaFontScalePreferences.DEFAULT_SCALE_PERCENT
    private var appliedSystemFontScale = 1f
    private var recreatePosted = false

    private val fontScaleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == NovaFontScalePreferences.KEY_SCALE_PERCENT) {
            requestRecreateIfScaleChanged()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        appliedScalePercent = NovaFontScalePreferences.readScalePercent(newBase)
        appliedSystemFontScale = NovaFontScalePreferences.readSystemFontScale(newBase)
        super.attachBaseContext(
            NovaFontScalePreferences.wrapContext(
                context = newBase,
                systemFontScale = appliedSystemFontScale,
            )
        )
    }

    override fun onStart() {
        super.onStart()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(fontScaleListener)
        requestRecreateIfScaleChanged()
    }

    override fun onResume() {
        super.onResume()
        requestRecreateIfScaleChanged()
    }

    override fun onStop() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(fontScaleListener)
        super.onStop()
    }

    protected open fun shouldRecreateForFontScaleChange(): Boolean = true

    /** Whether a controller's hat press may be spent taking this screen out of touch mode. */
    protected open val hatPressLeavesTouchMode: Boolean = true

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (hatPressLeavesTouchMode && NovaControllerTouchMode.leaveTouchMode(window, event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * Every Nova screen takes the key gate ([NovaKeyGate]): A acts on release and only where it was
     * pressed, and B goes back through the dispatcher on release. Game turns it off, because its pad
     * input is the host's.
     */
    protected open val novaKeyGate: Boolean = true
    private val keyGate = NovaKeyGate()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!novaKeyGate) return super.dispatchKeyEvent(event)
        return keyGate.dispatch(event, onBack = ::backFromGate) { super.dispatchKeyEvent(it) }
    }

    /**
     * B's Back. The gate turns B into Back after the soft keyboard has passed on the key, so while
     * the keyboard is up for a View text field, B puts the keyboard away and does nothing else, as
     * the Back key does through the keyboard (R4). A Compose field answers through its own back
     * handler, which the dispatcher runs first.
     */
    private fun backFromGate() {
        // The focused view of this screen's window, which is what currentFocus reads.
        val field = window.peekDecorView()?.findFocus()
        if (field is EditText && isSoftKeyboardUp(field)) {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(field.windowToken, 0)
            return
        }
        onBackPressedDispatcher.onBackPressed()
    }

    /** Whether the soft keyboard is showing for [field]'s window. */
    protected open fun isSoftKeyboardUp(field: View): Boolean =
        ViewCompat.getRootWindowInsets(field)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** A touch outside an armed split confirm cancels it, wherever on the screen it lands. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        NovaSplitConfirmRegistry.onTouch(window.decorView, event)
        return super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A press that began before another window took focus must not finish here.
        if (!hasFocus) keyGate.reset()
    }

    private fun requestRecreateIfScaleChanged() {
        val currentScalePercent = NovaFontScalePreferences.readScalePercent(this)
        val currentSystemFontScale = NovaFontScalePreferences.readSystemFontScale(this)
        val stale = currentScalePercent != appliedScalePercent ||
            abs(currentSystemFontScale - appliedSystemFontScale) > FONT_SCALE_EPSILON
        if (!stale || recreatePosted || !shouldRecreateForFontScaleChange()) return

        recreatePosted = true
        window.decorView.post {
            recreatePosted = false
            if (!isFinishing && !isDestroyed) {
                recreate()
            }
        }
    }

    private companion object {
        const val FONT_SCALE_EPSILON = 0.001f
    }
}

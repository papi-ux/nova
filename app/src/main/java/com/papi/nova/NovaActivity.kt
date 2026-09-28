package com.papi.nova

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
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
     * Screens turn this on when their own A and B handling is gone, so A acts on release and B goes
     * back through the dispatcher ([NovaKeyGate]). Game never does: its pad input is the host's.
     */
    protected open val novaKeyGate: Boolean = false
    private val keyGate = NovaKeyGate()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!novaKeyGate) return super.dispatchKeyEvent(event)
        return keyGate.dispatch(event, onBack = onBackPressedDispatcher::onBackPressed) { super.dispatchKeyEvent(it) }
    }

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

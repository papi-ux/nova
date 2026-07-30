package com.papi.nova.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.papi.nova.GameMenu
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.StartExternalDisplayControlReceiver
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardLayoutController
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.ExternalControllerView

private const val SOFT_KEYBOARD_SHOW_MAX_ATTEMPTS = 3
private const val SOFT_KEYBOARD_SHOW_RETRY_MILLIS = 100L
private const val SOFT_KEYBOARD_VISIBILITY_TIMEOUT_MILLIS = 1_500L

class ExternalDisplayControlController(
    private val host: ExternalDisplayControlHost,
) : View.OnKeyListener,
    KeyBoardLayoutController.ViewCallbacks {

    private val game = host.game
    private val display = host.controlDisplay
    private val context = host.hostContext
    private val controllerWindow = host.hostWindow

    private lateinit var prefConfig: PreferenceConfiguration
    private lateinit var rootLayout: ExternalControllerView
    private lateinit var zoomButton: ImageButton
    private var keyBoardLayoutController: KeyBoardLayoutController? = null

    private var isKeyboardVisible = false
    private var softKeyboardShowPending = false
    private var softKeyboardShowAttempts = 0
    private var softKeyboardShowAccepted = false
    private var softKeyboardWasVisible = false
    private var transientStateDisposed = false
    private var dismissalRequestedByNova = false
    private var menuOpenAtDisposal = false

    private val handler = Handler(Looper.getMainLooper())
    private var dimScreenRunnable = Runnable {}
    private var originalBrightness = -1f

    private var gameMenu: GameMenu? = null

    fun onCreate() {
        prefConfig = PreferenceConfiguration.readPreferences(context)
        initViews()
    }

    private fun initViews() {
        val windowInsetsController = WindowCompat.getInsetsController(controllerWindow, controllerWindow.decorView)

        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(controllerWindow, false)
        }
        ViewCompat.setOnApplyWindowInsetsListener(controllerWindow.decorView) { view, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (softKeyboardShowAccepted) {
                if (imeVisible) {
                    softKeyboardWasVisible = true
                    host.onSoftKeyboardVisibilityChanged(true)
                } else if (softKeyboardWasVisible) {
                    host.onSoftKeyboardVisibilityChanged(false)
                    softKeyboardShowAccepted = false
                    softKeyboardWasVisible = false
                }
            }
            updateKeyboardVisibility(
                imeVisible ||
                    (keyBoardLayoutController != null && keyBoardLayoutController!!.isKeyboardVisible()),
            )
            ViewCompat.onApplyWindowInsets(view, insets)
        }

        initializeComponents()
        createProgrammaticUI()
        initTouchEventHandling()
        setupInactivityTimeoutForBrightness()
        StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchEventHandling() {
        rootLayout.setOnTouchListener { view, event ->
            handleUserActivity()
            game.handleMotionEvent(view, event)
            true
        }
    }

    fun onStart() {
        transientStateDisposed = false
        dismissalRequestedByNova = false
        menuOpenAtDisposal = false
        resetInactivityTimer()
        if (game.isFinishing) {
            dismissAfterCurrentCallback()
        }
    }

    fun dismissAfterCurrentCallback() {
        dismissalRequestedByNova = true
        transientStateDisposed = true
        handler.post {
            if (host.isHostShowing()) {
                host.dismissHost()
            }
        }
    }

    fun cancel() {
        dismissalRequestedByNova = true
        transientStateDisposed = true
        handler.post {
            if (host.isHostShowing()) {
                host.cancelHost()
            }
        }
    }

    private fun disposeTransientState() {
        menuOpenAtDisposal = menuOpenAtDisposal || gameMenu?.isMenuOpen() == true
        transientStateDisposed = true
        gameMenu?.hideMenu()
        handler.removeCallbacksAndMessages(null)
        restoreBrightnessIfNeeded()
    }

    fun disposeAfterFailedShow() {
        dismissalRequestedByNova = true
        disposeTransientState()
    }

    fun onStop() {
        cancelPendingSoftKeyboardShow()
        softKeyboardShowAccepted = false
        softKeyboardWasVisible = false
        host.releaseSoftKeyboardFocus()
        disposeTransientState()
    }

    override fun onKeyboardControllerVisibilityChange(visible: Boolean) {
        updateKeyboardVisibility(visible)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInactivityTimeoutForBrightness() {
        originalBrightness = controllerWindow.attributes.screenBrightness

        dimScreenRunnable = Runnable {
            if (
                CompanionScreenDimmingPolicy.shouldDimNow(
                    keyboardVisible = isKeyboardVisible,
                    quickMenuOpen = gameMenu?.isMenuOpen() == true,
                )
            ) {
                val layout = controllerWindow.attributes
                layout.screenBrightness = 0.0f
                controllerWindow.attributes = layout
            } else {
                resetInactivityTimer()
            }
        }

        resetInactivityTimer()
    }

    private fun updateKeyboardVisibility(visible: Boolean) {
        if (isKeyboardVisible != visible) {
            isKeyboardVisible = visible
            if (isKeyboardVisible) {
                handler.removeCallbacks(dimScreenRunnable)
                restoreBrightnessIfNeeded()
            } else {
                resetInactivityTimer()
            }
        }
    }

    private fun restoreBrightnessIfNeeded() {
        val layout = controllerWindow.attributes
        if (layout.screenBrightness == 0.0f) {
            layout.screenBrightness = originalBrightness
            controllerWindow.attributes = layout
        }
    }

    private fun handleUserActivity() {
        if (transientStateDisposed) return
        restoreBrightnessIfNeeded()
        resetInactivityTimer()
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(dimScreenRunnable)
        if (!isKeyboardVisible) {
            CompanionScreenDimmingPolicy.delayMillis(prefConfig.companionScreenDimTimeoutSeconds)?.let { delay ->
                handler.postDelayed(dimScreenRunnable, delay)
            }
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        game.logCompanionDisplayFocus(display.displayId, hasFocus)
        if (hasFocus) {
            showPendingSoftKeyboardIfReady()
        }
        if (game.isFinishing) {
            dismissAfterCurrentCallback()
        }
    }

    fun handleCompanionBack() {
        if (game.isKeyboardLayoutVisible) {
            toggleFullKeyboard()
        } else if (!game.handleQuickMenuBackFromDisplay(display.displayId)) {
            cancel()
        }
    }

    fun handleBackFromOwningGame(): Boolean {
        if (!isCompanionDisplayAvailable()) return false
        handleCompanionBack()
        return true
    }

    private fun initializeComponents() {
        gameMenu = GameMenu(
            game,
            host.companionDialogContext,
            host.companionDialogWindowType,
            host::companionDialogWindowToken,
        ).also {
            it.setOnMenuDismissedListener(::handleUserActivity)
        }
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        game.recordQuickMenuInteraction(display.displayId)
        StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
        return game.onGenericMotionEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onKey(view: View, keyCode: Int, keyEvent: KeyEvent): Boolean {
        game.recordQuickMenuInteraction(display.displayId)
        if (keyEvent.deviceId >= 0) {
            StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
        }
        return when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> game.handleKeyDown(keyEvent)
            KeyEvent.ACTION_UP -> game.handleKeyUp(keyEvent)
            KeyEvent.ACTION_MULTIPLE -> game.handleKeyMultiple(keyEvent)
            else -> false
        }
    }

    fun onKeyDown(event: KeyEvent): Boolean {
        game.recordQuickMenuInteraction(display.displayId)
        if (event.deviceId >= 0) {
            StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
        }
        return game.onKeyDown(event.keyCode, event)
    }

    fun onKeyUp(event: KeyEvent): Boolean {
        game.recordQuickMenuInteraction(display.displayId)
        if (event.deviceId >= 0) {
            StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
        }
        return game.onKeyUp(event.keyCode, event)
    }

    fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        game.recordQuickMenuInteraction(display.displayId)
        if (event.deviceId >= 0) {
            StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
        }
        return game.onKeyMultiple(keyCode, repeatCount, event)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createProgrammaticUI() {
        rootLayout = ExternalControllerView(context)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        rootLayout.isFocusable = true
        rootLayout.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rootLayout.isFocusedByDefault = true
        }

        rootLayout.setInputCallbacks(game)
        rootLayout.setCommitTextEnabled(prefConfig.enableCommitText)

        host.setControllerContentView(rootLayout)

        val topLeftButtons = createButtonContainer(Gravity.TOP or Gravity.START)
        topLeftButtons.isFocusable = false
        zoomButton = createImageButton(R.drawable.ic_zoom_toggle) { toggleZoomMode(true) }
        if (game.isZoomModeEnabled) {
            zoomButton.alpha = 1.0f
        } else {
            zoomButton.alpha = 0.5f
        }
        topLeftButtons.addView(zoomButton)
        rootLayout.addView(topLeftButtons)

        val topRightButtons = createButtonContainer(Gravity.TOP or Gravity.END)
        topRightButtons.isFocusable = false
        topRightButtons.addView(createImageButton(R.drawable.ic_menu_external) { showGameMenu() })
        topRightButtons.addView(createImageButton(R.drawable.ic_close_external) { dismissAfterCurrentCallback() })
        rootLayout.addView(topRightButtons)

        val bottomLeftButton = createButtonContainer(Gravity.BOTTOM or Gravity.START)
        bottomLeftButton.isFocusable = false
        bottomLeftButton.addView(createImageButton(R.drawable.ic_android_keyboard) { _toggleKeyboard() })
        rootLayout.addView(bottomLeftButton)

        val bottomRightButton = createButtonContainer(Gravity.BOTTOM or Gravity.END)
        bottomRightButton.isFocusable = false
        bottomRightButton.addView(createImageButton(R.drawable.ic_fullscreen_keyboard) { _toggleFullKeyboard() })
        rootLayout.addView(bottomRightButton)
    }

    private fun _toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay on ExternalDisplayControlController")
        val inputManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val imeVisible =
            ViewCompat.getRootWindowInsets(rootLayout)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) {
            cancelPendingSoftKeyboardShow()
            softKeyboardShowAccepted = false
            softKeyboardWasVisible = false
            inputManager.hideSoftInputFromWindow(rootLayout.windowToken, 0)
            host.releaseSoftKeyboardFocus()
            return
        }

        host.prepareForSoftKeyboard()
        softKeyboardShowPending = true
        softKeyboardShowAttempts = 0
        softKeyboardShowAccepted = false
        softKeyboardWasVisible = false
        rootLayout.requestFocus()
        handler.postDelayed(::showPendingSoftKeyboardIfReady, SOFT_KEYBOARD_SHOW_RETRY_MILLIS)
    }

    private fun isSoftKeyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(rootLayout)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun cancelPendingSoftKeyboardShow() {
        softKeyboardShowPending = false
        softKeyboardShowAttempts = 0
    }

    private fun showPendingSoftKeyboardIfReady() {
        if (!softKeyboardShowPending) return
        if (!rootLayout.hasWindowFocus()) {
            softKeyboardShowAttempts += 1
            if (softKeyboardShowAttempts < SOFT_KEYBOARD_SHOW_MAX_ATTEMPTS) {
                handler.postDelayed(::showPendingSoftKeyboardIfReady, SOFT_KEYBOARD_SHOW_RETRY_MILLIS)
            } else {
                cancelPendingSoftKeyboardShow()
                host.releaseSoftKeyboardFocus()
            }
            return
        }
        rootLayout.post(::tryShowPendingSoftKeyboard)
    }

    private fun tryShowPendingSoftKeyboard() {
        if (!softKeyboardShowPending) return
        if (!rootLayout.hasWindowFocus()) {
            showPendingSoftKeyboardIfReady()
            return
        }
        rootLayout.requestFocus()
        val inputManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (inputManager.showSoftInput(rootLayout, InputMethodManager.SHOW_IMPLICIT)) {
            softKeyboardShowAccepted = true
            softKeyboardWasVisible = false
            cancelPendingSoftKeyboardShow()
            handler.postDelayed(
                {
                    if (!isSoftKeyboardVisible()) {
                        softKeyboardShowAccepted = false
                        softKeyboardWasVisible = false
                        host.releaseSoftKeyboardFocus()
                    }
                },
                SOFT_KEYBOARD_VISIBILITY_TIMEOUT_MILLIS,
            )
            return
        }

        softKeyboardShowAttempts += 1
        if (softKeyboardShowAttempts < SOFT_KEYBOARD_SHOW_MAX_ATTEMPTS) {
            handler.postDelayed(::showPendingSoftKeyboardIfReady, SOFT_KEYBOARD_SHOW_RETRY_MILLIS)
        } else {
            cancelPendingSoftKeyboardShow()
            host.releaseSoftKeyboardFocus()
        }
    }

    private fun initFullKeyboard(prefConfig: PreferenceConfiguration) {
        keyBoardLayoutController = KeyBoardLayoutController(rootLayout, context, prefConfig).also {
            it.setViewCallbacks(this)
            it.refreshLayout()
            it.show()
        }
    }

    private fun _toggleFullKeyboard() {
        val controller = keyBoardLayoutController
        if (controller == null) {
            initFullKeyboard(prefConfig)
            return
        }
        controller.toggleVisibility()
    }

    fun toggleZoomMode(callGame: Boolean) {
        if (callGame) {
            game.toggleZoomMode()
        } else {
            zoomButton.alpha = if (game.isZoomModeEnabled) 1.0f else 0.5f
        }
    }

    fun showGameMenu() {
        game.showGameMenuFromDisplay(display.displayId, null)
    }

    fun isCompanionDisplayAvailable(): Boolean {
        return host.isHostShowing() && display.isValid && !transientStateDisposed
    }

    fun shouldMigrateOpenMenuToStream(streamAvailable: Boolean): Boolean {
        return DualScreenQuickMenuPolicy.shouldMigrateCompanionMenu(
            menuWasOpen = menuOpenAtDisposal || gameMenu?.isMenuOpen() == true,
            dismissalRequestedByNova = dismissalRequestedByNova,
            streamAvailable = streamAvailable,
        )
    }

    fun showGameMenuOnCompanion(device: GameInputDevice?): Boolean {
        if (!isCompanionDisplayAvailable()) return false

        return try {
            handleUserActivity()
            gameMenu?.showMenu(device)
            gameMenu?.isMenuOpen() == true
        } catch (e: RuntimeException) {
            LimeLog.warning(
                "Nova: Android companion quick menu unavailable display_id=${display.displayId} " +
                    "exception=${e.javaClass.simpleName}"
            )
            runCatching { gameMenu?.hideMenu() }
            false
        }
    }

    fun hideGameMenu() {
        runCatching { gameMenu?.hideMenu() }
            .onFailure { error ->
                LimeLog.warning(
                    "Nova: Android companion quick menu dismiss failed display_id=${display.displayId} " +
                        "exception=${error.javaClass.simpleName}"
                )
            }
    }

    fun isGameMenuOpen(): Boolean {
        return gameMenu?.isMenuOpen() == true
    }

    private fun createButtonContainer(gravity: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(gravity)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                gravity,
            )
        }
    }

    private fun createImageButton(imageResourceId: Int, listener: View.OnClickListener): ImageButton {
        return ImageButton(context).apply {
            setImageResource(imageResourceId)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener(listener)
            layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(56))
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    fun toggleKeyboard() {
        _toggleKeyboard()
    }

    fun toggleFullKeyboard() {
        _toggleFullKeyboard()
    }

    fun toggleGameMenu() {
        showGameMenu()
    }
}

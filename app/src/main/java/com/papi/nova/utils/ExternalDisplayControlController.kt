package com.papi.nova.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.papi.nova.GameMenu
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.binding.input.virtual_controller.keyboard.KeyBoardLayoutController
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.ExternalControllerView
import com.papi.nova.ui.NovaCompanionCommandActionId
import com.papi.nova.ui.NovaCompanionCommandDeckState
import com.papi.nova.ui.NovaCompanionCommandDeckView
import com.papi.nova.ui.NovaHudUiState

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
    private lateinit var commandDeckView: NovaCompanionCommandDeckView
    private var keyBoardLayoutController: KeyBoardLayoutController? = null
    private var commandDeckState = NovaCompanionCommandDeckState.from(
        hud = NovaHudUiState.empty(),
        sessionState = "",
        displayRole = "Companion",
        unavailableLabel = context.getString(R.string.companion_deck_status_unavailable),
    )

    private var isAndroidKeyboardVisible = false
    private var isNovaKeyboardVisible = false
    private val isAnyKeyboardVisible: Boolean
        get() = isAndroidKeyboardVisible || isNovaKeyboardVisible
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
            updateAndroidKeyboardVisibility(imeVisible)
            updateNovaKeyboardVisibility(
                keyBoardLayoutController?.isKeyboardVisible() == true,
            )
            ViewCompat.onApplyWindowInsets(view, insets)
        }

        initializeComponents()
        createProgrammaticUI()
        initTouchEventHandling()
        setupInactivityTimeoutForBrightness()
        restoreCommandDeckFocus()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchEventHandling() {
        rootLayout.setOnTouchListener { view, event ->
            handleUserActivity(
                touchpadActive = event.actionMasked != MotionEvent.ACTION_UP &&
                    event.actionMasked != MotionEvent.ACTION_CANCEL,
            )
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
        updateNovaKeyboardVisibility(visible)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInactivityTimeoutForBrightness() {
        originalBrightness = controllerWindow.attributes.screenBrightness

        dimScreenRunnable = Runnable {
            if (
                CompanionScreenDimmingPolicy.shouldDimNow(
                    keyboardVisible = isAnyKeyboardVisible,
                    quickMenuOpen = gameMenu?.isMenuOpen() == true,
                )
            ) {
                val layout = controllerWindow.attributes
                layout.screenBrightness = 0.0f
                controllerWindow.attributes = layout
                updateCommandDeckInteraction(dimmed = true, touchpadActive = false)
            } else {
                resetInactivityTimer()
            }
        }

        resetInactivityTimer()
    }

    private fun updateAndroidKeyboardVisibility(visible: Boolean) {
        if (isAndroidKeyboardVisible == visible) return
        val wasAnyKeyboardVisible = isAnyKeyboardVisible
        isAndroidKeyboardVisible = visible
        applyKeyboardVisibilityChange(wasAnyKeyboardVisible)
    }

    private fun updateNovaKeyboardVisibility(visible: Boolean) {
        if (isNovaKeyboardVisible == visible) return
        val wasAnyKeyboardVisible = isAnyKeyboardVisible
        isNovaKeyboardVisible = visible
        applyKeyboardVisibilityChange(wasAnyKeyboardVisible)
    }

    private fun applyKeyboardVisibilityChange(wasAnyKeyboardVisible: Boolean) {
        if (isAnyKeyboardVisible) {
            handler.removeCallbacks(dimScreenRunnable)
            restoreBrightnessIfNeeded()
        } else {
            resetInactivityTimer()
            if (wasAnyKeyboardVisible && ::commandDeckView.isInitialized) {
                restoreCommandDeckFocus()
            }
        }
        renderCommandDeck()
    }

    private fun restoreBrightnessIfNeeded() {
        val layout = controllerWindow.attributes
        if (layout.screenBrightness == 0.0f) {
            layout.screenBrightness = originalBrightness
            controllerWindow.attributes = layout
        }
    }

    private fun handleUserActivity(touchpadActive: Boolean = false) {
        if (transientStateDisposed) return
        restoreBrightnessIfNeeded()
        updateCommandDeckInteraction(dimmed = false, touchpadActive = touchpadActive)
        resetInactivityTimer()
    }

    private fun updateCommandDeckInteraction(dimmed: Boolean, touchpadActive: Boolean) {
        if (!::commandDeckView.isInitialized ||
            (commandDeckState.dimmed == dimmed && commandDeckState.touchpadActive == touchpadActive)
        ) {
            return
        }
        commandDeckState = commandDeckState.copy(dimmed = dimmed, touchpadActive = touchpadActive)
        renderCommandDeck()
    }

    private fun restoreCommandDeckFocus() {
        host.prepareForCommandDeckFocus()
        if (::commandDeckView.isInitialized) {
            commandDeckView.restoreSafeActionFocus()
        }
    }

    private fun renderCommandDeck() {
        if (!::commandDeckView.isInitialized) return
        commandDeckView.render(
            commandDeckState.withActionSelections(
                androidKeyboardVisible = isAndroidKeyboardVisible,
                novaKeyboardVisible = isNovaKeyboardVisible,
                novaHudVisible = game.isNovaHudShowing(),
                zoomPanEnabled = game.isZoomModeEnabled,
            ),
        )
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(dimScreenRunnable)
        if (!isAnyKeyboardVisible) {
            CompanionScreenDimmingPolicy.delayMillis(prefConfig.companionScreenDimTimeoutSeconds)?.let { delay ->
                handler.postDelayed(dimScreenRunnable, delay)
            }
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        game.logCompanionDisplayFocus(display.displayId, hasFocus)
        if (hasFocus) {
            showPendingSoftKeyboardIfReady()
            if (!softKeyboardShowPending && !isAnyKeyboardVisible) {
                restoreCommandDeckFocus()
            }
        }
        if (game.isFinishing) {
            dismissAfterCurrentCallback()
        }
    }

    fun handleCompanionBack() {
        if (isNovaKeyboardVisible) {
            toggleFullKeyboard()
        } else if (!game.handleQuickMenuBackFromDisplay(display.displayId)) {
            game.hideCompanionControlsForSession()
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
            it.setOnMenuDismissedListener {
                handleUserActivity()
                restoreCommandDeckFocus()
            }
        }
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        game.recordQuickMenuInteraction(display.displayId)
        val handled = game.onGenericMotionEvent(event)
        yieldCommandDeckFocusAfterHandledInput(handled, event.deviceId)
        return handled
    }

    @Suppress("DEPRECATION")
    override fun onKey(view: View, keyCode: Int, keyEvent: KeyEvent): Boolean {
        game.recordQuickMenuInteraction(display.displayId)
        val handled = when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> game.handleKeyDown(keyEvent)
            KeyEvent.ACTION_UP -> game.handleKeyUp(keyEvent)
            KeyEvent.ACTION_MULTIPLE -> game.handleKeyMultiple(keyEvent)
            else -> false
        }
        yieldCommandDeckFocusAfterHandledInput(handled, keyEvent.deviceId)
        return handled
    }

    fun onKeyDown(event: KeyEvent): Boolean {
        game.recordQuickMenuInteraction(display.displayId)
        val handled = game.onKeyDown(event.keyCode, event)
        yieldCommandDeckFocusAfterHandledInput(handled, event.deviceId)
        return handled
    }

    fun onKeyUp(event: KeyEvent): Boolean {
        game.recordQuickMenuInteraction(display.displayId)
        val handled = game.onKeyUp(event.keyCode, event)
        yieldCommandDeckFocusAfterHandledInput(handled, event.deviceId)
        return handled
    }

    fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        game.recordQuickMenuInteraction(display.displayId)
        val handled = game.onKeyMultiple(keyCode, repeatCount, event)
        yieldCommandDeckFocusAfterHandledInput(handled, event.deviceId)
        return handled
    }

    private fun yieldCommandDeckFocusAfterHandledInput(handled: Boolean, deviceId: Int) {
        if (!handled || deviceId < 0 || transientStateDisposed || isAnyKeyboardVisible) return
        handler.post {
            if (!transientStateDisposed && !isAnyKeyboardVisible && ::rootLayout.isInitialized) {
                rootLayout.requestFocus()
            }
        }
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

        commandDeckView = NovaCompanionCommandDeckView(context, ::onCommandDeckAction)
        rootLayout.addView(
            commandDeckView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        renderCommandDeck()
    }

    fun updateCommandDeckState(state: NovaCompanionCommandDeckState) {
        commandDeckState = state.copy(
            dimmed = commandDeckState.dimmed,
            touchpadActive = commandDeckState.touchpadActive,
        )
        renderCommandDeck()
    }

    private fun onCommandDeckAction(actionId: NovaCompanionCommandActionId) {
        handleUserActivity()
        when (actionId) {
            NovaCompanionCommandActionId.ANDROID_KEYBOARD -> _toggleKeyboard()
            NovaCompanionCommandActionId.NOVA_KEYBOARD -> _toggleFullKeyboard()
            NovaCompanionCommandActionId.QUICK_KEYS -> showQuickKeys()
            NovaCompanionCommandActionId.COMMAND_CENTER -> showGameMenu()
            NovaCompanionCommandActionId.HIDE_COMPANION -> game.hideCompanionControlsForSession()
            NovaCompanionCommandActionId.NOVA_HUD -> game.toggleNovaHud()
            NovaCompanionCommandActionId.ZOOM_PAN -> toggleZoomMode(true)
            NovaCompanionCommandActionId.DISCONNECT -> game.disconnect()
            NovaCompanionCommandActionId.END_SESSION -> {
                restoreCommandDeckFocus()
                game.quit()
            }
        }
        handler.post(::renderCommandDeck)
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
        }
    }

    fun showGameMenu() {
        game.showGameMenuFromDisplay(display.displayId, null)
    }

    private fun showQuickKeys() {
        game.recordQuickMenuInteraction(display.displayId)
        gameMenu?.showSpecialKeysMenuFromCommandDeck()
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

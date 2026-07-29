package com.papi.nova.utils

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.papi.nova.Game
import com.papi.nova.R
import com.papi.nova.StartExternalDisplayControlReceiver
import com.papi.nova.binding.input.GameInputDevice

class ExternalDisplayControlActivity : Activity(),
    ExternalDisplayControlHost {

    private lateinit var controller: ExternalDisplayControlController
    private var owningGame: Game? = null
    private var registeredWithGame = false
    private var softKeyboardFocusLeaseActive = false
    private var softKeyboardWasVisible = false

    override val game: Game
        get() = requireNotNull(owningGame ?: Game.instance)

    override val controlDisplay: Display
        get() {
            val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                this@ExternalDisplayControlActivity.getDisplay()
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            return currentDisplay ?: requireNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY))
        }

    override val hostContext: Context
        get() = this

    override val hostWindow: Window
        get() = window

    override val companionDialogContext: Context
        get() = this

    override val companionDialogWindowType: Int
        get() = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG

    override fun companionDialogWindowToken(): IBinder? = window.decorView.windowToken

    override fun setControllerContentView(view: View) {
        setContentView(view)
    }

    override fun isHostShowing(): Boolean = !isFinishing && !isDestroyed

    override fun dismissHost() {
        finish()
        overridePendingTransition(0, 0)
    }

    override fun cancelHost() {
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.ExternalDisplayControllerTheme)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        super.onCreate(savedInstanceState)

        val currentGame = Game.instance
        if (currentGame == null || currentGame.isFinishing) {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        owningGame = currentGame

        controller = ExternalDisplayControlController(this)
        controller.onCreate()
        if (!attachToOwningGameIfNeeded()) {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!attachToOwningGameIfNeeded()) {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        if (::controller.isInitialized) {
            controller.onStart()
        }
    }

    override fun onStop() {
        releaseSoftKeyboardFocus()
        if (::controller.isInitialized) {
            controller.onStop()
        }
        if (registeredWithGame) {
            owningGame?.detachExternalDisplayControlActivity(this)
            registeredWithGame = false
        }
        super.onStop()
    }

    private fun attachToOwningGameIfNeeded(): Boolean {
        if (registeredWithGame) return true
        val currentGame = owningGame ?: return false
        if (currentGame.isFinishing || currentGame.isDestroyed) return false
        if (!currentGame.attachExternalDisplayControlActivity(this)) return false
        registeredWithGame = true
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::controller.isInitialized) {
            controller.onWindowFocusChanged(hasFocus)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::controller.isInitialized) {
            controller.handleCompanionBack()
        } else {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return controller.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return controller.onKeyDown(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return controller.onKeyUp(event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        return controller.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun dismissAfterCurrentCallback() {
        controller.dismissAfterCurrentCallback()
    }

    override fun handleBackFromOwningGame(): Boolean = controller.handleBackFromOwningGame()

    override fun toggleZoomMode(callGame: Boolean) {
        controller.toggleZoomMode(callGame)
    }

    override fun isCompanionDisplayAvailable(): Boolean {
        return controller.isCompanionDisplayAvailable()
    }

    override fun shouldMigrateOpenMenuToStream(streamAvailable: Boolean): Boolean {
        return controller.shouldMigrateOpenMenuToStream(streamAvailable)
    }

    override fun showGameMenuOnCompanion(device: GameInputDevice?): Boolean {
        return controller.showGameMenuOnCompanion(device)
    }

    override fun hideGameMenu() {
        controller.hideGameMenu()
    }

    override fun isGameMenuOpen(): Boolean {
        return controller.isGameMenuOpen()
    }

    override fun prepareForSoftKeyboard() {
        if (softKeyboardFocusLeaseActive) return
        softKeyboardFocusLeaseActive = true
        softKeyboardWasVisible = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    override fun onSoftKeyboardVisibilityChanged(imeVisible: Boolean) {
        if (!softKeyboardFocusLeaseActive) return
        if (imeVisible) {
            softKeyboardWasVisible = true
        } else if (softKeyboardWasVisible) {
            releaseSoftKeyboardFocus()
        }
    }

    override fun releaseSoftKeyboardFocus() {
        if (!softKeyboardFocusLeaseActive) return
        softKeyboardFocusLeaseActive = false
        softKeyboardWasVisible = false
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        StartExternalDisplayControlReceiver.requestFocusToGameActivity(false)
    }

    override fun toggleKeyboard() {
        controller.toggleKeyboard()
    }

    override fun toggleFullKeyboard() {
        controller.toggleFullKeyboard()
    }

    override fun toggleGameMenu() {
        controller.toggleGameMenu()
    }

    companion object {
        @JvmStatic
        fun launch(game: Game, displayId: Int) {
            val intent = Intent(game, ExternalDisplayControlActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val options = ActivityOptions.makeBasic()
                options.setLaunchDisplayId(displayId)
                game.startActivity(intent, options.toBundle())
            } else {
                game.startActivity(intent)
            }
            game.overridePendingTransition(0, 0)
        }
    }
}

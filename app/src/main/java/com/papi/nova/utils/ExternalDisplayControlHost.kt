package com.papi.nova.utils

import android.content.Context
import android.os.IBinder
import android.view.Display
import android.view.View
import android.view.Window
import com.papi.nova.Game
import com.papi.nova.binding.input.GameInputDevice
import com.papi.nova.ui.NovaCompanionCommandDeckState

interface ExternalDisplayControlHost {
    val game: Game
    val controlDisplay: Display
    val hostContext: Context
    val hostWindow: Window

    val companionDialogContext: Context
    val companionDialogWindowType: Int

    fun companionDialogWindowToken(): IBinder?
    fun setControllerContentView(view: View)
    fun isHostShowing(): Boolean
    fun dismissHost()
    fun cancelHost()
    fun dismissAfterCurrentCallback()
    fun handleBackFromOwningGame(): Boolean
    fun toggleZoomMode(callGame: Boolean)
    fun isCompanionDisplayAvailable(): Boolean
    fun shouldMigrateOpenMenuToStream(streamAvailable: Boolean): Boolean
    fun showGameMenuOnCompanion(device: GameInputDevice?): Boolean
    fun hideGameMenu()
    fun isGameMenuOpen(): Boolean
    fun toggleKeyboard()
    fun toggleFullKeyboard()
    fun toggleGameMenu()
    fun updateCommandDeckState(state: NovaCompanionCommandDeckState)

    fun prepareForCommandDeckFocus() = Unit
    fun prepareForSoftKeyboard() = Unit
    fun onSoftKeyboardVisibilityChanged(imeVisible: Boolean) = Unit
    fun releaseSoftKeyboardFocus() = Unit
}

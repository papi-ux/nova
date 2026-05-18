package com.papi.nova.ui

import com.papi.nova.binding.input.GameInputDevice

interface GameGestures {
    fun toggleKeyboard()

    fun showGameMenu(device: GameInputDevice?) {
    }

    fun cycleNovaHudFromController() {
    }
}

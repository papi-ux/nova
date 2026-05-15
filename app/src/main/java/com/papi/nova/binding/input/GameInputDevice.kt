package com.papi.nova.binding.input

import com.papi.nova.GameMenu

interface GameInputDevice {
    fun getGameMenuOptions(): List<GameMenu.MenuOption>

    fun supportsControllerMouseEmulation(): Boolean = false

    fun isControllerMouseEmulationActive(): Boolean = false

    fun setControllerMouseEmulationActive(active: Boolean) = Unit
}

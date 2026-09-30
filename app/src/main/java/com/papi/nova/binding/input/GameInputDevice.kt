package com.papi.nova.binding.input

import com.papi.nova.ui.panel.NovaMenuItem

interface GameInputDevice {
    /** Rows this controller adds to the Command Center's More Controls page. */
    fun getGameMenuOptions(): List<NovaMenuItem>

    fun supportsControllerMouseEmulation(): Boolean = false

    fun isControllerMouseEmulationActive(): Boolean = false

    fun setControllerMouseEmulationActive(active: Boolean) = Unit
}

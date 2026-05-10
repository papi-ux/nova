package com.papi.nova.binding.input;

import com.papi.nova.GameMenu;

import java.util.List;

/**
 * Description
 * Date: 2024-01-16
 * Time: 15:26
 * User: Genng(genng1991@gmail.com)
 */
public interface GameInputDevice {

    /**
     * @return list of device specific game menu options, e.g. configure a controller's mouse mode
     */
    List<GameMenu.MenuOption> getGameMenuOptions();

    /**
     * @return true when this input device can temporarily behave like a host mouse
     */
    default boolean supportsControllerMouseEmulation() {
        return false;
    }

    /**
     * @return true when this input device is currently sending mouse input instead of gamepad input
     */
    default boolean isControllerMouseEmulationActive() {
        return false;
    }

    /**
     * Enables or disables controller-driven host mouse input.
     */
    default void setControllerMouseEmulationActive(boolean active) {
    }
}

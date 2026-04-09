package com.papi.nova.ui;

import com.papi.nova.binding.input.GameInputDevice;

public interface GameGestures {
    void toggleKeyboard();

    default void showGameMenu(GameInputDevice device){};
}

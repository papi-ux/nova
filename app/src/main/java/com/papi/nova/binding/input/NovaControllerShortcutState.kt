package com.papi.nova.binding.input

import android.view.KeyEvent

enum class NovaControllerShortcutAction {
    NONE,
    DEFER_GUIDE,
    FORWARD_GUIDE_TO_HOST,
    OPEN_QUICK_MENU,
    CYCLE_NOVA_HUD,
    PASS_THROUGH_GUIDE_TAP,
    CONSUME_CHORD_BUTTON
}

class NovaControllerShortcutState {
    private var guideDown = false
    private var guideForwardedToHost = false
    private var guideConsumedByNova = false
    private var consumedChordButton = 0
    private var pendingConsumedButtonRelease = 0
    private var startDown = false
    private var selectDown = false
    private var startSelectConsumedByNova = false
    private var backDown = false
    private var yDown = false
    private var backYConsumedByNova = false

    fun onButtonDown(keyCode: Int, repeatCount: Int): NovaControllerShortcutAction {
        if (!guideDown && isNoGuideChordKey(keyCode)) {
            if (startSelectConsumedByNova && isStartSelectKey(keyCode)) {
                return NovaControllerShortcutAction.CONSUME_CHORD_BUTTON
            }
            if (backYConsumedByNova && isBackYKey(keyCode)) {
                return NovaControllerShortcutAction.CONSUME_CHORD_BUTTON
            }
            if (repeatCount == 0) {
                markNoGuideChordDown(keyCode)
                if (startDown && selectDown) {
                    startSelectConsumedByNova = true
                    return NovaControllerShortcutAction.OPEN_QUICK_MENU
                }
                if (backDown && yDown) {
                    backYConsumedByNova = true
                    return NovaControllerShortcutAction.CYCLE_NOVA_HUD
                }
            }
            return NovaControllerShortcutAction.NONE
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (repeatCount == 0) {
                guideDown = true
                guideForwardedToHost = false
                guideConsumedByNova = false
                consumedChordButton = 0
                pendingConsumedButtonRelease = 0
                return NovaControllerShortcutAction.DEFER_GUIDE
            }
            return when {
                guideDown && !guideForwardedToHost && !guideConsumedByNova ->
                    NovaControllerShortcutAction.DEFER_GUIDE
                guideConsumedByNova -> NovaControllerShortcutAction.CONSUME_CHORD_BUTTON
                else -> NovaControllerShortcutAction.NONE
            }
        }

        if (!guideDown) {
            return NovaControllerShortcutAction.NONE
        }

        if (guideConsumedByNova) {
            return if (keyCode == consumedChordButton) {
                NovaControllerShortcutAction.CONSUME_CHORD_BUTTON
            } else {
                NovaControllerShortcutAction.NONE
            }
        }

        if (repeatCount != 0) {
            return NovaControllerShortcutAction.NONE
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_MENU -> consumeNovaChord(keyCode, NovaControllerShortcutAction.OPEN_QUICK_MENU)
            KeyEvent.KEYCODE_BUTTON_Y -> consumeNovaChord(keyCode, NovaControllerShortcutAction.CYCLE_NOVA_HUD)
            else -> {
                if (!guideForwardedToHost) {
                    guideForwardedToHost = true
                    NovaControllerShortcutAction.FORWARD_GUIDE_TO_HOST
                } else {
                    NovaControllerShortcutAction.NONE
                }
            }
        }
    }

    fun onButtonUp(keyCode: Int): NovaControllerShortcutAction {
        if (isNoGuideChordKey(keyCode)) {
            markNoGuideChordUp(keyCode)
        }

        if (startSelectConsumedByNova && isStartSelectKey(keyCode)) {
            if (!startDown && !selectDown) {
                startSelectConsumedByNova = false
            }
            return NovaControllerShortcutAction.CONSUME_CHORD_BUTTON
        }

        if (backYConsumedByNova && isBackYKey(keyCode)) {
            if (!backDown && !yDown) {
                backYConsumedByNova = false
            }
            return NovaControllerShortcutAction.CONSUME_CHORD_BUTTON
        }

        if (pendingConsumedButtonRelease == keyCode) {
            pendingConsumedButtonRelease = 0
            return NovaControllerShortcutAction.CONSUME_CHORD_BUTTON
        }

        if (!guideDown) {
            return NovaControllerShortcutAction.NONE
        }

        if (guideConsumedByNova) {
            if (keyCode == consumedChordButton || keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (keyCode == consumedChordButton) {
                    pendingConsumedButtonRelease = 0
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                    resetGuideState()
                }
                return NovaControllerShortcutAction.CONSUME_CHORD_BUTTON
            }
            return NovaControllerShortcutAction.NONE
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            val action = if (guideForwardedToHost) {
                NovaControllerShortcutAction.NONE
            } else {
                NovaControllerShortcutAction.PASS_THROUGH_GUIDE_TAP
            }
            reset()
            return action
        }

        return NovaControllerShortcutAction.NONE
    }

    fun reset() {
        resetGuideState()
        startDown = false
        selectDown = false
        startSelectConsumedByNova = false
        backDown = false
        yDown = false
        backYConsumedByNova = false
        pendingConsumedButtonRelease = 0
    }

    private fun resetGuideState() {
        guideDown = false
        guideForwardedToHost = false
        guideConsumedByNova = false
        consumedChordButton = 0
    }

    private fun consumeNovaChord(
        keyCode: Int,
        action: NovaControllerShortcutAction
    ): NovaControllerShortcutAction {
        guideConsumedByNova = true
        consumedChordButton = keyCode
        pendingConsumedButtonRelease = keyCode
        return action
    }

    private fun isStartSelectKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BUTTON_START ||
            keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_BUTTON_SELECT

    private fun isBackYKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_Y

    private fun isNoGuideChordKey(keyCode: Int): Boolean =
        isStartSelectKey(keyCode) || isBackYKey(keyCode)

    private fun markNoGuideChordDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_MENU -> startDown = true
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectDown = true
            KeyEvent.KEYCODE_BACK -> backDown = true
            KeyEvent.KEYCODE_BUTTON_Y -> yDown = true
        }
    }

    private fun markNoGuideChordUp(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_MENU -> startDown = false
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectDown = false
            KeyEvent.KEYCODE_BACK -> backDown = false
            KeyEvent.KEYCODE_BUTTON_Y -> yDown = false
        }
    }
}

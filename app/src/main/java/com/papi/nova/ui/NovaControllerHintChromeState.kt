package com.papi.nova.ui

enum class NovaLibraryInputMode {
    CONTROLLER,
    TOUCH
}

enum class NovaControllerHintChromeEvent {
    CONTROLLER_INPUT,
    TOUCH_INPUT,
    IDLE,
    LAYOUT_CHANGED,
    HELP_REQUESTED,
    EXPLICIT_REVEAL
}

data class NovaControllerHintChromeState(
    val visible: Boolean = true,
    val lastInputMode: NovaLibraryInputMode? = null
) {
    fun reduce(event: NovaControllerHintChromeEvent): NovaControllerHintChromeState {
        return when (event) {
            NovaControllerHintChromeEvent.CONTROLLER_INPUT -> reduceInput(NovaLibraryInputMode.CONTROLLER)
            NovaControllerHintChromeEvent.TOUCH_INPUT -> reduceInput(NovaLibraryInputMode.TOUCH)
            NovaControllerHintChromeEvent.IDLE,
            NovaControllerHintChromeEvent.LAYOUT_CHANGED,
            NovaControllerHintChromeEvent.HELP_REQUESTED,
            NovaControllerHintChromeEvent.EXPLICIT_REVEAL -> {
                if (visible) this else copy(visible = true)
            }
        }
    }

    private fun reduceInput(inputMode: NovaLibraryInputMode): NovaControllerHintChromeState {
        val changedMode = lastInputMode != null && lastInputMode != inputMode
        val nextVisible = changedMode
        return if (visible == nextVisible && lastInputMode == inputMode) {
            this
        } else {
            copy(visible = nextVisible, lastInputMode = inputMode)
        }
    }
}

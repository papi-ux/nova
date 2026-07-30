package com.papi.nova.ui

enum class NovaControllerHintChromeEvent {
    BROWSE_INTENT,
    IDLE,
    LAYOUT_CHANGED,
    HELP_REQUESTED,
    EXPLICIT_REVEAL
}

data class NovaControllerHintChromeState(
    val visible: Boolean = true
) {
    fun reduce(event: NovaControllerHintChromeEvent): NovaControllerHintChromeState {
        val nextVisible = when (event) {
            NovaControllerHintChromeEvent.BROWSE_INTENT -> false
            NovaControllerHintChromeEvent.IDLE,
            NovaControllerHintChromeEvent.LAYOUT_CHANGED,
            NovaControllerHintChromeEvent.HELP_REQUESTED,
            NovaControllerHintChromeEvent.EXPLICIT_REVEAL -> true
        }
        return if (visible == nextVisible) this else copy(visible = nextVisible)
    }
}

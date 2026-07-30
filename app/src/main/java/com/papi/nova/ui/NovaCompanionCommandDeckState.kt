package com.papi.nova.ui

enum class NovaCompanionCommandActionId {
    ANDROID_KEYBOARD,
    NOVA_KEYBOARD,
    QUICK_KEYS,
    NOVA_HUD,
    ZOOM_PAN,
    COMMAND_CENTER,
    DISCONNECT,
    END_SESSION,
}

data class NovaCompanionCommandAction(
    val id: NovaCompanionCommandActionId,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val selected: Boolean = false,
)

data class NovaCompanionCommandDeckState(
    val actualFps: String,
    val targetFps: String,
    val latency: String,
    val bitrate: String,
    val codec: String,
    val resolution: String,
    val profile: String,
    val session: String,
    val displayRole: String,
    val actions: List<NovaCompanionCommandAction>,
    val dimmed: Boolean = false,
    val touchpadActive: Boolean = false,
) {
    fun initialFocusActionId(): NovaCompanionCommandActionId? =
        actions.firstOrNull { it.enabled && !it.destructive }?.id

    fun withActionSelections(
        androidKeyboardVisible: Boolean,
        novaKeyboardVisible: Boolean,
        novaHudVisible: Boolean,
        zoomPanEnabled: Boolean,
    ): NovaCompanionCommandDeckState = copy(
        actions = actions.map { action ->
            action.copy(
                selected = when (action.id) {
                    NovaCompanionCommandActionId.ANDROID_KEYBOARD -> androidKeyboardVisible
                    NovaCompanionCommandActionId.NOVA_KEYBOARD -> novaKeyboardVisible
                    NovaCompanionCommandActionId.NOVA_HUD -> novaHudVisible
                    NovaCompanionCommandActionId.ZOOM_PAN -> zoomPanEnabled
                    else -> false
                },
            )
        },
    )

    companion object {
        private const val EMPTY_VALUE = "--"

        private val orderedActions = listOf(
            NovaCompanionCommandAction(NovaCompanionCommandActionId.ANDROID_KEYBOARD),
            NovaCompanionCommandAction(NovaCompanionCommandActionId.NOVA_KEYBOARD),
            NovaCompanionCommandAction(NovaCompanionCommandActionId.QUICK_KEYS),
            NovaCompanionCommandAction(NovaCompanionCommandActionId.NOVA_HUD),
            NovaCompanionCommandAction(NovaCompanionCommandActionId.ZOOM_PAN),
            NovaCompanionCommandAction(NovaCompanionCommandActionId.COMMAND_CENTER),
            NovaCompanionCommandAction(NovaCompanionCommandActionId.DISCONNECT),
            NovaCompanionCommandAction(
                id = NovaCompanionCommandActionId.END_SESSION,
                destructive = true,
            ),
        )

        fun from(
            hud: NovaHudUiState,
            sessionState: String,
            displayRole: String,
            unavailableLabel: String,
        ): NovaCompanionCommandDeckState {
            val hasRuntimeProjection =
                hud.fpsLabel != EMPTY_VALUE ||
                    hud.latencyLabel != "--ms" ||
                    hud.bitrateLabel != EMPTY_VALUE ||
                    hud.resolutionLabel != EMPTY_VALUE ||
                    hud.codecLabel.isNotBlank() ||
                    hud.streamModeLabel.isNotBlank()

            return NovaCompanionCommandDeckState(
                actualFps = hud.fpsLabel.ifBlank { EMPTY_VALUE },
                targetFps = hud.targetFpsLabel.ifBlank { EMPTY_VALUE },
                latency = hud.latencyLabel.ifBlank { "--ms" },
                bitrate = hud.bitrateLabel.ifBlank { EMPTY_VALUE },
                codec = hud.codecLabel.ifBlank { EMPTY_VALUE },
                resolution = hud.resolutionLabel.ifBlank { EMPTY_VALUE },
                profile = hud.autopilotLabel
                    .takeIf { hasRuntimeProjection && it.isNotBlank() }
                    ?: unavailableLabel,
                session = sessionState.toDisplayLabel(unavailableLabel),
                displayRole = displayRole.trim().ifBlank { unavailableLabel },
                actions = orderedActions,
            )
        }

        private fun String.toDisplayLabel(unavailableLabel: String): String =
            trim()
                .takeIf { it.isNotBlank() }
                ?.split(Regex("[_\\s-]+"))
                ?.joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar { first -> first.titlecase() }
                }
                ?: unavailableLabel
    }
}

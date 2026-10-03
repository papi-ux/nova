package com.papi.nova.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.papi.nova.R
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.manager.NovaStreamSource
import com.papi.nova.manager.NovaStreamSourceLine
import com.papi.nova.ui.compose.NovaActionButton

/** Main retires this token; IO only reads its atomic authority immediately before dispatch. */
internal class NovaClientSettingsWriteAuthority {
    private val active = java.util.concurrent.atomic.AtomicBoolean(true)
    val valid: Boolean get() = active.get()
    fun retire() { active.set(false) }
}

internal data class NovaHostCopyRecovery(val line: String, val enabled: Boolean, val busy: Boolean,
    val onUseDeviceSetting: () -> Unit)

/** Typed provenance and current authority decide whether recovery may be offered. */
internal fun novaHostCopyRecovery(
    source: NovaStreamSourceLine?, settings: PolarisClientSettings?,
    space: Boolean, watch: Boolean, metered: Boolean, checking: Boolean, busy: Boolean,
    isCurrent: () -> Boolean, onUseDeviceSetting: () -> Unit,
): NovaHostCopyRecovery? {
    if (source?.source != NovaStreamSource.HOST_SAVED_COPY || space || watch || metered) return null
    fun allowed() = isCurrent() && !checking && !busy && settings?.capabilities?.let {
        it.displayModeOverride && it.targetBitrateOverride
    } == true
    return NovaHostCopyRecovery(source.text, allowed(), busy, { if (allowed()) onUseDeviceSetting() })
}

/** The notice and its action are neighbours, and the action uses the shared focusable button. */
@Composable
internal fun NovaHostCopyRecoveryRow(recovery: NovaHostCopyRecovery) {
    Column(Modifier.fillMaxWidth().testTag("nova-host-copy-recovery")) {
        NovaPanelStatusText(caption = recovery.line)
        NovaActionButton(
            text = stringResource(if (recovery.busy) R.string.nova_device_setting_saving else R.string.nova_use_device_setting),
            onClick = recovery.onUseDeviceSetting, enabled = recovery.enabled,
            modifier = Modifier.testTag("nova-use-device-setting"),
        )
    }
}

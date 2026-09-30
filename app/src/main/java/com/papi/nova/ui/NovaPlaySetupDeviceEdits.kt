package com.papi.nova.ui

import com.papi.nova.preferences.NovaSettingDefinition
import com.papi.nova.preferences.NovaSettingValue
import com.papi.nova.preferences.NovaTierSaveResult
import kotlinx.coroutines.suspendCancellableCoroutine

/** The activity's device edit action, kept separate from the host request debounce. */
internal class NovaPlaySetupDeviceEdits(
    private val save: (NovaSettingDefinition, NovaSettingValue, () -> Unit) -> Unit,
    private val settle: (suspend () -> Unit) -> Unit,
    private val outcome: () -> NovaTierSaveResult?,
    private val recheck: (NovaTierSaveResult?) -> Unit,
) {
    fun change(definition: NovaSettingDefinition, value: NovaSettingValue) {
        settle {
            suspendCancellableCoroutine<Unit> { waiting ->
                save(definition, value) {
                    if (waiting.isActive) waiting.resumeWith(Result.success(Unit))
                }
            }
            recheck(outcome())
        }
    }
}

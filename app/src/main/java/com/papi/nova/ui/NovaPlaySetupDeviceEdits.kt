package com.papi.nova.ui

import com.papi.nova.preferences.NovaSettingDefinition
import com.papi.nova.preferences.NovaSettingValue
import com.papi.nova.preferences.NovaTierSaveResult
import kotlinx.coroutines.CompletableDeferred

/** The activity's device edit action, kept separate from the host request debounce. */
internal class NovaPlaySetupDeviceEdits(
    private val save: (NovaSettingDefinition, NovaSettingValue, () -> Unit) -> Unit,
    private val settle: (suspend () -> Unit) -> Unit,
    private val outcome: () -> NovaTierSaveResult?,
    private val recheck: (NovaTierSaveResult?) -> Unit,
) {
    private var outstanding = 0
    private var committed = CompletableDeferred(Unit)

    fun change(definition: NovaSettingDefinition, value: NovaSettingValue) {
        if (outstanding++ == 0) committed = CompletableDeferred()
        // Settle invalidates the old plan immediately. Its replaceable slot owns only the
        // host recheck; the VM owns every local write and its actual saved-setup receipt.
        settle {
            committed.await()
            recheck(outcome())
        }
        save(definition, value) {
            if (--outstanding == 0) committed.complete(Unit)
        }
    }
}

package com.papi.nova.ui

import android.app.Activity
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.wol.WakeOnLanSender
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaField
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.novaSurfaces
import kotlinx.coroutines.flow.MutableStateFlow

/** Save runs outside the UI thread; completion returns on the UI thread. */
internal fun showWakeMacAddressEditor(
    activity: Activity,
    computer: ComputerDetails,
    panel: NovaPanelState = activity.novaSurfaces.panel,
    save: (String, (Boolean) -> Unit) -> Unit,
) {
    val discovered = WakeOnLanSender.usableMacAddress(computer.macAddress)
        ?: activity.getString(R.string.wol_address_unknown)
    val title = activity.getString(R.string.wol_address_title)
    lateinit var makeForm: (String, String?) -> NovaCommonPage.Form
    makeForm = { initial, warning ->
        lateinit var form: NovaCommonPage.Form
        form = NovaCommonPage.Form(
            key = "wake-address",
            title = title,
            fields = listOf(NovaField("address", title, initial, maxLength = 32,
                hint = activity.getString(R.string.wol_address_description, discovered))),
            submitLabel = activity.getString(R.string.save),
            warning = warning,
            onSubmit = submit@{ values ->
                if (panel.top !== form) return@submit null
                val value = values["address"].orEmpty()
                if (value.isNotBlank() && WakeOnLanSender.usableMacAddress(value) == null) {
                    activity.getString(R.string.wol_address_invalid)
                } else {
                    // Only the top page is composed. Preserve the entered draft before a
                    // Busy page disposes its fields; failure returns to an editable copy.
                    val capturedForm = makeForm(value, null)
                    panel.replaceTop(capturedForm)
                    val busy = NovaCommonPage.Busy("wake-address-saving", title,
                        MutableStateFlow(activity.getString(R.string.nova_cc_live_tuning_saving)))
                    panel.push(busy)
                    val complete: (Boolean) -> Unit = { saved ->
                        if (panel.top === busy && !activity.isFinishing && !activity.isDestroyed) {
                            panel.pop()
                            if (panel.top === capturedForm) {
                                if (saved) {
                                    if (!panel.pop()) panel.close()
                                } else {
                                    panel.replaceTop(makeForm(value,
                                        activity.getString(R.string.wol_address_save_failed)))
                                }
                            }
                        }
                    }
                    try { save(value, complete) } catch (_: RuntimeException) { complete(false) }
                    // The original Form is no longer top, so its host cannot pop the
                    // Busy page, even if a callback completed synchronously.
                    null
                }
            },
        )
        form
    }
    panel.push(makeForm(computer.manualWakeMacAddress.orEmpty(), null))
}

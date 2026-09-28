package com.papi.nova.computers

import android.content.Context
import com.papi.nova.nvstream.wol.WakeOnLanSender

/** User-owned wake addresses are separate from discovery and its database writes. */
internal class WakeMacOverrides(context: Context) {
    private val preferences = context.getSharedPreferences("wake_mac_overrides", Context.MODE_PRIVATE)

    fun get(uuid: String): String? =
        if (uuid.isBlank()) null else WakeOnLanSender.usableMacAddress(preferences.getString(uuid, null))

    fun save(uuid: String, value: String?): Boolean {
        if (uuid.isBlank()) return false
        val normalized = if (value.isNullOrBlank()) null else WakeOnLanSender.usableMacAddress(value) ?: return false
        val editor = preferences.edit()
        if (normalized == null) editor.remove(uuid) else editor.putString(uuid, normalized)
        return editor.commit()
    }

    fun remove(uuid: String) {
        preferences.edit().remove(uuid).commit()
    }
}

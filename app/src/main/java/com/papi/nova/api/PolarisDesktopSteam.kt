package com.papi.nova.api

import org.json.JSONObject

data class PolarisDesktopSteam(
    val state: String,
    val revision: String = "",
    val gameActive: Boolean = false,
    val effectiveKeepRunning: Boolean = false,
    val accepted: Boolean = false,
    val message: String = "",
) {
    val available: Boolean get() = state == "running" || state == "stopped"

    companion object {
        fun parse(json: JSONObject): PolarisDesktopSteam? {
            if (json.opt("version") != 1 || json.opt("status") !is Boolean) return null
            val state = json.optString("state")
            if (state == "unavailable" && json.getBoolean("status") == false)
                return PolarisDesktopSteam(state, message = json.optString("error").take(400))
            if (state !in setOf("running", "stopped") || !json.getBoolean("status") ||
                json.opt("revision") !is String || json.getString("revision").isBlank() ||
                json.opt("game_active") !is Boolean || json.opt("effective_keep_running") !is Boolean) return null
            return PolarisDesktopSteam(state, json.getString("revision"), json.getBoolean("game_active"),
                json.getBoolean("effective_keep_running"), json.opt("accepted") == true)
        }
    }
}

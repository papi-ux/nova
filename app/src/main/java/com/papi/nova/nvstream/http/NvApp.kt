package com.papi.nova.nvstream.http

import com.papi.nova.LimeLog
import com.papi.nova.shared.polaris.model.PolarisGame
import java.util.Locale

class NvApp {
    var appName: String = ""

    var appUUID: String? = ""

    var appId = 0
        set(value) {
            field = value
            initialized = true
        }

    var appIndex = 0
    private var initialized = false
    var isHdrSupported = false
    var source = ""
        private set
    var launcherSource = ""
        private set
    private var launcherDetail = ""
    var platform = ""
        private set
    var runtime = ""
        private set
    var steamAppid = ""
        private set
    private var category = ""

    constructor()

    constructor(appName: String?) {
        this.appName = appName ?: ""
    }

    constructor(appName: String?, appUUID: String?, appId: Int, hdrSupported: Boolean) {
        this.appName = appName ?: ""
        this.appUUID = appUUID
        this.appId = appId
        this.isHdrSupported = hdrSupported
        initialized = true
    }

    fun setAppId(appId: String?) {
        try {
            this.appId = Integer.parseInt(appId ?: "null")
        } catch (e: NumberFormatException) {
            LimeLog.warning("Malformed app ID: $appId")
        }
    }

    fun setAppIndex(appIndex: String?) {
        try {
            this.appIndex = Integer.parseInt(appIndex ?: "null")
        } catch (e: NumberFormatException) {
            LimeLog.warning("Malformed app index: $appIndex")
        }
    }

    fun applyPolarisMetadata(game: PolarisGame?): Boolean {
        if (game == null) {
            return false
        }

        val nextSource = normalizeToken(game.source)
        val nextLauncherSource = normalizeToken(game.launcherSource)
        val nextLauncherDetail = normalizeToken(game.launcherDetail)
        val nextPlatform = normalizeToken(game.platform)
        val nextRuntime = normalizeToken(game.runtime)
        val nextSteamAppid = safeString(game.steamAppid)
        val nextCategory = normalizeToken(game.category)

        val changed = source != nextSource ||
            launcherSource != nextLauncherSource ||
            launcherDetail != nextLauncherDetail ||
            platform != nextPlatform ||
            runtime != nextRuntime ||
            steamAppid != nextSteamAppid ||
            category != nextCategory

        source = nextSource
        launcherSource = nextLauncherSource
        launcherDetail = nextLauncherDetail
        platform = nextPlatform
        runtime = nextRuntime
        steamAppid = nextSteamAppid
        category = nextCategory
        return changed
    }

    val sourceLabel: String
        get() = when (if (launcherSource.isNotEmpty()) launcherSource else source) {
            "steam" -> "Steam"
            "lutris" -> "Lutris"
            "heroic" -> "Heroic"
            "manual" -> "Manual"
            else -> ""
        }

    val platformLabel: String
        get() = when (platform) {
            "linux" -> "Linux"
            "windows" -> "Windows"
            "macos" -> "macOS"
            else -> ""
        }

    val runtimeLabel: String
        get() = when (runtime) {
            "native" -> "Native"
            "proton" -> "Proton"
            "wine" -> "Wine"
            "steam" -> "Steam"
            "umu" -> "UMU"
            else -> ""
        }

    val metadataLabel: String
        get() {
            val parts = ArrayList<String>()
            addDistinct(parts, sourceLabel)
            addDistinct(parts, platformLabel)
            addDistinct(parts, runtimeLabel)
            val label = StringBuilder()
            for (part in parts) {
                if (label.isNotEmpty()) {
                    label.append(" · ")
                }
                label.append(part)
            }
            return label.toString()
        }

    val metadataKey: String
        get() = "$source|$launcherSource|$launcherDetail|$platform|$runtime|$steamAppid|$category"

    fun isInitialized(): Boolean = initialized

    override fun toString(): String {
        val str = StringBuilder()
        str.append("Name: ").append(appName).append("\n")
        str.append("UUID: ").append(appUUID).append("\n")
        str.append("ID: ").append(appId).append("\n")
        str.append("HDR Supported: ").append(if (isHdrSupported) "Yes" else "Unknown").append("\n")
        val metadata = metadataLabel
        if (metadata.isNotEmpty()) {
            str.append("Source: ").append(metadata).append("\n")
        }
        return str.toString()
    }

    companion object {
        const val REMOTE_INPUT_UUID = "8CB5C136-DA67-4F99-B4A1-F9CD35005CF4"

        private fun safeString(value: String?): String = value?.trim() ?: ""

        private fun normalizeToken(value: String?): String = safeString(value).lowercase(Locale.US)

        private fun addDistinct(parts: MutableList<String>, value: String?) {
            if (value == null || value.isEmpty()) {
                return
            }
            for (part in parts) {
                if (part.equals(value, ignoreCase = true)) {
                    return
                }
            }
            parts.add(value)
        }
    }
}

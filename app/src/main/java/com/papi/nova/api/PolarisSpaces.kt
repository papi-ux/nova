package com.papi.nova.api

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.json.JSONObject
import java.io.StringReader

/**
 * Only the paired device's permitted Spaces. Names and activity never confer permission.
 *
 * [canOpen] and [blockedReason] arrived after nova#308 as optional keys. A host that does not
 * send them gets the answer the older client derived from [state] alone.
 *
 * [launcher] is the launcher the Space opens, as the host's word for it (steam, heroic, lutris).
 * A Space is named by its owner, so the name says nothing about what is inside; null when the
 * host does not say.
 */
data class PolarisSpace(
    val id: String,
    val name: String,
    val state: String,
    val selected: Boolean,
    val libraryEnabled: Boolean = false,
    val canOpen: Boolean = state == "ready" || state == "running",
    val blockedReason: String? = null,
    val launcher: String? = null,
) {
    /** Open or resume: the host's answer, plus a Space that is already running for this device. */
    val openable: Boolean get() = canOpen || state == "running"
}

/** The host's seat budget: how many Spaces may run at once, and how many are running. */
data class PolarisSpacesCapacity(val concurrentLimit: Int, val concurrentActive: Int)

data class PolarisSpaces(
    val enabled: Boolean,
    val available: Boolean,
    val canSwitch: Boolean,
    val selectedId: String,
    val spaces: List<PolarisSpace>,
    val desktopAllowed: Boolean = false,
    /** Why the host cannot offer Spaces; the host sends it only with available:false. */
    val unavailableReason: String? = null,
    /** Why this device cannot change Space; meaningful only while canSwitch is false. */
    val switchBlockedReason: String? = null,
    /** The Space Polaris routes this device to when it has not chosen one: an id, "desktop", or null. */
    val defaultSpaceId: String? = null,
    val capacity: PolarisSpacesCapacity? = null,
) {
    val selected: PolarisSpace? get() = spaces.singleOrNull { it.selected }

    /** The device streams the host's Desktop: said by the host, never assumed from an empty selection. */
    val desktopSelected: Boolean get() = available && selectedId == "desktop"

    companion object {
        private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
        private val wordPattern = Regex("[A-Za-z0-9_:.-]{1,64}")
        private val states = setOf("ready", "starting", "running", "stopping", "in_use", "unavailable")

        fun parse(payload: String): PolarisSpaces? = runCatching {
            require(payload.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
            // JSONObject on Android accepts duplicate keys. Reject ambiguous documents first.
            JsonReader(StringReader(payload)).use { reader ->
                fun visit(depth: Int) {
                    require(depth <= 4)
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> { reader.beginObject(); val keys = mutableSetOf<String>()
                            while (reader.hasNext()) { require(keys.add(reader.nextName())); visit(depth + 1) }; reader.endObject() }
                        JsonToken.BEGIN_ARRAY -> { reader.beginArray(); while (reader.hasNext()) visit(depth + 1); reader.endArray() }
                        else -> reader.skipValue()
                    }
                }
                visit(0); require(reader.peek() == JsonToken.END_DOCUMENT)
            }
            val json = JSONObject(payload)
            require(json.opt("schema") is Int && json.getInt("schema") == 1 && json.opt("status") == true)
            fun flag(key: String): Boolean { require(json.opt(key) is Boolean); return json.getBoolean(key) }
            val enabled = flag("enabled"); val available = flag("available"); val canSwitch = flag("can_switch")
            require(json.opt("selected_space_id") is String)
            val selectedId = json.getString("selected_space_id")
            val array = json.getJSONArray("spaces"); require(array.length() <= 4096)
            val spaces = (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                require(listOf("id", "name", "state").all { entry.opt(it) is String } && entry.opt("selected") is Boolean)
                val id = entry.getString("id"); val name = entry.getString("name"); val state = entry.getString("state")
                require(id != "desktop" && idPattern.matches(id) && name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= 128 &&
                    name.none { it.code < 32 || it.code == 127 } && state in states)
                require(!entry.has("library_enabled") || entry.opt("library_enabled") is Boolean)
                require(!entry.has("can_open") || entry.opt("can_open") is Boolean)
                PolarisSpace(
                    id, name, state, entry.getBoolean("selected"), entry.optBoolean("library_enabled", false),
                    canOpen = if (entry.has("can_open")) entry.getBoolean("can_open") else state == "ready" || state == "running",
                    blockedReason = optionalWord(entry, "blocked_reason"),
                    launcher = decoration(entry, "launcher"),
                )
            }
            require(spaces.map { it.id }.toSet().size == spaces.size)
            require(!available || enabled)
            require(!canSwitch || available)
            require(!json.has("desktop_allowed") || json.opt("desktop_allowed") is Boolean)
            val desktopAllowed = json.optBoolean("desktop_allowed", false)
            require(if (available) {
                if (selectedId == "desktop") desktopAllowed && spaces.none { it.selected }
                else spaces.count { it.selected } == 1 && spaces.single { it.selected }.id == selectedId
            } else selectedId.isEmpty() && spaces.none { it.selected })
            val defaultSpaceId = optionalWord(json, "default_space_id")
                ?.also { require(it == "desktop" || idPattern.matches(it)) }
            val capacity = json.opt("capacity")?.takeUnless { it == JSONObject.NULL }?.let { raw ->
                require(raw is JSONObject)
                val limit = raw.opt("concurrent_limit"); val active = raw.opt("concurrent_active")
                require(limit is Int && active is Int && limit >= 0 && active >= 0)
                PolarisSpacesCapacity(limit, active)
            }
            PolarisSpaces(
                enabled, available, canSwitch, selectedId, spaces, desktopAllowed,
                unavailableReason = optionalWord(json, "unavailable_reason"),
                switchBlockedReason = optionalWord(json, "switch_blocked_reason"),
                defaultSpaceId = defaultSpaceId,
                capacity = capacity,
            )
        }.getOrNull()

        /**
         * A word that only decorates a row. Anything the client cannot use, whether missing, not
         * a string, or not a plain word, reads as nothing: a label must never cost the list.
         */
        private fun decoration(source: JSONObject, key: String): String? =
            (source.opt(key) as? String)?.takeIf { wordPattern.matches(it) }

        /**
         * An optional wire word. Absent, null or empty reads as null; a value the client has
         * never seen is kept, because the copy layer has a sentence for "something new" and
         * a reason must never cost the whole snapshot. Anything but a short plain string is
         * an error.
         */
        private fun optionalWord(source: JSONObject, key: String): String? {
            if (!source.has(key) || source.isNull(key)) return null
            val value = source.opt(key)
            require(value is String)
            if (value.isEmpty()) return null
            require(wordPattern.matches(value))
            return value
        }
    }
}

package com.papi.nova.utils

import com.google.gson.Gson

object KeyConfigHelper {
    class ShortcutFile {
        @JvmField
        var data: MutableList<Shortcut>

        constructor() {
            data = ArrayList()
        }

        constructor(data: List<Shortcut>) {
            this.data = ArrayList(data)
        }
    }

    class Shortcut {
        @JvmField
        var id: String? = null

        @JvmField
        var name: String? = null

        @JvmField
        var sticky: Boolean = false

        @JvmField
        var keys: MutableList<String>

        constructor() {
            keys = ArrayList()
        }

        constructor(id: String?, name: String?, sticky: Boolean, keys: List<String>) {
            this.id = id
            this.name = name
            this.sticky = sticky
            this.keys = ArrayList(keys)
        }
    }

    @JvmStatic
    fun parseShortcutFile(json: String): ShortcutFile {
        return Gson().fromJson(json, ShortcutFile::class.java)
    }
}

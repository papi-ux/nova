package com.papi.nova.profiles

import java.util.UUID

class SettingsProfile(
    private val uuid: UUID,
    private var name: String,
    private val createdUtc: Long,
    private var modifiedUtc: Long,
    private var options: Map<String, Any>?,
) {
    private var isActive = false

    fun getUuid(): UUID = uuid

    fun getName(): String = name

    fun setName(name: String) {
        this.name = name
    }

    fun getCreatedUtc(): Long = createdUtc

    fun getModifiedUtc(): Long = modifiedUtc

    fun setModifiedUtc(modifiedUtc: Long) {
        this.modifiedUtc = modifiedUtc
    }

    fun getOptions(): Map<String, Any>? = options

    fun setOptions(options: Map<String, Any>?) {
        this.options = options
    }

    fun isActive(): Boolean = isActive

    fun setActive(active: Boolean) {
        isActive = active
    }
}

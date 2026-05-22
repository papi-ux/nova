package com.papi.nova.preferences

import com.papi.nova.BuildConfig

object NovaAppVersion {
    fun current(): String = format(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    fun format(versionName: String, versionCode: Int): String {
        return "Nova $versionName ($versionCode)"
    }
}

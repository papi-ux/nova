package com.papi.nova.binding

import android.content.Context
import com.papi.nova.binding.crypto.AndroidCryptoProvider
import com.papi.nova.nvstream.http.LimelightCryptoProvider

object PlatformBinding {
    @JvmStatic
    fun getCryptoProvider(c: Context): LimelightCryptoProvider = AndroidCryptoProvider(c)
}

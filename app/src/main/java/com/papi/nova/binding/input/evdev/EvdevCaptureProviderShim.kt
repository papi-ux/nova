package com.papi.nova.binding.input.evdev

import android.app.Activity
import com.papi.nova.BuildConfig
import com.papi.nova.binding.input.capture.InputCaptureProvider

object EvdevCaptureProviderShim {
    @JvmStatic
    fun isCaptureProviderSupported(): Boolean = BuildConfig.ROOT_BUILD

    @JvmStatic
    fun createEvdevCaptureProvider(activity: Activity, listener: EvdevListener): InputCaptureProvider {
        return try {
            val providerClass =
                Class.forName("com.papi.nova.binding.input.evdev.EvdevCaptureProvider")
            providerClass.constructors[0].newInstance(activity, listener) as InputCaptureProvider
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(e)
        }
    }
}

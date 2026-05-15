package com.papi.nova.binding.input.capture

import android.app.Activity
import android.view.View
import com.papi.nova.BuildConfig
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.binding.input.evdev.EvdevCaptureProviderShim
import com.papi.nova.binding.input.evdev.EvdevListener

object InputCaptureManager {
    @JvmStatic
    fun getInputCaptureProvider(activity: Activity, evdevListener: EvdevListener): InputCaptureProvider? {
        val streamView = activity.findViewById<View>(R.id.streamContainer)

        if (EvdevCaptureProviderShim.isCaptureProviderSupported()) {
            if (BuildConfig.ROOT_BUILD) {
                LimeLog.info("Using Evdev mouse capture provider")
                return EvdevCaptureProviderShim.createEvdevCaptureProvider(activity, evdevListener)
            }

            LimeLog.warning("Evdev capture supported on non-root build")
        }

        if (AndroidNativePointerCaptureProvider.isCaptureProviderSupported()) {
            LimeLog.info("Using Android O+ native mouse capture provider")
            return AndroidNativePointerCaptureProvider(activity, streamView)
        }

        if (AndroidPointerIconCaptureProvider.isCaptureProviderSupported()) {
            LimeLog.info("Using Android N+ pointer icon capture provider")
            return AndroidPointerIconCaptureProvider(activity, streamView)
        }

        if (ShieldCaptureProvider.isCaptureProviderSupported()) {
            LimeLog.info("Using Shield mouse capture provider")
            return ShieldCaptureProvider(activity)
        }

        return null
    }
}

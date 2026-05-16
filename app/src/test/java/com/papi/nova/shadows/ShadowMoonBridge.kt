package com.papi.nova.shadows

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(value = com.papi.nova.nvstream.jni.MoonBridge::class, isInAndroidSdk = false)
class ShadowMoonBridge {
    class AudioConfiguration(
        val channelCount: Int,
        val channelMask: Int
    ) {
        fun toInt(): Int = 0
    }

    companion object {
        @JvmField val AUDIO_CONFIGURATION_STEREO = AudioConfiguration(2, 0x3)
        @JvmField val AUDIO_CONFIGURATION_51_SURROUND = AudioConfiguration(6, 0x3F)
        @JvmField val AUDIO_CONFIGURATION_71_SURROUND = AudioConfiguration(8, 0x63F)

        const val DR_OK = 0

        @JvmStatic
        @Implementation
        protected fun __staticInitializer__() = Unit

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun CAPABILITY_SLICES_PER_FRAME(s: Byte): Int = 0

        @JvmStatic
        fun getPendingAudioDuration(): Int = 0

        @JvmStatic
        fun cleanupBridge() = Unit
    }
}

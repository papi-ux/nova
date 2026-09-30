package com.papi.nova.ui

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.papi.nova.preferences.PreferenceConfiguration.FormatOption

/**
 * Whether this device's video decoders take a picture of a given size, as they say themselves.
 *
 * Play Setup lists every size the host offers and keeps one this device cannot decode in the list,
 * greyed, with that as its reason. The answer is only ever what a decoder reports: null when no
 * decoder for the codec answers, or the codec is not one MediaCodec decodes (PyroWave decodes on
 * the GPU), and then nothing is greyed and no reason is made up.
 */
internal fun interface NovaDecodeLimit {
    /** True or false as a decoder for the codec answers, null when none does. */
    fun decodes(width: Int, height: Int): Boolean?

    companion object {
        /** Knows nothing, so it greys nothing. */
        val Unknown = NovaDecodeLimit { _, _ -> null }

        /**
         * The hardware decoders for the codec [format] asks for: Auto may use HEVC, H.264 or AV1, so
         * any of them taking the size is enough. Software decoders are left out, since a stream is
         * not played through one. Read once, when made.
         */
        fun forFormat(format: FormatOption?): NovaDecodeLimit {
            val mimes = novaDecodeMimes(format)
            if (mimes.isEmpty()) return Unknown
            val capabilities = runCatching { hardwareDecoders(mimes) }.getOrDefault(emptyList())
            if (capabilities.isEmpty()) return Unknown
            return NovaDecodeLimit { width, height ->
                capabilities.any { caps -> runCatching { caps.isSizeSupported(width, height) }.getOrDefault(false) }
            }
        }

        private fun hardwareDecoders(mimes: List<String>): List<MediaCodecInfo.VideoCapabilities> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && !isSoftware(it) }
                .flatMap { info ->
                    info.supportedTypes
                        .filter { type -> mimes.any { it.equals(type, ignoreCase = true) } }
                        .mapNotNull { type -> runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull() }
                }

        private fun isSoftware(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isSoftwareOnly
            val name = info.name.lowercase()
            return name.startsWith("omx.google.") || name.startsWith("c2.android.")
        }
    }
}

/** The MediaCodec types a stream in [format] may use; none for a codec MediaCodec does not decode. */
internal fun novaDecodeMimes(format: FormatOption?): List<String> = when (format) {
    FormatOption.FORCE_H264 -> listOf("video/avc")
    FormatOption.FORCE_HEVC -> listOf("video/hevc")
    FormatOption.FORCE_AV1 -> listOf("video/av01")
    FormatOption.FORCE_PYROWAVE -> emptyList()
    else -> listOf("video/hevc", "video/avc", "video/av01")
}

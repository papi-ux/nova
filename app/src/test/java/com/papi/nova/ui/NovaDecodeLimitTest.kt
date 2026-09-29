package com.papi.nova.ui

import com.papi.nova.preferences.PreferenceConfiguration.FormatOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Play Setup greys a size this device cannot decode only on a decoder's own word. Where nothing
 * answers, nothing is greyed and no reason is made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaDecodeLimitTest {
    @Test
    fun eachCodecAsksItsOwnDecodersAndAutoAsksEveryOneItMayUse() {
        assertEquals(listOf("video/avc"), novaDecodeMimes(FormatOption.FORCE_H264))
        assertEquals(listOf("video/hevc"), novaDecodeMimes(FormatOption.FORCE_HEVC))
        assertEquals(listOf("video/av01"), novaDecodeMimes(FormatOption.FORCE_AV1))
        assertEquals(listOf("video/hevc", "video/avc", "video/av01"), novaDecodeMimes(FormatOption.AUTO))
        assertEquals("PyroWave decodes on the GPU, which MediaCodec does not describe", emptyList<String>(), novaDecodeMimes(FormatOption.FORCE_PYROWAVE))
    }

    @Test
    fun withNoDecoderToAskTheAnswerIsUnknownNotNo() {
        assertNull(NovaDecodeLimit.forFormat(FormatOption.FORCE_PYROWAVE).decodes(3840, 2160))
        // Robolectric registers no decoders, so nothing answers for HEVC either.
        assertNull(NovaDecodeLimit.forFormat(FormatOption.FORCE_HEVC).decodes(5760, 3240))
        assertNull(NovaDecodeLimit.Unknown.decodes(1920, 1080))
    }
}

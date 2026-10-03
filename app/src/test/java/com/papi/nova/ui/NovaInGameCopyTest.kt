package com.papi.nova.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review finding 10: words over the stream are plain, and the player's machine is the host. The
 * session line kept "(DMA-BUF)" and "(SHM)" in brackets, and the legacy warning said "Slow
 * connection to PC".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaInGameCopyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theSessionLineSaysTheCaptureInPlainWords() {
        listOf(R.string.nova_cc_capture_gpu, R.string.nova_cc_capture_cpu, R.string.nova_cc_capture_gpu_encoder).forEach { id ->
            val words = context.getString(id)
            assertFalse("\"$words\" names no mechanism in brackets", words.contains("(") || words.contains("DMA-BUF") || words.contains("SHM"))
        }
    }

    @Test
    fun theLegacyConnectionWarningSaysHost() {
        val german = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.GERMAN) })
        listOf(R.string.slow_connection_msg, R.string.poor_connection_msg).forEach { id ->
            val english = context.getString(id)
            assertTrue("\"$english\" says host", english.contains("host") && !english.contains("PC"))
            val deutsch = german.getString(id)
            assertTrue("\"$deutsch\" says Host", deutsch.contains("Host") && !deutsch.contains("PC"))
        }
    }
}

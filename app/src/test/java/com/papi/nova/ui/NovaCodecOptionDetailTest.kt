package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Play Setup's Video Codec page repeated one sentence under every standard codec, put Recommended
 * in a name, and mixed "(Experimental)" with "(experimental)".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaCodecOptionDetailTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun eachCodecHasItsOwnSentenceAndRecommendedLeadsTheNote() {
        val values = listOf("auto", "forceav1", "forceh265", "neverh265")
        val details = values.map { novaCodecOptionDetail(context, it) }
        assertEquals(details.size, details.toSet().size)
        assertTrue(details.first().startsWith("Recommended."))
        assertFalse(context.getString(R.string.videoformat_auto).contains("Recommended"))
    }
}

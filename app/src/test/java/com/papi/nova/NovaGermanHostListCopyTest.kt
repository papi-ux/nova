package com.papi.nova

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The line the Hosts screen shows while its host list is still starting, as a German player reads
 * it. It named an internal class, "Der ComputerManager Dienst", where the English says Nova is
 * still starting its host list, and the host word test missed it inside the compound word (C28).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "de")
class NovaGermanHostListCopyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theHostListStartingLineNamesNoClassAndSaysHost() {
        val line = context.getString(R.string.error_manager_not_running)
        assertFalse(line, line.contains("ComputerManager"))
        assertFalse(line, line.contains("Dienst"))
        assertFalse(line, line.contains("Computer", ignoreCase = true))
        assertTrue("German, not the English fallback: $line", line.contains("Nova") && line.contains("Hosts"))
    }
}

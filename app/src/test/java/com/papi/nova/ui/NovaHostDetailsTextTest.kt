package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** View Details showed "Manual Address: null" and permission bits in monospace on the Shield. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostDetailsTextTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun labelledLinesWithWhatIsUnknownLeftOut() {
        val details = ComputerDetails().apply {
            name = "test-pc"
            uuid = "abc-123"
            state = ComputerDetails.State.ONLINE
            activeAddress = ComputerDetails.AddressTuple("192.0.2.10", 47989)
            localAddress = ComputerDetails.AddressTuple("192.0.2.10", 47989)
            pairState = PairingManager.PairState.PAIRED
            httpsPort = 47984
        }
        assertEquals(
            listOf(
                "Name: test-pc",
                "Status: Online",
                "Address: 192.0.2.10:47989",
                "Paired: Yes",
                "HTTPS port: 47984",
                "Host ID: abc-123",
            ).joinToString("\n"),
            novaHostDetailsText(context, details),
        )
        assertFalse(novaHostDetailsText(context, details).contains("null"))
    }

    @Test
    fun theMenuShowsItAsTextNotADump() {
        val sheet = File("src/main/java/com/papi/nova/ui/NovaHostSheet.kt").readText()
        assertFalse(sheet.contains("message = details.toString()"))
    }
}

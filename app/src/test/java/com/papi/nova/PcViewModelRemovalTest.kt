package com.papi.nova

import android.os.Looper
import com.papi.nova.nvstream.http.ComputerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PcViewModelRemovalTest {
    @Test
    fun removalEmitsAuthoritativeEmptyState() {
        val viewModel = PcViewModel()
        val details = ComputerDetails().apply {
            uuid = "remove-me"
            name = "Remove Me"
        }

        viewModel.updateComputer(details)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("remove-me"), viewModel.computers.value.map { it.details.uuid })

        viewModel.removeComputer("remove-me")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(viewModel.computers.value.isEmpty())
        assertTrue(viewModel.computersLiveData.value.orEmpty().isEmpty())
    }
}

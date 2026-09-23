package com.papi.nova.manager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchRefusalReasonTest {
    @Test
    fun onlyAHostThatCannotResolveALaunchIsToldToUpdatePolaris() {
        // The report this exists for: a tablet could not open Desktop, and the single message every
        // refusal shared told its owner to update a host that was current, running, and answering
        // correctly. Two of these are about the host's version. The rest are not, and saying so was
        // worse than saying nothing, because it sent someone to fix the one thing that was fine.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updatePolaris = context.getString(R.string.nova_launch_deterministic_host_required)

        assertEquals(
            listOf(LaunchRefusalReason.HOST_TOO_OLD, LaunchRefusalReason.PROFILE_NOT_DETERMINISTIC),
            LaunchRefusalReason.entries.filter {
                context.getString(it.messageRes()) == updatePolaris
            },
        )
    }

    @Test
    fun everyRefusalCarriesItsOwnWords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val messages = LaunchRefusalReason.entries.associateWith { context.getString(it.messageRes()) }

        for ((reason, message) in messages) {
            assertTrue("$reason has no message", message.isNotBlank())
            assertTrue("$reason does not tell anyone what to do next", message.trim().endsWith("."))
        }

        // The five envelope causes are the ones the single message used to cover. Each has to read
        // differently from the others, or naming the cause has bought the person nothing.
        val envelope = listOf(
            LaunchRefusalReason.TOPOLOGY,
            LaunchRefusalReason.HDR,
            LaunchRefusalReason.ENCODER,
            LaunchRefusalReason.DISPLAY_MODE,
            LaunchRefusalReason.BITRATE,
        ).map { messages.getValue(it) }

        assertEquals(envelope.size, envelope.toSet().size)
        assertTrue(
            envelope.none { it == context.getString(R.string.nova_launch_deterministic_host_required) }
        )
    }
}

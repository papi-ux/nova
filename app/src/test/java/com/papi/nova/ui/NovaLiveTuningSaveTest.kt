package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live Tuning's switch from the split's confirm to the result on its row (review finding 5): a
 * save the host did not confirm is held for the row, after the host's state is read again, and
 * cleared after its time; a later result is never cut short by an earlier timer.
 */
class NovaLiveTuningSaveTest {
    private val observed = PolarisSessionStatus(state = "streaming")
    private val sent = mutableListOf<Boolean>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val order = mutableListOf<String>()
    private var confirms = true
    private var changes = 0

    private fun saver(launch: (suspend () -> Unit) -> Unit = { block -> runBlocking { block() } }) = NovaLiveTuningSave(
        launch = launch,
        onMain = { block -> block() },
        later = { delayMs, block -> timers += delayMs to block },
        save = { enable, _ ->
            sent += enable
            order += "save"
            confirms
        },
        fetch = { order += "fetch" },
        publish = { order += "publish" },
        changed = { changes++ },
    )

    @Test
    fun aSaveTheHostDidNotConfirmIsHeldForItsRowThenClears() {
        confirms = false
        val save = saver()
        save.request(enable = false, observed)

        assertEquals(listOf(false), sent)
        assertEquals("the host's state is read again before the row says anything", listOf("save", "fetch", "publish"), order)
        assertFalse(save.pending)
        assertEquals("the row holds what was asked for", false, save.unconfirmed)
        assertEquals(listOf(NovaLiveTuningSave.SHOWN_MS), timers.map { it.first })

        val before = changes
        timers.removeAt(0).second()
        assertNull("after its time the row goes back to its state", save.unconfirmed)
        assertTrue("and the page is told", changes > before)
    }

    @Test
    fun anEarlierTimerNeverCutsALaterResultShort() {
        confirms = false
        val save = saver()
        save.request(enable = false, observed)
        save.request(enable = true, observed)
        assertEquals(true, save.unconfirmed)

        timers[0].second()
        assertEquals("the first result's timer leaves the second standing", true, save.unconfirmed)
        timers[1].second()
        assertNull(save.unconfirmed)
    }

    @Test
    fun aConfirmedSaveLeavesNothingOnTheRow() {
        val save = saver()
        save.request(enable = true, observed)
        assertNull(save.unconfirmed)
        assertTrue(timers.isEmpty())
    }

    /**
     * Review finding 7, the handler half: the save asks for the state the split offered, in both
     * directions, whatever the status it was confirmed on says. A flip of that status, the old
     * handler, sends the opposite as soon as the host changed while the split stood armed.
     */
    @Test
    fun itAsksForTheStateTheSplitOfferedInBothDirections() {
        fun host(on: Boolean) = observed.copy(
            liveTuning = com.papi.nova.api.LiveTuningStatus(
                enabled = on,
                state = if (on) "stable" else "off",
                supported = true,
                reason = "supported",
                qualityLimitKbps = 20000,
                requestedBitrateKbps = 20000,
                appliedBitrateKbps = 20000,
                configurationRevision = "a".repeat(64),
                hostInstance = "host",
                sequence = 1,
                sessionGeneration = 41,
                appSessionId = "session",
            ),
            liveTuningPresent = true,
        )
        val save = saver()

        save.request(enable = true, host(on = false))
        save.request(enable = false, host(on = true))
        assertEquals("Turn On asks for On, Turn Off for Off", listOf(true, false), sent)

        // Armed at Off, and another device turned it on before the confirm: still On.
        save.request(enable = true, host(on = true))
        // Armed at On, and it was turned off meanwhile: still Off.
        save.request(enable = false, host(on = false))
        assertEquals(listOf(true, false, true, false), sent)
    }

    @Test
    fun aSecondConfirmWhileASaveIsOnItsWayDoesNothing() {
        val waiting = mutableListOf<suspend () -> Unit>()
        val save = saver(launch = { block -> waiting += block })
        save.request(enable = false, observed)
        assertTrue(save.pending)
        save.request(enable = true, observed)
        assertEquals("one save on its way", 1, waiting.size)

        runBlocking { waiting.single()() }
        assertFalse(save.pending)
        assertEquals(listOf(false), sent)
    }
}

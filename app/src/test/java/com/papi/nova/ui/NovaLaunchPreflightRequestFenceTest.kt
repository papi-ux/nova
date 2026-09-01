package com.papi.nova.ui

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaLaunchPreflightRequestFenceTest {

    @Test
    fun lateOlderResponseCannotPublishAfterNewerRequestStarts() {
        val fence = NovaLaunchPreflightRequestFence()
        val older = fence.begin()
        val newer = fence.begin()
        val published = mutableListOf<String>()

        // Deliberately complete the network responses out of order.
        if (fence.owns(newer)) published += "newer"
        if (fence.owns(older)) published += "older"

        assertEquals(listOf("newer"), published)
        assertTrue(fence.owns(newer))
        assertFalse(fence.owns(older))
    }

    @Test
    fun selectionSettleInvalidatesInFlightResponseBeforeReplacementStarts() {
        val fence = NovaLaunchPreflightRequestFence()
        val inFlight = fence.begin()

        fence.invalidate()

        assertFalse(fence.owns(inFlight))
        val replacement = fence.begin()
        assertTrue(fence.owns(replacement))
        assertFalse(fence.owns(inFlight))
    }

    @Test
    fun olderSteamWriteFinishesBeforeReplacementCanCommit() = runBlocking {
        val queue = NovaSteamLaunchModeWriteQueue()
        val olderStarted = CountDownLatch(1)
        val releaseOlder = CountDownLatch(1)
        val commits = CopyOnWriteArrayList<String>()

        val older = launch(Dispatchers.Default) {
            queue.commit {
                withContext(Dispatchers.IO) {
                    olderStarted.countDown()
                    check(releaseOlder.await(2, TimeUnit.SECONDS))
                    commits += "older"
                }
            }
        }
        assertTrue("older write did not start", olderStarted.await(2, TimeUnit.SECONDS))

        // A newer intent may queue while the host is still answering the old one, but
        // it cannot overtake that write and then be overwritten by it.
        val replacement = launch(Dispatchers.Default) {
            queue.commit { commits += "replacement" }
        }
        releaseOlder.countDown()

        older.join()
        replacement.join()
        assertEquals(listOf("older", "replacement"), commits)
    }

    @Test
    fun crossRowWorkCannotDiscardPendingSteamIntent() {
        val intents = NovaSteamLaunchModeIntentTracker(initialConfirmedMode = "direct")
        intents.select("big-picture")

        // Profile/topology work has no handle to this tracker. When it settles, the
        // independently owned Steam mutation is still exactly the pending intent.
        val afterUnrelatedRowChange = intents.snapshot()

        assertEquals("big-picture", afterUnrelatedRowChange?.mode)
        assertTrue(afterUnrelatedRowChange?.let(intents::owns) == true)
    }

    @Test
    fun supersededSteamCommitBecomesRollbackPointBeforeLatestFailure() {
        val intents = NovaSteamLaunchModeIntentTracker(initialConfirmedMode = "direct")
        intents.select("big-picture")
        val older = checkNotNull(intents.snapshot())

        intents.select("direct")
        val olderResult = intents.complete(older, hostConfirmedMode = "big-picture")
        val latest = checkNotNull(intents.snapshot())
        val latestResult = intents.complete(latest, hostConfirmedMode = null)

        assertFalse(olderResult.ownsLatest)
        assertEquals("direct", latest.mode)
        assertTrue(latestResult.ownsLatest)
        assertEquals("big-picture", latestResult.displayMode)
    }

    @Test
    fun retainedCoordinatorOrdersReplacementActivityAfterInFlightWrite() = runBlocking {
        val retainedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val olderStarted = CountDownLatch(1)
        val releaseOlder = CountDownLatch(1)
        val commits = CopyOnWriteArrayList<String>()
        val coordinator = NovaSteamLaunchModeCoordinator(
            scope = retainedScope,
            initialConfirmedMode = "direct",
            settleDelayMs = 0,
            write = { mode ->
                if (mode == "big-picture") {
                    olderStarted.countDown()
                    check(releaseOlder.await(2, TimeUnit.SECONDS))
                }
                commits += mode
                mode
            },
        )

        coordinator.select("big-picture")
        assertTrue("older write did not start", olderStarted.await(2, TimeUnit.SECONDS))

        // A replacement Activity gets the same retained coordinator. It sees the
        // optimistic choice and queues its new intent behind the already-delivered POST.
        assertEquals("big-picture", coordinator.snapshot().displayMode)
        val replacementGeneration = coordinator.select("direct")
        releaseOlder.countDown()

        val settled = coordinator.awaitLatest()
        assertEquals(listOf("big-picture", "direct"), commits)
        assertEquals(replacementGeneration, settled.generation)
        assertEquals("direct", settled.displayMode)
        assertFalse(settled.pending)
        assertFalse(settled.failed)
        retainedScope.cancel()
    }
}

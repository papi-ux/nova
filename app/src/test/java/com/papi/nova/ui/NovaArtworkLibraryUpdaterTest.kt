package com.papi.nova.ui

import com.papi.nova.api.PolarisArtworkUpdateResult
import com.papi.nova.api.PolarisArtworkUpdateStatus
import com.papi.nova.shared.polaris.model.PolarisGame
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaArtworkLibraryUpdaterTest {
    private fun game(id: String, custom: Boolean = false) = PolarisGame(
        id = id,
        artwork = PolarisGame.ArtworkManifest(
            override = PolarisGame.ArtworkOverride(active = custom),
        ),
    )

    private fun result(status: PolarisArtworkUpdateStatus) = PolarisArtworkUpdateResult(
        manifest = PolarisGame.ArtworkManifest(revision = status.name),
        status = status,
        requestedKinds = emptyList(),
        remainingKinds = emptyList(),
    )

    @Test
    fun boundsConcurrencyAndSkipsCustomArtwork() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val updater = NovaArtworkLibraryUpdater(parallelism = 2)
        val summary = updater.run((1..5).map { game("g$it", custom = it == 3) }) {
            calls.incrementAndGet()
            val current = active.incrementAndGet()
            maximumActive.updateAndGet { max -> maxOf(max, current) }
            delay(25)
            active.decrementAndGet()
            result(PolarisArtworkUpdateStatus.UPDATED)
        }

        assertEquals(4, calls.get())
        assertTrue(maximumActive.get() <= 2)
        assertEquals(5, summary.progress.completed)
        assertEquals(4, summary.progress.updated)
        assertEquals(1, summary.progress.customPreserved)
        assertTrue(summary.failedGameIds.isEmpty())
    }

    @Test
    fun reportsPartialAndThrownFailuresAsRetryableIds() = runBlocking {
        val updater = NovaArtworkLibraryUpdater()
        val summary = updater.run(listOf(game("healthy"), game("partial"), game("thrown"))) { item ->
            when (item.id) {
                "healthy" -> result(PolarisArtworkUpdateStatus.HEALTHY)
                "partial" -> result(PolarisArtworkUpdateStatus.PARTIAL_FAILURE)
                else -> error("fixed test failure")
            }
        }

        assertEquals(3, summary.progress.completed)
        assertEquals(1, summary.progress.healthy)
        assertEquals(2, summary.progress.failed)
        assertEquals(listOf("partial", "thrown"), summary.failedGameIds)
    }

    @Test
    fun cancellationPreventsQueuedGamesFromStarting() = runBlocking {
        val started = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            NovaArtworkLibraryUpdater(parallelism = 2).run((1..8).map { game("g$it") }) {
                started.incrementAndGet()
                gate.await()
                result(PolarisArtworkUpdateStatus.UPDATED)
            }
        }
        while (started.get() < 2) yield()
        job.cancelAndJoin()
        assertEquals(2, started.get())
    }
}

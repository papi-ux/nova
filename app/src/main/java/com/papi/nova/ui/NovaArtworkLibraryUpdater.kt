package com.papi.nova.ui

import com.papi.nova.api.PolarisArtworkUpdateResult
import com.papi.nova.api.PolarisArtworkUpdateStatus
import com.papi.nova.shared.polaris.model.PolarisGame

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class NovaArtworkLibraryUpdater(private val parallelism: Int = 2) {
    init {
        require(parallelism in 1..4)
    }

    data class Progress(
        val total: Int,
        val completed: Int,
        val updated: Int,
        val healthy: Int,
        val customPreserved: Int,
        val failed: Int,
    )

    data class Summary(
        val progress: Progress,
        val failedGameIds: List<String>,
    )

    private data class ItemResult(
        val gameId: String,
        val status: PolarisArtworkUpdateStatus?,
    )

    suspend fun run(
        games: List<PolarisGame>,
        onProgress: (Progress) -> Unit = {},
        update: suspend (PolarisGame) -> PolarisArtworkUpdateResult,
    ): Summary = coroutineScope {
        val uniqueGames = games.distinctBy { it.id }
        val customGames = uniqueGames.filter { it.artwork?.override?.active == true }
        val eligibleGames = uniqueGames.filterNot { it.artwork?.override?.active == true }
        val completed = AtomicInteger(customGames.size)
        val updated = AtomicInteger(0)
        val healthy = AtomicInteger(0)
        val customPreserved = AtomicInteger(customGames.size)
        val failed = AtomicInteger(0)
        val callbackLock = Any()
        fun snapshot() = Progress(
            total = uniqueGames.size,
            completed = completed.get(),
            updated = updated.get(),
            healthy = healthy.get(),
            customPreserved = customPreserved.get(),
            failed = failed.get(),
        )
        synchronized(callbackLock) { onProgress(snapshot()) }
        val semaphore = Semaphore(parallelism)
        val results = eligibleGames.map { game ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val status = try {
                        update(game).status
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    when (status) {
                        PolarisArtworkUpdateStatus.UPDATED -> updated.incrementAndGet()
                        PolarisArtworkUpdateStatus.HEALTHY -> healthy.incrementAndGet()
                        PolarisArtworkUpdateStatus.CUSTOM_PRESERVED -> customPreserved.incrementAndGet()
                        PolarisArtworkUpdateStatus.PARTIAL_FAILURE, null -> failed.incrementAndGet()
                    }
                    completed.incrementAndGet()
                    synchronized(callbackLock) { onProgress(snapshot()) }
                    ItemResult(game.id, status)
                }
            }
        }.awaitAll()
        Summary(
            progress = snapshot(),
            failedGameIds = results.filter {
                it.status == null || it.status == PolarisArtworkUpdateStatus.PARTIAL_FAILURE
            }.map { it.gameId },
        )
    }
}

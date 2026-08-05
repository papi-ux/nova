package com.papi.nova.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisArtworkChoice
import com.papi.nova.api.PolarisArtworkLibraryUpdateUnavailableException
import com.papi.nova.api.PolarisArtworkMatchCandidate
import com.papi.nova.api.PolarisArtworkUpdateResult
import com.papi.nova.api.PolarisArtworkUpdateStatus
import com.papi.nova.shared.polaris.model.PolarisGame

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class NovaArtworkLibraryUpdater(
    private val parallelism: Int = 2,
    private val onWorkerStarted: () -> Unit = {},
) {
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
        onProgress: suspend (Progress) -> Unit = {},
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
        val callbackLock = Mutex()
        fun snapshot() = Progress(
            total = uniqueGames.size,
            completed = completed.get(),
            updated = updated.get(),
            healthy = healthy.get(),
            customPreserved = customPreserved.get(),
            failed = failed.get(),
        )
        callbackLock.withLock { onProgress(snapshot()) }
        val results = arrayOfNulls<ItemResult>(eligibleGames.size)
        val workerCount = minOf(parallelism, eligibleGames.size)
        val queue = kotlinx.coroutines.channels.Channel<IndexedValue<PolarisGame>>(
            capacity = maxOf(1, workerCount),
        )
        val producer = launch(Dispatchers.IO) {
            try {
                eligibleGames.withIndex().forEach { queue.send(it) }
            } finally {
                queue.close()
            }
        }
        val workers = List(workerCount) {
            async(Dispatchers.IO) {
                onWorkerStarted()
                for ((gameIndex, game) in queue) {
                    currentCoroutineContext().ensureActive()
                    val status = try {
                        update(game).status
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: PolarisArtworkLibraryUpdateUnavailableException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    withContext(NonCancellable) {
                        callbackLock.withLock {
                            when (status) {
                                PolarisArtworkUpdateStatus.UPDATED -> updated.incrementAndGet()
                                PolarisArtworkUpdateStatus.HEALTHY -> healthy.incrementAndGet()
                                PolarisArtworkUpdateStatus.CUSTOM_PRESERVED ->
                                    customPreserved.incrementAndGet()
                                PolarisArtworkUpdateStatus.PARTIAL_FAILURE, null ->
                                    failed.incrementAndGet()
                            }
                            completed.incrementAndGet()
                            onProgress(snapshot())
                        }
                    }
                    results[gameIndex] = ItemResult(game.id, status)
                }
            }
        }
        workers.awaitAll()
        producer.join()
        Summary(
            progress = snapshot(),
            failedGameIds = results.filterNotNull().filter {
                it.status == null || it.status == PolarisArtworkUpdateStatus.PARTIAL_FAILURE
            }.map { it.gameId },
        )
    }
}

internal enum class NovaArtworkLibraryUpdateFailure {
    SERVER_CAPABILITY_UNAVAILABLE,
    UNEXPECTED,
}

internal sealed interface NovaArtworkLibraryUpdateUiState {
    data object Idle : NovaArtworkLibraryUpdateUiState
    data class Running(
        val progress: NovaArtworkLibraryUpdater.Progress,
        val cancelling: Boolean = false,
    ) : NovaArtworkLibraryUpdateUiState
    data class Complete(val summary: NovaArtworkLibraryUpdater.Summary) : NovaArtworkLibraryUpdateUiState
    data class Cancelled(val progress: NovaArtworkLibraryUpdater.Progress) : NovaArtworkLibraryUpdateUiState
    data class Failed(
        val progress: NovaArtworkLibraryUpdater.Progress,
        val reason: NovaArtworkLibraryUpdateFailure = NovaArtworkLibraryUpdateFailure.UNEXPECTED,
    ) : NovaArtworkLibraryUpdateUiState
}

internal data class NovaArtworkLibraryUpdateSnapshot(
    val state: NovaArtworkLibraryUpdateUiState = NovaArtworkLibraryUpdateUiState.Idle,
    val committedArtwork: Map<String, PolarisGame.ArtworkManifest> = emptyMap(),
)

internal data class NovaArtworkLibraryRefreshToken(
    val id: Long,
    val startedAtPublication: Long,
)

internal enum class NovaArtworkMutationOwner {
    BATCH,
    STUDIO,
}

internal data class NovaArtworkMutationToken(
    val gameId: String,
    val generation: Long,
    val owner: NovaArtworkMutationOwner,
    val studioAdmissionGeneration: Long,
)

internal class NovaArtworkLibraryUpdateCoordinator(
    private val scope: CoroutineScope,
    parallelism: Int = 2,
    private val update: suspend (PolarisGame) -> PolarisArtworkUpdateResult,
    private val clearArtworkCache: () -> Unit = {},
    private val onOwnershipAssignedBeforeStart: () -> Unit = {},
    private val onCancelOwnershipLocked: () -> Unit = {},
    private val onStartAdmissionAttempt: () -> Unit = {},
    private val onCancelAdmissionAttempt: () -> Unit = {},
    private val onArtworkMutationAdmissionAttempt: (String, NovaArtworkMutationOwner) -> Unit = { _, _ -> },
) {
    private data class CommittedArtwork(
        val sequence: Long,
        val manifest: PolarisGame.ArtworkManifest,
    )

    private val updater = NovaArtworkLibraryUpdater(parallelism)
    private val runLock = Any()
    private val publicationLock = Any()
    private val committedArtwork = linkedMapOf<String, CommittedArtwork>()
    private val latestArtworkMutationByGameId = linkedMapOf<String, Long>()
    private val latestStudioAdmissionByGameId = linkedMapOf<String, Long>()
    private val artworkMutationLocks = linkedMapOf<String, Mutex>()
    private val studioOverrideProtectedArtworkByGameId =
        linkedMapOf<String, PolarisGame.ArtworkManifest>()
    private val activeRefreshes = linkedMapOf<Long, Long>()
    private val _snapshot = MutableStateFlow(NovaArtworkLibraryUpdateSnapshot())
    val snapshot: StateFlow<NovaArtworkLibraryUpdateSnapshot> = _snapshot.asStateFlow()

    private var activeJob: Job? = null
    private var publicationSequence = 0L
    private var artworkMutationSequence = 0L
    private var studioAdmissionSequence = 0L
    private var refreshSequence = 0L
    private var acknowledgedPublicationSequence = 0L

    fun start(games: List<PolarisGame>): Boolean {
        val selectedGames = games.distinctBy { it.id }
        if (selectedGames.isEmpty()) return false
        val initialProgress = NovaArtworkLibraryUpdater.Progress(
            total = selectedGames.size,
            completed = 0,
            updated = 0,
            healthy = 0,
            customPreserved = 0,
            failed = 0,
        )
        val latestProgress = AtomicReference(initialProgress)
        lateinit var launched: Job
        onStartAdmissionAttempt()
        synchronized(runLock) {
            if (activeJob != null) return false
            _snapshot.update {
                it.copy(state = NovaArtworkLibraryUpdateUiState.Running(initialProgress))
            }
            launched = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val summary = updater.run(
                        games = selectedGames,
                        onProgress = { progress ->
                            latestProgress.set(progress)
                            _snapshot.update { current ->
                                val cancelling =
                                    (current.state as? NovaArtworkLibraryUpdateUiState.Running)
                                        ?.cancelling == true
                                current.copy(
                                    state = NovaArtworkLibraryUpdateUiState.Running(
                                        progress = progress,
                                        cancelling = cancelling,
                                    ),
                                )
                            }
                        },
                    ) { game ->
                        withArtworkMutation(game.id, NovaArtworkMutationOwner.BATCH) { mutation ->
                            studioProtectedArtwork(game.id)?.let { protectedArtwork ->
                                return@withArtworkMutation PolarisArtworkUpdateResult(
                                    manifest = protectedArtwork,
                                    status = PolarisArtworkUpdateStatus.CUSTOM_PRESERVED,
                                    requestedKinds = emptyList(),
                                    remainingKinds = emptyList(),
                                )
                            }
                            val result = update(game)
                            val published = withContext(NonCancellable) {
                                publishCommittedArtwork(mutation, result.manifest).also { accepted ->
                                    if (accepted) clearArtworkCache()
                                }
                            }
                            if (!published && isBatchSuperseded(mutation)) {
                                result.copy(status = PolarisArtworkUpdateStatus.CUSTOM_PRESERVED)
                            } else {
                                result
                            }
                        }
                    }
                    _snapshot.update {
                        it.copy(state = NovaArtworkLibraryUpdateUiState.Complete(summary))
                    }
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        _snapshot.update {
                            it.copy(
                                state = NovaArtworkLibraryUpdateUiState.Cancelled(latestProgress.get()),
                            )
                        }
                    }
                } catch (failure: Exception) {
                    val reason = if (failure is PolarisArtworkLibraryUpdateUnavailableException) {
                        NovaArtworkLibraryUpdateFailure.SERVER_CAPABILITY_UNAVAILABLE
                    } else {
                        NovaArtworkLibraryUpdateFailure.UNEXPECTED
                    }
                    withContext(NonCancellable) {
                        _snapshot.update {
                            it.copy(state = NovaArtworkLibraryUpdateUiState.Failed(latestProgress.get(), reason))
                        }
                    }
                }
            }
            activeJob = launched
            launched.invokeOnCompletion { cause ->
                synchronized(runLock) {
                    if (activeJob === launched) {
                        if (cause is CancellationException) {
                            _snapshot.update {
                                it.copy(
                                    state = NovaArtworkLibraryUpdateUiState.Cancelled(
                                        latestProgress.get(),
                                    ),
                                )
                            }
                        }
                        activeJob = null
                    }
                }
            }
            onOwnershipAssignedBeforeStart()
            launched.start()
        }
        return true
    }

    fun cancel(): Boolean {
        onCancelAdmissionAttempt()
        return synchronized(runLock) {
            val job = activeJob ?: return@synchronized false
            onCancelOwnershipLocked()
            _snapshot.update { current ->
                val running = current.state as? NovaArtworkLibraryUpdateUiState.Running
                    ?: return@update current
                current.copy(state = running.copy(cancelling = true))
            }
            job.cancel()
            true
        }
    }

    fun beginRefresh(): NovaArtworkLibraryRefreshToken = synchronized(publicationLock) {
        refreshSequence += 1
        NovaArtworkLibraryRefreshToken(
            id = refreshSequence,
            startedAtPublication = publicationSequence,
        ).also { activeRefreshes[it.id] = it.startedAtPublication }
    }

    fun publishRefresh(
        token: NovaArtworkLibraryRefreshToken,
        games: List<PolarisGame>,
        publish: (List<PolarisGame>) -> Unit,
    ): Boolean = synchronized(publicationLock) {
        val refreshStartedAt = activeRefreshes[token.id]
            ?: return@synchronized false
        if (token.id != refreshSequence) {
            activeRefreshes.remove(token.id)
            return@synchronized false
        }
        val newerCommits = committedArtwork
            .filterValues { it.sequence > refreshStartedAt }
            .mapValues { it.value.manifest }
        val merged = mergeArtwork(games, newerCommits)
        try {
            publish(merged)
        } finally {
            activeRefreshes.remove(token.id)
        }
        reconcileStudioProtection(merged)
        acknowledgedPublicationSequence = maxOf(
            acknowledgedPublicationSequence,
            refreshStartedAt,
        )
        pruneAcknowledgedCommits()
        true
    }

    fun discardRefresh(token: NovaArtworkLibraryRefreshToken): Boolean = synchronized(publicationLock) {
        val wasActive = activeRefreshes.remove(token.id) != null
        wasActive && token.id == refreshSequence
    }

    fun mergeCommittedArtwork(games: List<PolarisGame>): List<PolarisGame> =
        mergeArtwork(games, snapshot.value.committedArtwork)

    private fun reconcileStudioProtection(games: List<PolarisGame>) {
        games.forEach { game ->
            val manifest = game.artwork
            if (manifest?.override?.active == true) {
                studioOverrideProtectedArtworkByGameId[game.id] = manifest
            } else {
                studioOverrideProtectedArtworkByGameId.remove(game.id)
            }
        }
    }

    private fun pruneAcknowledgedCommits() {
        val iterator = committedArtwork.entries.iterator()
        while (iterator.hasNext()) {
            val committed = iterator.next().value
            if (committed.sequence <= acknowledgedPublicationSequence) {
                iterator.remove()
            }
        }
        _snapshot.update { it.copy(committedArtwork = committedManifests()) }
    }

    private fun committedManifests(): Map<String, PolarisGame.ArtworkManifest> =
        committedArtwork.mapValues { it.value.manifest }

    private fun studioProtectedArtwork(gameId: String): PolarisGame.ArtworkManifest? =
        synchronized(publicationLock) {
            studioOverrideProtectedArtworkByGameId[gameId]
        }

    internal suspend fun <T> withArtworkMutation(
        gameId: String,
        owner: NovaArtworkMutationOwner,
        mutate: suspend (NovaArtworkMutationToken) -> T,
    ): T {
        val lock = synchronized(publicationLock) {
            if (owner == NovaArtworkMutationOwner.STUDIO) {
                studioAdmissionSequence += 1
                latestStudioAdmissionByGameId[gameId] = studioAdmissionSequence
            }
            artworkMutationLocks.getOrPut(gameId) { Mutex() }
        }
        onArtworkMutationAdmissionAttempt(gameId, owner)
        return lock.withLock {
            mutate(beginArtworkMutation(gameId, owner))
        }
    }

    private fun beginArtworkMutation(
        gameId: String,
        owner: NovaArtworkMutationOwner,
    ): NovaArtworkMutationToken = synchronized(publicationLock) {
        artworkMutationSequence += 1
        NovaArtworkMutationToken(
            gameId = gameId,
            generation = artworkMutationSequence,
            owner = owner,
            studioAdmissionGeneration = latestStudioAdmissionByGameId[gameId] ?: 0L,
        ).also { token ->
            latestArtworkMutationByGameId[gameId] = token.generation
        }
    }

    private fun isBatchSuperseded(mutation: NovaArtworkMutationToken): Boolean =
        synchronized(publicationLock) {
            mutation.owner == NovaArtworkMutationOwner.BATCH &&
                (latestStudioAdmissionByGameId[mutation.gameId] ?: 0L) >
                mutation.studioAdmissionGeneration
        }

    internal fun publishCommittedArtwork(
        mutation: NovaArtworkMutationToken,
        manifest: PolarisGame.ArtworkManifest,
    ): Boolean = synchronized(publicationLock) {
        if (latestArtworkMutationByGameId[mutation.gameId] != mutation.generation) {
            return@synchronized false
        }
        if (isBatchSuperseded(mutation)) {
            return@synchronized false
        }
        if (
            mutation.owner == NovaArtworkMutationOwner.BATCH &&
            mutation.gameId in studioOverrideProtectedArtworkByGameId
        ) {
            return@synchronized false
        }
        if (mutation.owner == NovaArtworkMutationOwner.STUDIO) {
            if (manifest.override?.active == true) {
                studioOverrideProtectedArtworkByGameId[mutation.gameId] = manifest
            } else {
                studioOverrideProtectedArtworkByGameId.remove(mutation.gameId)
            }
        }
        publicationSequence += 1
        committedArtwork[mutation.gameId] = CommittedArtwork(publicationSequence, manifest)
        val manifests = committedArtwork.mapValues { it.value.manifest }
        _snapshot.update { it.copy(committedArtwork = manifests) }
        true
    }

    private fun mergeArtwork(
        games: List<PolarisGame>,
        artworkByGameId: Map<String, PolarisGame.ArtworkManifest>,
    ): List<PolarisGame> = games.map { game ->
        artworkByGameId[game.id]?.let { game.copy(artwork = it) } ?: game
    }
}

sealed interface NovaArtworkMutationResult {
    data class Committed(val game: PolarisGame) : NovaArtworkMutationResult
    object Rejected : NovaArtworkMutationResult
    object Failed : NovaArtworkMutationResult
}

internal class NovaArtworkLibraryUpdateViewModel(
    private val apiClient: PolarisApiClient,
) : ViewModel() {
    private val coordinator = NovaArtworkLibraryUpdateCoordinator(
        scope = viewModelScope,
        parallelism = 2,
        update = { game -> apiClient.updateArtworkForLibrary(game.id) },
        clearArtworkCache = apiClient::clearCoverCache,
    )

    val snapshot: StateFlow<NovaArtworkLibraryUpdateSnapshot> = coordinator.snapshot

    fun start(games: List<PolarisGame>): Boolean = coordinator.start(games)
    fun cancel(): Boolean = coordinator.cancel()
    fun beginRefresh(): NovaArtworkLibraryRefreshToken = coordinator.beginRefresh()
    fun publishRefresh(
        token: NovaArtworkLibraryRefreshToken,
        games: List<PolarisGame>,
        publish: (List<PolarisGame>) -> Unit,
    ): Boolean = coordinator.publishRefresh(token, games, publish)
    fun discardRefresh(token: NovaArtworkLibraryRefreshToken): Boolean =
        coordinator.discardRefresh(token)
    fun mergeCommittedArtwork(games: List<PolarisGame>): List<PolarisGame> =
        coordinator.mergeCommittedArtwork(games)

    fun refreshArtwork(
        game: PolarisGame,
        onResult: (NovaArtworkMutationResult) -> Unit,
    ): Job = launchArtworkMutation(game, onResult) {
        apiClient.resolveArtwork(game.id, force = true)
    }

    fun applyArtworkSelections(
        game: PolarisGame,
        candidate: PolarisArtworkMatchCandidate,
        selections: Map<String, PolarisArtworkChoice>,
        onResult: (NovaArtworkMutationResult) -> Unit,
    ): Job = launchArtworkMutation(game, onResult) {
        apiClient.applyArtworkSelections(game.id, candidate, selections)
    }

    fun clearArtworkOverride(
        game: PolarisGame,
        onResult: (NovaArtworkMutationResult) -> Unit,
    ): Job = launchArtworkMutation(game, onResult) {
        apiClient.clearArtworkOverride(game.id)
    }

    private fun launchArtworkMutation(
        game: PolarisGame,
        onResult: (NovaArtworkMutationResult) -> Unit,
        mutate: () -> PolarisGame.ArtworkManifest?,
    ): Job = viewModelScope.launch {
        val result = try {
            coordinator.withArtworkMutation(game.id, NovaArtworkMutationOwner.STUDIO) { mutation ->
                val manifest = withContext(Dispatchers.IO) { mutate() }
                if (manifest == null) {
                    NovaArtworkMutationResult.Rejected
                } else {
                    val committed = withContext(NonCancellable) {
                        coordinator.publishCommittedArtwork(mutation, manifest).also { accepted ->
                            if (accepted) apiClient.clearCoverCache()
                        }
                    }
                    if (committed) {
                        NovaArtworkMutationResult.Committed(game.copy(artwork = manifest))
                    } else {
                        NovaArtworkMutationResult.Rejected
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            NovaArtworkMutationResult.Failed
        }
        onResult(result)
    }

    internal class Factory(
        context: Context,
        private val serverAddress: String,
        private val httpsPort: Int,
        serverCertDer: ByteArray?,
    ) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext
        private val serverCertificate = serverCertDer?.copyOf()

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NovaArtworkLibraryUpdateViewModel::class.java))
            val client = PolarisApiClient(
                applicationContext,
                serverAddress,
                httpsPort,
                serverCertificate,
            )
            return NovaArtworkLibraryUpdateViewModel(client) as T
        }
    }
}

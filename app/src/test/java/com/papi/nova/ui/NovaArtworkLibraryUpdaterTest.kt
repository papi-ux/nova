package com.papi.nova.ui

import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisArtworkLibraryUpdateUnavailableException
import com.papi.nova.api.PolarisArtworkUpdateResult
import com.papi.nova.api.PolarisArtworkUpdateStatus
import com.papi.nova.shared.polaris.model.PolarisGame
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun incompatiblePolarisContractStopsWithoutPerGameFailures() = runBlocking {
        val calls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = NovaArtworkLibraryUpdateCoordinator(scope = scope, update = {
            calls.incrementAndGet()
            throw PolarisArtworkLibraryUpdateUnavailableException()
        })
        assertTrue(coordinator.start((1..8).map { game("g$it") }))
        val failed = withTimeout(2_000) {
            coordinator.snapshot.first {
                it.state is NovaArtworkLibraryUpdateUiState.Failed
            }.state as NovaArtworkLibraryUpdateUiState.Failed
        }
        assertEquals(NovaArtworkLibraryUpdateFailure.SERVER_CAPABILITY_UNAVAILABLE, failed.reason)
        assertEquals(0, failed.progress.failed)
        assertTrue(calls.get() in 1..2)
        scope.cancel()
    }

    @Test
    fun everyPublishedProgressSnapshotHasAtomicOutcomeAccounting() = runBlocking {
        val progress = CopyOnWriteArrayList<NovaArtworkLibraryUpdater.Progress>()
        val statuses = listOf(
            PolarisArtworkUpdateStatus.UPDATED,
            PolarisArtworkUpdateStatus.HEALTHY,
            PolarisArtworkUpdateStatus.CUSTOM_PRESERVED,
            PolarisArtworkUpdateStatus.PARTIAL_FAILURE,
        )
        val summary = NovaArtworkLibraryUpdater(parallelism = 4).run(
            games = (1..64).map { game("g$it") },
            onProgress = { progress += it },
        ) { item ->
            result(statuses[(item.id.removePrefix("g").toInt() - 1) % statuses.size])
        }

        (progress + summary.progress).forEach { published ->
            assertEquals(
                "every progress callback must publish one coherent accounting snapshot",
                published.completed,
                published.updated + published.healthy +
                    published.customPreserved + published.failed,
            )
        }
    }

    @Test
    fun alreadyCancelledParentCannotRetainOwnership() = runBlocking {
        val parent = SupervisorJob().apply { cancel() }
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            update = { error("cancelled parent must not invoke update") },
        )
        assertTrue(coordinator.start(listOf(game("a"))))
        assertTrue(coordinator.snapshot.value.state is NovaArtworkLibraryUpdateUiState.Cancelled)
        assertTrue(coordinator.start(listOf(game("b"))))
    }

    @Test
    fun cancellationCannotEnterOwnershipBeforeLazyJobStarts() = runBlocking {
        val assigned = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val cancelAttempted = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            update = { kotlinx.coroutines.awaitCancellation() },
            onOwnershipAssignedBeforeStart = {
                assigned.countDown()
                releaseStart.await()
            },
            onCancelAdmissionAttempt = { cancelAttempted.countDown() },
        )
        val startThread = Thread { coordinator.start(listOf(game("a"))) }
        startThread.start()
        assertTrue(assigned.await(2, TimeUnit.SECONDS))
        val cancelThread = Thread {
            coordinator.cancel()
            cancelReturned.countDown()
        }
        cancelThread.start()
        assertTrue(cancelAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(cancelReturned.await(100, TimeUnit.MILLISECONDS))
        releaseStart.countDown()
        assertTrue(cancelReturned.await(2, TimeUnit.SECONDS))
        startThread.join()
        cancelThread.join()
        withTimeout(2_000) {
            coordinator.snapshot.first { it.state is NovaArtworkLibraryUpdateUiState.Cancelled }
            while (!coordinator.start(listOf(game("b")))) yield()
        }
        assertTrue(coordinator.cancel())
        scope.cancel()
    }

    @Test
    fun cancellationAfterHandlerRegistrationBeforeBodyStartReleasesOwnership() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cancelled = AtomicReference<Boolean>()
        lateinit var coordinator: NovaArtworkLibraryUpdateCoordinator
        coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            update = { error("pre-start cancellation must not enter the job body") },
            onOwnershipAssignedBeforeStart = {
                cancelled.set(coordinator.cancel())
            },
        )

        assertTrue(coordinator.start(listOf(game("old"))))
        assertEquals(true, cancelled.get())
        assertTrue(coordinator.snapshot.value.state is NovaArtworkLibraryUpdateUiState.Cancelled)
        assertTrue(coordinator.start(listOf(game("replacement"))))
        scope.cancel()
    }

    @Test
    fun cancellationLockCannotMutateAReplacementGeneration() = runBlocking {
        val updateEntered = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val cancelLocked = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val replacement = AtomicReference<Boolean>()
        val replacementAttempted = CountDownLatch(1)
        val replacementReturned = CountDownLatch(1)
        val startAttempts = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            update = {
                updateEntered.countDown()
                releaseUpdate.await()
                result(PolarisArtworkUpdateStatus.UPDATED)
            },
            onCancelOwnershipLocked = {
                cancelLocked.countDown()
                releaseCancel.await()
            },
            onStartAdmissionAttempt = {
                if (startAttempts.incrementAndGet() == 2) replacementAttempted.countDown()
            },
        )
        assertTrue(coordinator.start(listOf(game("old"))))
        assertTrue(updateEntered.await(2, TimeUnit.SECONDS))
        val cancelThread = Thread { coordinator.cancel() }.apply { start() }
        assertTrue(cancelLocked.await(2, TimeUnit.SECONDS))
        releaseUpdate.countDown()
        val replacementThread = Thread {
            replacement.set(coordinator.start(listOf(game("new"))))
            replacementReturned.countDown()
        }.apply { start() }
        assertTrue(replacementAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(replacementReturned.await(100, TimeUnit.MILLISECONDS))
        releaseCancel.countDown()
        cancelThread.join()
        replacementThread.join()
        withTimeout(2_000) {
            if (replacement.get() != true) {
                while (!coordinator.start(listOf(game("new")))) yield()
            }
            coordinator.snapshot.first { it.state is NovaArtworkLibraryUpdateUiState.Complete }
        }
        assertFalse(
            (coordinator.snapshot.value.state as? NovaArtworkLibraryUpdateUiState.Running)
                ?.cancelling == true
        )
        scope.cancel()
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

    @Test
    fun cancellationReportsAlreadyCommittedInFlightResults() = runBlocking {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val latest = AtomicReference<NovaArtworkLibraryUpdater.Progress>()
        val job = launch {
            NovaArtworkLibraryUpdater(parallelism = 2).run(
                games = (1..6).map { game("g$it") },
                onProgress = { latest.set(it) },
            ) {
                started.countDown()
                release.await()
                result(PolarisArtworkUpdateStatus.UPDATED)
            }
        }

        yield()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()

        assertEquals(2, latest.get().completed)
        assertEquals(2, latest.get().updated)
    }

    @Test
    fun allGamePaginationCollectsMultiplePagesDeduplicatesAndStopsOnShortPage() {
        val offsets = mutableListOf<Int>()
        val games = PolarisApiClient.paginateAllGames(pageSize = 2) { offset ->
            offsets += offset
            when (offset) {
                0 -> listOf(game("g1"), game("g2"))
                2 -> listOf(game("g2"), game("g3"))
                4 -> listOf(game("g4"))
                else -> throw AssertionError("unexpected offset $offset")
            }
        }

        assertEquals(listOf(0, 2, 4), offsets)
        assertEquals(listOf("g1", "g2", "g3", "g4"), games.map { it.id })
    }

    @Test
    fun allGamePaginationRejectsAFullPageThatAddsNoNewIds() {
        val offsets = mutableListOf<Int>()
        try {
            PolarisApiClient.paginateAllGames(pageSize = 2) { offset ->
                offsets += offset
                listOf(game("g1"), game("g2"))
            }
            fail("expected non-advancing full page to fail closed")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("no progress"))
        }

        assertEquals(listOf(0, 2), offsets)
    }

    @Test
    fun allGamePaginationPropagatesSecondPageFailure() {
        val firstPage = listOf(game("g1"), game("g2"))
        try {
            PolarisApiClient.paginateAllGames(pageSize = 2) { offset ->
                if (offset == 0) firstPage else throw IOException("fixed page failure")
            }
            fail("second-page failure must abort all-games collection")
        } catch (expected: IOException) {
            assertEquals("fixed page failure", expected.message)
        }
    }

    @Test
    fun allGamePaginationPropagatesCancellation() {
        val cancellation = CancellationException("fixed cancellation")
        try {
            PolarisApiClient.paginateAllGames(pageSize = 2) { throw cancellation }
            fail("cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun retainedCoordinatorRejectsReplacementStartUntilActiveCallsSettle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            parallelism = 2,
            update = {
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                started.countDown()
                try {
                    release.await()
                    result(PolarisArtworkUpdateStatus.UPDATED)
                } finally {
                    active.decrementAndGet()
                }
            },
        )
        try {
            assertTrue(coordinator.start((1..6).map { game("first-$it") }))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(coordinator.cancel())
            assertFalse(coordinator.start((1..6).map { game("replacement-$it") }))
            assertTrue(maximumActive.get() <= 2)
            release.countDown()
            withTimeout(5_000) {
                while (coordinator.snapshot.value.state !is NovaArtworkLibraryUpdateUiState.Cancelled) {
                    delay(10)
                }
            }
            withTimeout(5_000) {
                while (!coordinator.start((1..6).map { game("replacement-$it") })) {
                    delay(10)
                }
            }
            withTimeout(5_000) {
                while (coordinator.snapshot.value.state !is NovaArtworkLibraryUpdateUiState.Complete) {
                    delay(10)
                }
            }
            assertTrue(maximumActive.get() <= 2)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun overlappingRefreshesRetainCommittedArtworkUntilEveryOlderSnapshotPublishes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val oldManifest = PolarisGame.ArtworkManifest(revision = "old")
        val newManifest = PolarisGame.ArtworkManifest(revision = "new")
        val staleRefresh = listOf(game("g1").copy(artwork = oldManifest))
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            parallelism = 2,
            update = {
                release.await()
                PolarisArtworkUpdateResult(
                    manifest = newManifest,
                    status = PolarisArtworkUpdateStatus.UPDATED,
                    requestedKinds = emptyList(),
                    remainingKinds = emptyList(),
                )
            },
        )
        try {
            val slowerRefresh = coordinator.beginRefresh()
            val fasterRefresh = coordinator.beginRefresh()
            assertTrue(coordinator.start(staleRefresh))
            release.complete(Unit)
            withTimeout(5_000) {
                while (!coordinator.snapshot.value.committedArtwork.containsKey("g1")) delay(10)
            }

            var fasterPublished: List<PolarisGame>? = null
            assertTrue(
                coordinator.publishRefresh(fasterRefresh, staleRefresh) { published ->
                    fasterPublished = published
                },
            )
            assertEquals("new", fasterPublished?.single()?.artwork?.revision)
            assertTrue(coordinator.snapshot.value.committedArtwork.containsKey("g1"))

            assertFalse(
                coordinator.publishRefresh(slowerRefresh, staleRefresh) {
                    fail("older refresh must not publish")
                },
            )
            assertTrue(coordinator.snapshot.value.committedArtwork.containsKey("g1"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun committedArtworkWinsOverAnOlderDelayedRefreshSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val oldManifest = PolarisGame.ArtworkManifest(revision = "old")
        val newManifest = PolarisGame.ArtworkManifest(revision = "new")
        val staleRefresh = listOf(game("g1").copy(artwork = oldManifest))
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            parallelism = 2,
            update = {
                release.await()
                PolarisArtworkUpdateResult(
                    manifest = newManifest,
                    status = PolarisArtworkUpdateStatus.UPDATED,
                    requestedKinds = emptyList(),
                    remainingKinds = emptyList(),
                )
            },
        )
        try {
            assertTrue(coordinator.start(staleRefresh))
            release.complete(Unit)
            withTimeout(5_000) {
                while (!coordinator.snapshot.value.committedArtwork.containsKey("g1")) {
                    delay(10)
                }
            }
            val merged = coordinator.mergeCommittedArtwork(staleRefresh)
            assertEquals("new", merged.single().artwork?.revision)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun newerStudioMutationRejectsAnOlderDelayedBatchCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val batchStarted = CompletableDeferred<Unit>()
        val releaseBatch = CompletableDeferred<Unit>()
        val batchManifest = PolarisGame.ArtworkManifest(revision = "older-batch")
        val studioManifest = PolarisGame.ArtworkManifest(
            revision = "newer-studio",
            override = PolarisGame.ArtworkOverride(active = true),
        )
        val studioAdmission = CompletableDeferred<Unit>()
        val studioOwned = CompletableDeferred<Unit>()
        val releaseStudioPublication = CompletableDeferred<Unit>()
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            parallelism = 1,
            update = {
                batchStarted.complete(Unit)
                releaseBatch.await()
                PolarisArtworkUpdateResult(
                    manifest = batchManifest,
                    status = PolarisArtworkUpdateStatus.UPDATED,
                    requestedKinds = emptyList(),
                    remainingKinds = emptyList(),
                )
            },
            onArtworkMutationAdmissionAttempt = { _, owner ->
                if (owner == NovaArtworkMutationOwner.STUDIO) {
                    studioAdmission.complete(Unit)
                }
            },
        )
        try {
            assertTrue(coordinator.start(listOf(game("g1"))))
            batchStarted.await()
            val studioCommit = scope.async {
                coordinator.withArtworkMutation("g1", NovaArtworkMutationOwner.STUDIO) { mutation ->
                    studioOwned.complete(Unit)
                    releaseStudioPublication.await()
                    coordinator.publishCommittedArtwork(mutation, studioManifest)
                }
            }
            studioAdmission.await()
            assertFalse(studioCommit.isCompleted)
            releaseBatch.complete(Unit)
            studioOwned.await()
            withTimeout(5_000) {
                while (coordinator.snapshot.value.state !is NovaArtworkLibraryUpdateUiState.Complete) {
                    delay(10)
                }
            }
            val summary = (coordinator.snapshot.value.state as NovaArtworkLibraryUpdateUiState.Complete).summary
            assertEquals(0, summary.progress.updated)
            assertEquals(1, summary.progress.customPreserved)
            assertFalse(coordinator.snapshot.value.committedArtwork.containsKey("g1"))
            releaseStudioPublication.complete(Unit)
            assertTrue(studioCommit.await())
            assertEquals(
                "newer-studio",
                coordinator.snapshot.value.committedArtwork.getValue("g1").revision,
            )
            assertEquals(
                "newer-studio",
                coordinator.mergeCommittedArtwork(listOf(game("g1"))).single().artwork?.revision,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun studioFirstMutationKeepsPriorityOverAQueuedUpdater() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val studioLocked = CompletableDeferred<Unit>()
        val releaseStudio = CompletableDeferred<Unit>()
        val batchAdmission = CompletableDeferred<Unit>()
        val batchNetworkStarted = CompletableDeferred<Unit>()
        val studioManifest = PolarisGame.ArtworkManifest(
            revision = "studio-first",
            override = PolarisGame.ArtworkOverride(active = true),
        )
        val batchManifest = PolarisGame.ArtworkManifest(revision = "queued-batch")
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            parallelism = 1,
            update = {
                batchNetworkStarted.complete(Unit)
                PolarisArtworkUpdateResult(
                    manifest = batchManifest,
                    status = PolarisArtworkUpdateStatus.UPDATED,
                    requestedKinds = emptyList(),
                    remainingKinds = emptyList(),
                )
            },
            onArtworkMutationAdmissionAttempt = { _, owner ->
                if (owner == NovaArtworkMutationOwner.BATCH) {
                    batchAdmission.complete(Unit)
                }
            },
        )
        try {
            val studioCommit = scope.async {
                coordinator.withArtworkMutation("g1", NovaArtworkMutationOwner.STUDIO) { mutation ->
                    studioLocked.complete(Unit)
                    releaseStudio.await()
                    coordinator.publishCommittedArtwork(mutation, studioManifest)
                }
            }
            studioLocked.await()
            assertTrue(coordinator.start(listOf(game("g1"))))
            batchAdmission.await()
            assertFalse(batchNetworkStarted.isCompleted)
            releaseStudio.complete(Unit)
            assertTrue(studioCommit.await())
            withTimeout(5_000) {
                while (coordinator.snapshot.value.state !is NovaArtworkLibraryUpdateUiState.Complete) {
                    delay(10)
                }
            }
            assertFalse(batchNetworkStarted.isCompleted)
            val summary = (coordinator.snapshot.value.state as NovaArtworkLibraryUpdateUiState.Complete).summary
            assertEquals(1, summary.progress.customPreserved)
            assertEquals(
                "studio-first",
                coordinator.snapshot.value.committedArtwork.getValue("g1").revision,
            )
            assertEquals(
                "studio-first",
                coordinator.mergeCommittedArtwork(listOf(game("g1"))).single().artwork?.revision,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun successfulStudioClearReleasesBatchPriority() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val batchStarted = CompletableDeferred<Unit>()
        val batchManifest = PolarisGame.ArtworkManifest(revision = "auto-after-clear")
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            update = {
                batchStarted.complete(Unit)
                PolarisArtworkUpdateResult(
                    manifest = batchManifest,
                    status = PolarisArtworkUpdateStatus.UPDATED,
                    requestedKinds = emptyList(),
                    remainingKinds = emptyList(),
                )
            },
        )
        try {
            assertTrue(
                coordinator.withArtworkMutation("g1", NovaArtworkMutationOwner.STUDIO) { mutation ->
                    coordinator.publishCommittedArtwork(
                        mutation,
                        PolarisGame.ArtworkManifest(
                            revision = "manual",
                            override = PolarisGame.ArtworkOverride(active = true),
                        ),
                    )
                },
            )
            assertTrue(
                coordinator.withArtworkMutation("g1", NovaArtworkMutationOwner.STUDIO) { mutation ->
                    coordinator.publishCommittedArtwork(
                        mutation,
                        PolarisGame.ArtworkManifest(revision = "cleared"),
                    )
                },
            )
            assertTrue(coordinator.start(listOf(game("g1"))))
            batchStarted.await()
            withTimeout(5_000) {
                while (coordinator.snapshot.value.state !is NovaArtworkLibraryUpdateUiState.Complete) {
                    delay(10)
                }
            }
            assertEquals(
                "auto-after-clear",
                coordinator.snapshot.value.committedArtwork.getValue("g1").revision,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun authoritativeRefreshClearsStaleStudioProtection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val batchStarted = CompletableDeferred<Unit>()
        val batchManifest = PolarisGame.ArtworkManifest(revision = "auto-after-external-clear")
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            update = {
                batchStarted.complete(Unit)
                PolarisArtworkUpdateResult(
                    manifest = batchManifest,
                    status = PolarisArtworkUpdateStatus.UPDATED,
                    requestedKinds = emptyList(),
                    remainingKinds = emptyList(),
                )
            },
        )
        try {
            assertTrue(
                coordinator.withArtworkMutation("g1", NovaArtworkMutationOwner.STUDIO) { mutation ->
                    coordinator.publishCommittedArtwork(
                        mutation,
                        PolarisGame.ArtworkManifest(
                            revision = "manual-before-external-clear",
                            override = PolarisGame.ArtworkOverride(active = true),
                        ),
                    )
                },
            )
            val refresh = coordinator.beginRefresh()
            assertTrue(
                coordinator.publishRefresh(refresh, listOf(game("g1"))) {},
            )
            assertTrue(coordinator.start(listOf(game("g1"))))
            withTimeout(5_000) {
                while (coordinator.snapshot.value.state !is NovaArtworkLibraryUpdateUiState.Complete) {
                    delay(10)
                }
            }
            assertTrue(batchStarted.isCompleted)
            assertEquals(
                "auto-after-external-clear",
                coordinator.snapshot.value.committedArtwork.getValue("g1").revision,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun explicitStudioCommitWinsOverOlderRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = NovaArtworkLibraryUpdateCoordinator(scope = scope, update = { result(PolarisArtworkUpdateStatus.HEALTHY) })
        val refresh = coordinator.beginRefresh()
        assertTrue(
            coordinator.withArtworkMutation("g1", NovaArtworkMutationOwner.STUDIO) { studioMutation ->
                coordinator.publishCommittedArtwork(
                    studioMutation,
                    PolarisGame.ArtworkManifest(revision = "studio"),
                )
            },
        )
        val published = AtomicReference<List<PolarisGame>>()
        assertTrue(coordinator.publishRefresh(refresh, listOf(game("g1"))) { published.set(it) })
        assertEquals("studio", published.get().single().artwork?.revision)
        scope.cancel()
    }

    @Test
    fun newerRefreshPublicationRejectsAnOlderSnapshot() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            update = { result(PolarisArtworkUpdateStatus.HEALTHY) },
        )
        try {
            val older = coordinator.beginRefresh()
            val newer = coordinator.beginRefresh()
            val newerGames = listOf(game("newer"))
            val olderGames = listOf(game("older"))

            var publishedGames: List<PolarisGame>? = null
            assertTrue(
                coordinator.publishRefresh(newer, newerGames) { publishedGames = it },
            )
            assertEquals(newerGames, publishedGames)
            assertFalse(
                coordinator.publishRefresh(older, olderGames) {
                    fail("older refresh must not publish")
                },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun throwingVisiblePublicationNeverAcknowledgesCommittedArtwork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val newManifest = PolarisGame.ArtworkManifest(revision = "new")
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            update = {
                release.await()
                PolarisArtworkUpdateResult(
                    manifest = newManifest,
                    status = PolarisArtworkUpdateStatus.UPDATED,
                    requestedKinds = emptyList(),
                    remainingKinds = emptyList(),
                )
            },
        )
        try {
            assertTrue(coordinator.start(listOf(game("g1"))))
            release.complete(Unit)
            withTimeout(5_000) {
                while (!coordinator.snapshot.value.committedArtwork.containsKey("g1")) delay(10)
            }

            val failedRefresh = coordinator.beginRefresh()
            val refreshed = listOf(game("g1").copy(artwork = newManifest))
            try {
                coordinator.publishRefresh(failedRefresh, refreshed) { published ->
                    assertEquals(refreshed, published)
                    throw IllegalStateException("fixed visible publication failure")
                }
                fail("visible publication failure must propagate")
            } catch (expected: IllegalStateException) {
                assertEquals("fixed visible publication failure", expected.message)
            }
            assertFalse(coordinator.discardRefresh(failedRefresh))
            assertTrue(coordinator.snapshot.value.committedArtwork.containsKey("g1"))

            val cancelledRefresh = coordinator.beginRefresh()
            val cancellation = CancellationException("fixed visible publication cancellation")
            try {
                coordinator.publishRefresh(cancelledRefresh, refreshed) {
                    throw cancellation
                }
                fail("visible publication cancellation must propagate")
            } catch (expected: CancellationException) {
                assertTrue(expected === cancellation)
            }
            assertFalse(coordinator.discardRefresh(cancelledRefresh))
            assertTrue(coordinator.snapshot.value.committedArtwork.containsKey("g1"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun successfulRefreshStartedAfterCommitAcknowledgesCommittedArtwork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val newManifest = PolarisGame.ArtworkManifest(revision = "new")
        val coordinator = NovaArtworkLibraryUpdateCoordinator(
            scope = scope,
            update = {
                release.await()
                PolarisArtworkUpdateResult(
                    manifest = newManifest,
                    status = PolarisArtworkUpdateStatus.UPDATED,
                    requestedKinds = emptyList(),
                    remainingKinds = emptyList(),
                )
            },
        )
        try {
            assertTrue(coordinator.start(listOf(game("g1"))))
            release.complete(Unit)
            withTimeout(5_000) {
                while (!coordinator.snapshot.value.committedArtwork.containsKey("g1")) delay(10)
            }
            val acknowledgingRefresh = coordinator.beginRefresh()
            val refreshed = listOf(game("g1").copy(artwork = newManifest))

            var publishedGames: List<PolarisGame>? = null
            assertTrue(
                coordinator.publishRefresh(acknowledgingRefresh, refreshed) {
                    publishedGames = it
                },
            )
            assertEquals(refreshed, publishedGames)
            assertTrue(coordinator.snapshot.value.committedArtwork.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun updaterCreatesOnlyTheFixedWorkerPool() = runBlocking {
        val workersStarted = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val updater = NovaArtworkLibraryUpdater(
            parallelism = 2,
            onWorkerStarted = { workersStarted.incrementAndGet() },
        )
        val job = launch {
            updater.run((1..1_000).map { game("g$it") }) {
                gate.await()
                result(PolarisArtworkUpdateStatus.HEALTHY)
            }
        }
        try {
            withTimeout(5_000) {
                while (workersStarted.get() < 2) yield()
            }
            assertEquals(2, workersStarted.get())
        } finally {
            job.cancelAndJoin()
        }
    }
}

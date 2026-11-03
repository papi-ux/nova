package com.papi.nova.ui

import com.papi.nova.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NovaDoctorAiTest {
    private val api = Any()
    private val connection = Any()
    private val scope = DoctorAiScope("session", 7, "doctor-network", "a".repeat(64), 9)
    private fun context(at: Long, scope: DoctorAiScope = this.scope, api: Any = this.api, connection: Any = this.connection,
        available: Boolean = true, enabled: Boolean = true) = DoctorAiContext(api, connection,
        DoctorAiReading(true, DoctorAiDescriptor(enabled, true, available, "", scope.sourceResultId, scope.findingFingerprint, scope.controllerRevision), scope, at))
    private val text = DoctorAiText("Optional cause", listOf("Evidence"), listOf("Prose"), "No setting writer")

    @Test fun observationsAndPageOpenSendNothingAndRepeatedPendingClickSendsOnce() = runTest {
        var calls = 0
        val reply = CompletableDeferred<DoctorAiResponse>()
        val model = NovaDoctorAi(backgroundScope, { testScheduler.currentTime + 1 }) { _, _ -> calls++; reply.await() }
        model.observe(context(1)); model.pageVisible(true); runCurrent()
        repeat(5) { model.observe(context(1)) }; assertEquals(0, calls)
        model.explain(); model.explain(); runCurrent(); assertEquals(1, calls)
        assertEquals(NovaDoctorAiMessage.PENDING, model.state.value.message)
        assertFalse(model.state.value.enabled)
        reply.complete(DoctorAiResponse(text, 1000)); runCurrent()
        assertEquals(text, model.state.value.text)
    }
    @Test fun numericReadingsAndHostBusyDescriptorDoNotInvalidateTheSameFinding() = runTest {
        val reply = CompletableDeferred<DoctorAiResponse>()
        val model = NovaDoctorAi(backgroundScope, { testScheduler.currentTime + 1 }) { _, _ -> reply.await() }
        model.observe(context(1)); model.pageVisible(true); model.explain(); runCurrent()
        advanceTimeBy(1000); model.observe(context(1001, available = false))
        reply.complete(DoctorAiResponse(text, 2000)); runCurrent()
        assertEquals(text, model.state.value.text)
        assertFalse(model.state.value.enabled)
    }
    @Test fun observedFindingAbaCannotReviveAnUncancellableLateReply() = runTest {
        val reply = CompletableDeferred<DoctorAiResponse>()
        val model = NovaDoctorAi(backgroundScope, { testScheduler.currentTime + 1 }) { _, _ -> withContext(NonCancellable) { reply.await() } }
        model.observe(context(1)); model.pageVisible(true); model.explain(); runCurrent()
        model.observe(context(1, scope.copy(findingFingerprint = "b".repeat(64))))
        model.observe(context(1)); reply.complete(DoctorAiResponse(text, 1000)); runCurrent()
        assertNull(model.state.value.text)
        assertNotEquals(NovaDoctorAiMessage.PENDING, model.state.value.message)
    }
    @Test fun pageCloseStatusFailureAttachmentScopeControlAndManualIntentRetirePendingReply() = runTest {
        val changes = listOf<(NovaDoctorAi) -> Unit>(
            { it.pageVisible(false) }, { it.observe(null) }, { it.retire() },
            { it.observe(context(1, api = Any())) }, { it.observe(context(1, connection = Any())) },
            { it.observe(context(1, scope.copy(sessionGeneration = 8))) },
            { it.observe(context(1, scope.copy(appSessionId = "replacement"))) },
            { it.observe(context(1, scope.copy(controllerRevision = 10))) },
            { it.observe(context(1, enabled = false)) },
        )
        for (change in changes) {
            val reply = CompletableDeferred<DoctorAiResponse>()
            val model = NovaDoctorAi(backgroundScope, { testScheduler.currentTime + 1 }) { _, _ -> withContext(NonCancellable) { reply.await() } }
            model.observe(context(1)); model.pageVisible(true); model.explain(); runCurrent()
            change(model); reply.complete(DoctorAiResponse(text, 1000)); runCurrent()
            assertNull(model.state.value.text)
        }
    }
    @Test fun expiryAndFailedProviderKeepNoOptionalText() = runTest {
        var reply = DoctorAiResponse(text, 1000)
        val model = NovaDoctorAi(backgroundScope, { testScheduler.currentTime + 1 }) { _, _ -> reply }
        model.observe(context(1)); model.pageVisible(true); model.explain(); runCurrent()
        assertNotNull(model.state.value.text)
        advanceTimeBy(1000); runCurrent(); assertNull(model.state.value.text)
        reply = DoctorAiResponse(); model.explain(); runCurrent()
        assertEquals(NovaDoctorAiMessage.FALLBACK, model.state.value.message)
    }
    @Test fun staleReadCancelsTheRequestWithoutStartingAnother() = runTest {
        var calls = 0; var cancelled = false
        val model = NovaDoctorAi(backgroundScope, { testScheduler.currentTime + 1 }) { _, _ ->
            calls++
            try { CompletableDeferred<DoctorAiResponse>().await() } finally { cancelled = true }
        }
        model.observe(context(1)); model.pageVisible(true); model.explain(); runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertTrue(cancelled); assertEquals(1, calls); assertNull(model.state.value.text); assertFalse(model.state.value.enabled)
    }
    @Test fun inlineFetchDoesNotHoldTheModelMonitorAgainstAnApiPublication() {
        val apiMonitor = Any()
        val publisherHoldsApi = java.util.concurrent.CountDownLatch(1)
        val fetchEntered = java.util.concurrent.CountDownLatch(1)
        val observed = java.util.concurrent.CountDownLatch(1)
        val sawOrderedObservation = java.util.concurrent.atomic.AtomicBoolean(false)
        val jobs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val model = NovaDoctorAi(jobs, fetch = { _, _ ->
            fetchEntered.countDown()
            sawOrderedObservation.set(observed.await(2, java.util.concurrent.TimeUnit.SECONDS))
            synchronized(apiMonitor) { DoctorAiResponse() }
        })
        val reading = context(System.nanoTime() / 1_000_000)
        model.observe(reading); model.pageVisible(true)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val publication = pool.submit {
                synchronized(apiMonitor) {
                    publisherHoldsApi.countDown()
                    check(fetchEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    model.observe(reading)
                    observed.countDown()
                }
            }
            assertTrue(publisherHoldsApi.await(2, java.util.concurrent.TimeUnit.SECONDS))
            model.explain()
            publication.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("Inline fetch held the model monitor", sawOrderedObservation.get())
        } finally { model.retire(); pool.shutdownNow(); jobs.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    @Test fun disabledOrUnavailableReadingDoesNotStartARequest() = runTest {
        var calls = 0
        val model = NovaDoctorAi(backgroundScope, { testScheduler.currentTime + 1 }) { _, _ -> calls++; DoctorAiResponse() }
        model.pageVisible(true)
        model.explain(); model.observe(context(1, enabled = false)); model.explain()
        model.observe(context(1, available = false)); model.explain(); runCurrent()
        assertEquals(0, calls)
    }
}

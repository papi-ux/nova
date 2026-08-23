package com.papi.nova.ui

import android.content.Context
import com.papi.nova.api.PolarisDoctorActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class DoctorActionReceiptStoreTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val scopeA: String
        get() = requireNotNull(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "session-a", "control")
        )

    @Test
    fun watchingVerificationWithoutRepeatDirectiveKeepsPollingSameRun() {
        val applied = DoctorActionReceiptStore.applyResult(
            previous = null,
            scopeId = scopeA,
            result = PolarisDoctorActionResult(
                status = true,
                changed = true,
                state = "watching",
                message = "Fix applied. Doctor is watching live loss and latency.",
                runId = "doctor-run-1",
                verificationDelaySeconds = 8,
                verificationActionId = "verify",
                undoAvailable = true,
                undoActionId = "restore_quality"
            ),
            nowEpochMs = 1_000L
        )

        val earlyVerification = DoctorActionReceiptStore.applyResult(
            previous = applied,
            scopeId = scopeA,
            result = PolarisDoctorActionResult(
                status = true,
                state = "watching",
                runId = "doctor-run-1"
            ),
            nowEpochMs = 9_000L
        )

        assertEquals("doctor-run-1", earlyVerification.runId)
        assertEquals("verify", earlyVerification.verificationActionId)
        assertTrue(earlyVerification.verificationDueAtEpochMs > 9_000L)
        assertTrue(earlyVerification.undoAvailable)
        assertEquals("restore_quality", earlyVerification.undoActionId)
        assertFalse(earlyVerification.isTerminal)
    }

    @Test
    fun blankRunIdInVerificationInheritsCapturedRun() {
        val watching = watchingReceipt()

        val updated = DoctorActionReceiptStore.applyResult(
            previous = watching,
            scopeId = scopeA,
            result = PolarisDoctorActionResult(
                status = true,
                state = "watching",
                runId = ""
            ),
            nowEpochMs = 2_000L
        )

        assertEquals("doctor-run-1", updated.runId)
        assertEquals("verify", updated.verificationActionId)
        assertTrue(updated.verificationPending)
    }

    @Test
    fun resolvedVerificationStopsPollingButKeepsUndoReceipt() {
        val resolved = DoctorActionReceiptStore.applyResult(
            previous = watchingReceipt(),
            scopeId = scopeA,
            result = PolarisDoctorActionResult(
                status = true,
                state = "resolved",
                message = "Doctor verified that network pressure cleared.",
                runId = "doctor-run-1",
                undoAvailable = true,
                undoActionId = "restore_quality"
            ),
            nowEpochMs = 10_000L
        )

        assertTrue(resolved.isTerminal)
        assertEquals("", resolved.verificationActionId)
        assertEquals(0L, resolved.verificationDueAtEpochMs)
        assertTrue(resolved.undoAvailable)
        assertEquals("restore_quality", resolved.undoActionId)
    }

    @Test
    fun resolvedVerificationWithoutUndoMetadataKeepsPreviousUndoReceipt() {
        val resolved = DoctorActionReceiptStore.applyResult(
            previous = watchingReceipt(),
            scopeId = scopeA,
            result = PolarisDoctorActionResult(
                status = true,
                state = "resolved",
                message = "Doctor verified that network pressure cleared.",
                runId = "doctor-run-1"
            ),
            nowEpochMs = 10_000L
        )

        assertTrue(resolved.isTerminal)
        assertEquals("", resolved.verificationActionId)
        assertEquals(0L, resolved.verificationDueAtEpochMs)
        assertTrue(resolved.undoAvailable)
        assertEquals("restore_quality", resolved.undoActionId)
    }

    @Test
    fun explicitHostUndoRevocationOverridesPreviousAvailability() {
        val revoked = DoctorActionReceiptStore.applyResult(
            previous = watchingReceipt(),
            scopeId = scopeA,
            result = PolarisDoctorActionResult(
                status = true,
                state = "needs_attention",
                runId = "doctor-run-1",
                undoAvailable = false
            ),
            nowEpochMs = 10_000L
        )

        assertFalse(revoked.undoAvailable)
        assertEquals("", revoked.undoActionId)
    }

    @Test
    fun successfulUndoClearsTheActionableReceipt() {
        val undone = DoctorActionReceiptStore.applyResult(
            previous = watchingReceipt().copy(state = "resolved", verificationActionId = ""),
            scopeId = scopeA,
            result = PolarisDoctorActionResult(
                status = true,
                state = "undone",
                message = "Doctor restored the previous bitrate and Auto Quality state.",
                runId = "doctor-run-1"
            ),
            nowEpochMs = 11_000L
        )

        assertTrue(undone.isTerminal)
        assertFalse(undone.undoAvailable)
        assertEquals("", undone.undoActionId)
        assertFalse(undone.verificationPending)
    }

    @Test
    fun transientVerificationFailureDefersTheSamePendingRun() {
        val deferred = DoctorActionReceiptStore.deferVerification(
            watchingReceipt(verificationDueAtEpochMs = 1_000L),
            nowEpochMs = 5_000L
        )

        assertEquals("doctor-run-1", deferred.runId)
        assertEquals("verify", deferred.verificationActionId)
        assertTrue(deferred.verificationDueAtEpochMs > 5_000L)
        assertTrue(deferred.verificationPending)
    }

    @Test
    fun repeatedTransientVerificationFailuresBackOffAndStopAfterBound() {
        val first = DoctorActionReceiptStore.deferVerification(
            watchingReceipt(verificationDueAtEpochMs = 1_000L),
            nowEpochMs = 5_000L
        )
        val second = DoctorActionReceiptStore.deferVerification(first, nowEpochMs = 6_000L)
        val third = DoctorActionReceiptStore.deferVerification(second, nowEpochMs = 8_000L)
        val stopped = DoctorActionReceiptStore.deferVerification(third, nowEpochMs = 12_000L)

        assertEquals(6_000L, first.verificationDueAtEpochMs)
        assertEquals(8_000L, second.verificationDueAtEpochMs)
        assertEquals(12_000L, third.verificationDueAtEpochMs)
        assertEquals("needs_attention", stopped.state)
        assertEquals("", stopped.verificationActionId)
        assertEquals(0L, stopped.verificationDueAtEpochMs)
        assertFalse(stopped.verificationPending)
    }

    @Test
    fun successfulWatchingRepliesStopAfterTotalVerificationBound() {
        var receipt = watchingReceipt()
        repeat(12) { attempt ->
            receipt = DoctorActionReceiptStore.applyResult(
                previous = receipt,
                scopeId = scopeA,
                result = PolarisDoctorActionResult(
                    status = true,
                    state = "watching",
                    runId = "doctor-run-1"
                ),
                nowEpochMs = 10_000L + attempt
            )
        }

        assertEquals("needs_attention", receipt.state)
        assertFalse(receipt.verificationPending)
        assertEquals("", receipt.verificationActionId)
        assertEquals(12, receipt.verificationAttemptCount)
    }

    @Test
    fun permanentVerificationRejectionStopsPollingAndHonorsUndoRevocation() {
        val stopped = DoctorActionReceiptStore.stopVerification(
            receipt = watchingReceipt(),
            result = PolarisDoctorActionResult(
                status = false,
                error = "Doctor run expired",
                runId = "doctor-run-1",
                undoAvailable = false
            ),
            nowEpochMs = 10_000L
        )

        assertEquals("needs_attention", stopped.state)
        assertEquals("Doctor run expired", stopped.message)
        assertFalse(stopped.verificationPending)
        assertFalse(stopped.undoAvailable)
        assertEquals("", stopped.undoActionId)
    }

    @Test
    fun permanentVerificationRejectionWithoutUndoMetadataFailsClosed() {
        val stopped = DoctorActionReceiptStore.stopVerification(
            receipt = watchingReceipt(),
            result = PolarisDoctorActionResult(
                status = false,
                error = "Doctor run expired",
                runId = "doctor-run-1"
            ),
            nowEpochMs = 10_000L
        )

        assertFalse(stopped.undoAvailable)
        assertEquals("", stopped.undoActionId)
    }

    @Test
    fun permanentUndoRejectionRetiresDurableUndo() {
        val retired = DoctorActionReceiptStore.retireUndo(
            receipt = watchingReceipt().copy(state = "resolved", verificationActionId = ""),
            result = PolarisDoctorActionResult(
                status = false,
                error = "Doctor run expired",
                runId = "doctor-run-1"
            ),
            nowEpochMs = 12_000L
        )

        assertEquals("needs_attention", retired.state)
        assertEquals("Doctor run expired", retired.message)
        assertFalse(retired.undoAvailable)
        assertEquals("", retired.undoActionId)
    }

    @Test
    fun responseGuardRejectsStaleScopeGenerationAndRunButAcceptsBlankRunId() {
        val request = DoctorActionRequestIdentity(scopeA, "doctor-run-1", generation = 7L)
        val current = watchingReceipt()
        val blankRunResponse = PolarisDoctorActionResult(status = true, state = "watching", runId = "")

        assertTrue(
            DoctorActionReceiptStore.responseMatches(
                current = current,
                activeScopeId = scopeA,
                activeGeneration = 7L,
                request = request,
                result = blankRunResponse
            )
        )
        assertFalse(
            DoctorActionReceiptStore.responseMatches(
                current = current,
                activeScopeId = "other-scope",
                activeGeneration = 7L,
                request = request,
                result = blankRunResponse
            )
        )
        assertFalse(
            DoctorActionReceiptStore.responseMatches(
                current = current,
                activeScopeId = scopeA,
                activeGeneration = 8L,
                request = request,
                result = blankRunResponse
            )
        )
        assertFalse(
            DoctorActionReceiptStore.responseMatches(
                current = current.copy(runId = "doctor-run-2"),
                activeScopeId = scopeA,
                activeGeneration = 7L,
                request = request,
                result = blankRunResponse
            )
        )
        assertFalse(
            DoctorActionReceiptStore.responseMatches(
                current = current,
                activeScopeId = scopeA,
                activeGeneration = 7L,
                request = request,
                result = blankRunResponse.copy(runId = "doctor-run-2")
            )
        )
    }

    @Test
    fun newRunResponseMustProvideANewRunId() {
        val request = DoctorActionRequestIdentity(scopeA, runId = "", generation = 9L)

        assertFalse(
            DoctorActionReceiptStore.responseMatches(
                current = watchingReceipt(),
                activeScopeId = scopeA,
                activeGeneration = 9L,
                request = request,
                result = PolarisDoctorActionResult(status = true, state = "watching", runId = "")
            )
        )
        assertTrue(
            DoctorActionReceiptStore.responseMatches(
                current = watchingReceipt(),
                activeScopeId = scopeA,
                activeGeneration = 9L,
                request = request,
                result = PolarisDoctorActionResult(status = true, state = "watching", runId = "doctor-run-2")
            )
        )
    }

    @Test
    fun successfulLegacyNewRunCanBePresentedWithoutDurableReceipt() {
        val request = DoctorActionRequestIdentity(scopeA, runId = "", generation = 9L)
        val result = PolarisDoctorActionResult(
            status = true,
            changed = true,
            state = "stable",
            message = "Applied",
            runId = ""
        )

        assertTrue(DoctorActionReceiptStore.successfulLegacyNewRunResult(request, result))
        assertFalse(
            DoctorActionReceiptStore.successfulLegacyNewRunResult(
                request.copy(runId = "doctor-run-1"),
                result
            )
        )
        assertFalse(DoctorActionReceiptStore.successfulLegacyNewRunResult(request, result.copy(status = false)))
    }

    @Test
    fun staleMenuValidationGenerationIsRejected() {
        assertTrue(DoctorActionReceiptStore.validationGenerationIsCurrent(7L, 7L))
        assertFalse(DoctorActionReceiptStore.validationGenerationIsCurrent(8L, 7L))
    }

    @Test
    fun receiptIsVisibleOnlyAfterExactScopeValidation() {
        val receipt = watchingReceipt()

        assertNull(DoctorActionReceiptStore.visibleReceipt(receipt, scopeA, validatedScopeId = null))
        assertNull(DoctorActionReceiptStore.visibleReceipt(receipt, scopeA, validatedScopeId = "other-scope"))
        assertEquals(receipt, DoctorActionReceiptStore.visibleReceipt(receipt, scopeA, scopeA))
    }

    @Test
    fun scopedPersistenceDoesNotOverwriteOtherSessionOrStoreRawIdentity() {
        val prefs = context.getSharedPreferences("doctor-receipt-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val scopeB = requireNotNull(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "session-b", "control")
        )
        val receiptA = watchingReceipt()
        val receiptB = watchingReceipt().copy(scopeId = scopeB, runId = "doctor-run-2")

        DoctorActionReceiptStore.save(prefs, receiptA)
        DoctorActionReceiptStore.save(prefs, receiptB)

        val encoded = prefs.all.values.joinToString("\n")
        assertFalse(encoded.contains("session-a"))
        assertFalse(encoded.contains("session-b"))
        assertFalse(encoded.contains("10.0.0.232"))
        assertEquals(receiptA, DoctorActionReceiptStore.load(prefs, scopeA))
        assertEquals(receiptB, DoctorActionReceiptStore.load(prefs, scopeB))
        assertNull(DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "", "control"))
    }

    @Test
    fun receiptScopeRequiresExactSessionTokenAndNonBlankGameIdentity() {
        assertNull(DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "session-a", ""))
        assertNull(DoctorActionReceiptStore.scopeId("10.0.0.232", 0, "session-a", "control"))
        assertNotEquals(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "session-a", "control"),
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, " session-a", "control")
        )
    }

    @Test
    fun receiptPersistenceRetainsOnlyEightMostRecentScopes() {
        val prefs = context.getSharedPreferences("doctor-receipt-retention-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var lastScope = ""
        repeat(9) { index ->
            lastScope = requireNotNull(
                DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "session-$index", "game-$index")
            )
            DoctorActionReceiptStore.save(
                prefs,
                watchingReceipt().copy(scopeId = lastScope, runId = "doctor-run-$index")
            )
            Thread.sleep(2L)
        }

        assertEquals(8, prefs.all.size)
        assertEquals("doctor-run-8", DoctorActionReceiptStore.load(prefs, lastScope)?.runId)
    }

    @Test
    fun completionAfterReopenRefreshesOnlyTheCurrentMenu() {
        val registry = DoctorMenuRefreshRegistry()
        val refreshed = mutableListOf<String>()
        val first = registry.open()
        assertTrue(registry.attach(first) { refreshed += "first" })
        assertTrue(registry.close(first))
        val second = registry.open()
        assertTrue(registry.attach(second) { refreshed += "second" })

        assertFalse(registry.close(first))
        assertNull(registry.runIfCurrent(first) { refreshed += "stale" })
        assertTrue(registry.runIfCurrent(second) { refreshed += "current" } == Unit)
        assertTrue(registry.dispatch())
        assertEquals(listOf("current", "second"), refreshed)
    }

    @Test
    fun staleCompletionCannotClearANewerPendingDoctorRequest() {
        val pending = DoctorActionPendingRegistry()
        assertTrue(pending.begin(1L))
        pending.reset()
        assertTrue(pending.begin(2L))

        assertFalse(pending.clearIfOwned(1L))
        assertTrue(pending.isPending())
        assertTrue(pending.clearIfOwned(2L))
        assertFalse(pending.isPending())
    }

    @Test
    fun sameGenerationVerificationCannotBeClaimedTwiceConcurrently() {
        val pending = DoctorActionPendingRegistry()

        assertTrue(pending.begin(7L))
        assertFalse(pending.begin(7L))
        assertTrue(pending.isPending())
        assertTrue(pending.clearIfOwned(7L))
        assertTrue(pending.begin(7L))
    }

    private fun watchingReceipt(
        verificationDueAtEpochMs: Long = 9_000L
    ) = DoctorActionReceipt(
        scopeId = scopeA,
        runId = "doctor-run-1",
        state = "watching",
        message = "Watching",
        verificationActionId = "verify",
        verificationDueAtEpochMs = verificationDueAtEpochMs,
        undoAvailable = true,
        undoActionId = "restore_quality"
    )
}

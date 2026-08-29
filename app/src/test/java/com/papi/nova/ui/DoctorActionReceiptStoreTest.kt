package com.papi.nova.ui

import android.content.Context
import com.papi.nova.api.PolarisDoctorActionResult
import com.papi.nova.api.PolarisSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun recoveryReceiptReconstructsQueuedUndoAfterResumeAndRetiresTerminalStates() {
        val recoveryScope = requireNotNull(
            DoctorActionReceiptStore.recoveryScopeId("10.0.0.232", 47984, "game-a")
        )
        val queued = DoctorActionReceiptStore.fromRecoveryReceipt(
            scopeId = recoveryScope,
            receipt = PolarisSessionStatus.RecoveryReceipt(
                state = "queued",
                runId = "recovery-run-a",
                appUuid = "game-a",
                expiresAt = 2_000_000_000L,
                message = "Safer settings are queued for the next launch.",
                undoSupported = true,
                undoAvailable = true,
                undoActionId = "undo_recovery_profile_next_launch"
            ),
            nowEpochMs = 1_000L
        )

        assertNotNull(queued)
        assertEquals("queued", queued?.state)
        assertTrue(queued?.undoAvailable == true)
        assertEquals("undo_recovery_profile_next_launch", queued?.undoActionId)
        assertFalse(queued?.isTerminal == true)
        for (terminalState in listOf("expired", "applied", "rejected", "undone", "superseded")) {
            val terminal = DoctorActionReceiptStore.fromRecoveryReceipt(
                scopeId = recoveryScope,
                receipt = PolarisSessionStatus.RecoveryReceipt(
                    state = terminalState,
                    runId = "recovery-run-a",
                    appUuid = "game-a",
                    undoAvailable = false
                ),
                nowEpochMs = 2_000L
            )
            assertTrue("$terminalState must retire the queued action", terminal?.isTerminal == true)
            assertFalse(terminal?.undoAvailable == true)
        }
    }

    @Test
    fun recoveryScopeTransitionDoesNotResurrectQueuedReceiptAfterUndo() {
        val prefs = context.getSharedPreferences("doctor-receipt-recovery-scope-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val appScope = requireNotNull(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "session-a", "game-a")
        )
        val recoveryScope = requireNotNull(
            DoctorActionReceiptStore.recoveryScopeId("10.0.0.232", 47984, "game-a")
        )
        val initiallyQueued = DoctorActionReceipt(
            scopeId = appScope,
            runId = "recovery-run-a",
            state = "queued",
            message = "Queued",
            verificationActionId = "verify_recovery_profile_next_launch",
            undoAvailable = true,
            undoActionId = "undo_recovery_profile_next_launch",
            appUuid = "game-a",
            updatedAtEpochMs = 1_000L
        )
        DoctorActionReceiptStore.save(prefs, initiallyQueued)

        val reconstructed = DoctorActionReceiptStore.reconcileScope(
            preferences = prefs,
            currentReceipt = initiallyQueued,
            currentScopeId = appScope,
            nextScopeId = recoveryScope,
            appSessionScopeId = appScope,
            recoveryScopeId = recoveryScope,
            currentAppUuid = "game-a",
            authoritativeRecovery = PolarisSessionStatus.RecoveryReceipt(
                state = "queued",
                runId = "recovery-run-a",
                appUuid = "game-a",
                undoAvailable = true,
                undoActionId = "undo_recovery_profile_next_launch"
            ),
            nowEpochMs = 2_000L
        )

        assertEquals(recoveryScope, reconstructed?.scopeId)
        assertNull(DoctorActionReceiptStore.load(prefs, appScope))
        assertEquals("queued", DoctorActionReceiptStore.load(prefs, recoveryScope)?.state)

        val undone = DoctorActionReceiptStore.applyResult(
            previous = reconstructed,
            scopeId = recoveryScope,
            result = PolarisDoctorActionResult(
                status = true,
                state = "undone",
                recoveryState = "undone",
                runId = "recovery-run-a",
                appUuid = "game-a",
                undoAvailable = false
            ),
            nowEpochMs = 3_000L
        )
        DoctorActionReceiptStore.save(prefs, undone)

        val afterRecordRemoval = DoctorActionReceiptStore.reconcileScope(
            preferences = prefs,
            currentReceipt = null,
            currentScopeId = null,
            nextScopeId = appScope,
            appSessionScopeId = appScope,
            recoveryScopeId = recoveryScope,
            currentAppUuid = "game-a",
            authoritativeRecovery = null,
            nowEpochMs = 4_000L
        )

        assertEquals("undone", afterRecordRemoval?.state)
        assertEquals(appScope, afterRecordRemoval?.scopeId)
        assertFalse(afterRecordRemoval?.undoAvailable == true)
        assertNull(DoctorActionReceiptStore.load(prefs, recoveryScope))
        assertEquals("undone", DoctorActionReceiptStore.load(prefs, appScope)?.state)
        assertEquals(
            "undone",
            DoctorActionReceiptStore.reconcileScope(
                preferences = prefs,
                currentReceipt = null,
                currentScopeId = null,
                nextScopeId = appScope,
                appSessionScopeId = appScope,
                recoveryScopeId = recoveryScope,
                currentAppUuid = "game-a",
                authoritativeRecovery = null,
                nowEpochMs = 5_000L
            )?.state
        )
    }

    @Test
    fun authoritativeAbsenceAfterHostUndoAndClientCrashClearsEveryQueuedCopy() {
        val prefs = context.getSharedPreferences("doctor-receipt-undo-crash-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val appScope = requireNotNull(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "session-a", "game-a")
        )
        val recoveryScope = requireNotNull(
            DoctorActionReceiptStore.recoveryScopeId("10.0.0.232", 47984, "game-a")
        )
        DoctorActionReceiptStore.save(
            prefs,
            DoctorActionReceipt(
                scopeId = appScope,
                runId = "recovery-run-a",
                state = "queued",
                message = "Queued",
                verificationActionId = "verify_recovery_profile_next_launch",
                undoAvailable = true,
                undoActionId = "undo_recovery_profile_next_launch",
                appUuid = "game-a"
            )
        )

        // Nova restarted before reconstructing the authoritative queued record.
        val reconstructed = DoctorActionReceiptStore.reconcileScope(
            preferences = prefs,
            currentReceipt = null,
            currentScopeId = null,
            nextScopeId = recoveryScope,
            appSessionScopeId = appScope,
            recoveryScopeId = recoveryScope,
            currentAppUuid = "game-a",
            authoritativeRecovery = PolarisSessionStatus.RecoveryReceipt(
                state = "queued",
                runId = "recovery-run-a",
                appUuid = "game-a",
                undoAvailable = true,
                undoActionId = "undo_recovery_profile_next_launch"
            ),
            nowEpochMs = 2_000L
        )
        assertEquals("queued", reconstructed?.state)
        assertNull(DoctorActionReceiptStore.load(prefs, appScope))
        assertEquals("queued", DoctorActionReceiptStore.load(prefs, recoveryScope)?.state)

        // Polaris accepted Undo, but Nova died before persisting the undone response.
        val afterRestart = DoctorActionReceiptStore.reconcileScope(
            preferences = prefs,
            currentReceipt = null,
            currentScopeId = null,
            nextScopeId = appScope,
            appSessionScopeId = appScope,
            recoveryScopeId = recoveryScope,
            currentAppUuid = "game-a",
            authoritativeRecovery = null,
            nowEpochMs = 3_000L
        )

        assertNull(afterRestart)
        assertNull(DoctorActionReceiptStore.load(prefs, appScope))
        assertNull(DoctorActionReceiptStore.load(prefs, recoveryScope))
    }

    @Test
    fun authoritativeRecoveryPreservesUnrelatedTerminalAppReceipt() {
        val prefs = context.getSharedPreferences("doctor-receipt-terminal-preservation-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val appScope = requireNotNull(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "session-a", "game-a")
        )
        val recoveryScope = requireNotNull(
            DoctorActionReceiptStore.recoveryScopeId("10.0.0.232", 47984, "game-a")
        )
        val terminalNetworkReceipt = DoctorActionReceipt(
            scopeId = appScope,
            runId = "network-run-a",
            state = "resolved",
            message = "Network pressure cleared.",
            undoAvailable = true,
            undoActionId = "restore_quality"
        )
        DoctorActionReceiptStore.save(prefs, terminalNetworkReceipt)

        val reconstructed = DoctorActionReceiptStore.reconcileScope(
            preferences = prefs,
            currentReceipt = null,
            currentScopeId = null,
            nextScopeId = recoveryScope,
            appSessionScopeId = appScope,
            recoveryScopeId = recoveryScope,
            currentAppUuid = "game-a",
            authoritativeRecovery = PolarisSessionStatus.RecoveryReceipt(
                state = "queued",
                runId = "recovery-run-a",
                appUuid = "game-a",
                undoAvailable = true,
                undoActionId = "undo_recovery_profile_next_launch"
            ),
            nowEpochMs = 2_000L
        )

        assertEquals("queued", reconstructed?.state)
        assertEquals("resolved", DoctorActionReceiptStore.load(prefs, appScope)?.state)
        assertEquals("network-run-a", DoctorActionReceiptStore.load(prefs, appScope)?.runId)
        assertTrue(DoctorActionReceiptStore.load(prefs, appScope)?.undoAvailable == true)
        assertEquals("queued", DoctorActionReceiptStore.load(prefs, recoveryScope)?.state)
    }

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
    fun responseGuardRejectsStaleScopeGenerationRunAndBlankRunId() {
        val request = DoctorActionRequestIdentity(scopeA, "doctor-run-1", generation = 7L)
        val current = watchingReceipt()
        val blankRunResponse = PolarisDoctorActionResult(status = true, state = "watching", runId = "")

        assertFalse(
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
        assertFalse(
            DoctorActionReceiptStore.responseMatches(
                current = watchingReceipt(),
                activeScopeId = scopeA,
                activeGeneration = 9L,
                request = request,
                result = PolarisDoctorActionResult(
                    status = true,
                    state = "watching",
                    runId = "doctor-run-2",
                    changedContractValid = false
                )
            )
        )
    }

    @Test
    fun successfulReadOnlyRecheckCanBePresentedWithoutDurableReceipt() {
        val request = DoctorActionRequestIdentity(
            scopeA,
            runId = "",
            generation = 9L,
            actionId = "recheck_pacing"
        )
        val result = PolarisDoctorActionResult(
            status = true,
            changed = false,
            state = "observed",
            message = "Observed",
            runId = ""
        )

        assertTrue(DoctorActionReceiptStore.successfulReadOnlyNewRunResult(request, result))
        assertFalse(
            DoctorActionReceiptStore.successfulReadOnlyNewRunResult(
                request.copy(runId = "doctor-run-1"),
                result
            )
        )
        assertFalse(
            DoctorActionReceiptStore.successfulReadOnlyNewRunResult(
                request.copy(actionId = "lower_bitrate"),
                result.copy(changed = true, state = "stable")
            )
        )
        assertFalse(DoctorActionReceiptStore.successfulReadOnlyNewRunResult(request, result.copy(status = false)))
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

        val encoded = prefs.all.entries.joinToString("\n") { (key, value) -> "$key=$value" }
        assertFalse(encoded.contains("session-a"))
        assertFalse(encoded.contains("session-b"))
        assertFalse(encoded.contains("10.0.0.232"))
        assertEquals(receiptA, DoctorActionReceiptStore.load(prefs, scopeA))
        assertEquals(receiptB, DoctorActionReceiptStore.load(prefs, scopeB))
        assertNull(DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "", "control"))
    }

    @Test
    fun receiptScopeSurvivesTransportTokenRotationButRejectsANewAppGeneration() {
        val beforeStatus = PolarisSessionStatus(
            state = "streaming",
            sessionToken = "transport-a",
            appSessionId = "app-session-a",
            appSessionIdPresent = true,
            sessionGeneration = 41L,
            gameUuid = "control"
        )
        val afterStatus = beforeStatus.copy(sessionToken = "transport-b")
        val laterStatus = afterStatus.copy(
            appSessionId = "app-session-b",
            sessionGeneration = 42L
        )
        val beforeResume = requireNotNull(
            DoctorActionReceiptStore.scopeId(
                host = "10.0.0.232",
                httpsPort = 47984,
                sessionStatus = beforeStatus
            )
        )
        val afterResume = requireNotNull(
            DoctorActionReceiptStore.scopeId(
                host = "10.0.0.232",
                httpsPort = 47984,
                sessionStatus = afterStatus
            )
        )
        val laterLaunch = requireNotNull(
            DoctorActionReceiptStore.scopeId(
                host = "10.0.0.232",
                httpsPort = 47984,
                sessionStatus = laterStatus
            )
        )
        val differentGame = requireNotNull(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, afterStatus.copy(gameUuid = "other"))
        )
        val differentHost = requireNotNull(
            DoctorActionReceiptStore.scopeId("10.0.0.233", 47984, afterStatus)
        )
        val differentPort = requireNotNull(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47985, afterStatus)
        )

        val prefs = context.getSharedPreferences("doctor-receipt-resume-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val receipt = watchingReceipt().copy(scopeId = beforeResume)
        DoctorActionReceiptStore.save(prefs, receipt)
        val resumedReceipt = DoctorActionReceiptStore.load(prefs, afterResume)
        val request = DoctorActionRequestIdentity(afterResume, receipt.runId, generation = 7L)

        assertEquals(beforeResume, afterResume)
        assertNotEquals(beforeResume, laterLaunch)
        assertNotEquals(beforeResume, differentGame)
        assertNotEquals(beforeResume, differentHost)
        assertNotEquals(beforeResume, differentPort)
        assertEquals(receipt, resumedReceipt)
        assertEquals(receipt, DoctorActionReceiptStore.visibleReceipt(resumedReceipt, afterResume, afterResume))
        assertTrue(DoctorActionReceiptStore.requestIsCurrent(resumedReceipt, afterResume, 7L, request))
        assertTrue(
            DoctorActionReceiptStore.undoIsAuthorized(
                current = resumedReceipt,
                candidate = receipt,
                activeScopeId = afterResume,
                validatedScopeId = afterResume,
                canAdjustHostTuning = true
            )
        )
        assertNull(DoctorActionReceiptStore.load(prefs, laterLaunch))
        assertFalse(DoctorActionReceiptStore.requestIsCurrent(resumedReceipt, laterLaunch, 7L, request))
        assertFalse(
            DoctorActionReceiptStore.undoIsAuthorized(
                current = resumedReceipt,
                candidate = receipt,
                activeScopeId = laterLaunch,
                validatedScopeId = laterLaunch,
                canAdjustHostTuning = true
            )
        )
        assertFalse(
            DoctorActionReceiptStore.undoIsAuthorized(
                current = resumedReceipt,
                candidate = receipt.copy(scopeId = laterLaunch),
                activeScopeId = afterResume,
                validatedScopeId = afterResume,
                canAdjustHostTuning = true
            )
        )
    }

    @Test
    fun scopeRejectsPresentBlankAppIdentityAndSeparatesIdentityDomains() {
        val presentBlank = PolarisSessionStatus(
            state = "streaming",
            sessionToken = "transport-a",
            appSessionId = "",
            appSessionIdPresent = true,
            gameUuid = "control"
        )
        val appIdentity = presentBlank.copy(
            sessionToken = "other-transport",
            appSessionId = "same-raw-value"
        )
        val legacyIdentity = presentBlank.copy(
            sessionToken = "same-raw-value",
            appSessionId = "",
            appSessionIdPresent = false
        )

        assertNull(DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, presentBlank))
        assertNotEquals(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, appIdentity),
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, legacyIdentity)
        )
        assertNull(
            DoctorActionReceiptStore.scopeId(
                "10.0.0.232",
                47984,
                appIdentity.copy(appSessionId = "x".repeat(2_049))
            )
        )
        assertNotEquals(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "a\u0000b", "c"),
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "a", "b\u0000c")
        )
        assertNull(
            DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, "\uD800", "control")
        )
    }

    @Test
    fun undoAuthorityRejectsEveryIndependentMismatch() {
        val current = watchingReceipt().copy(state = "resolved", verificationActionId = "")
        fun authorized(
            candidate: DoctorActionReceipt = current,
            activeScope: String? = scopeA,
            validatedScope: String? = scopeA,
            canAdjust: Boolean = true
        ) = DoctorActionReceiptStore.undoIsAuthorized(
            current = current,
            candidate = candidate,
            activeScopeId = activeScope,
            validatedScopeId = validatedScope,
            canAdjustHostTuning = canAdjust
        )

        assertTrue(authorized())
        assertFalse(authorized(candidate = current.copy(runId = "other-run")))
        assertFalse(authorized(candidate = current.copy(undoActionId = "other-action")))
        assertFalse(authorized(candidate = current.copy(scopeId = "other-scope")))
        assertFalse(authorized(activeScope = "other-scope", validatedScope = "other-scope"))
        assertFalse(authorized(validatedScope = null))
        assertFalse(authorized(canAdjust = false))
        val queuedRecovery = current.copy(runId = "recovery-run-1", state = "queued")
        assertTrue(
            DoctorActionReceiptStore.undoIsAuthorized(
                current = queuedRecovery,
                candidate = queuedRecovery,
                activeScopeId = scopeA,
                validatedScopeId = scopeA,
                canAdjustHostTuning = false
            )
        )
        assertFalse(
            DoctorActionReceiptStore.undoIsAuthorized(
                current = current.copy(undoAvailable = false),
                candidate = current,
                activeScopeId = scopeA,
                validatedScopeId = scopeA,
                canAdjustHostTuning = true
            )
        )
    }

    @Test
    fun olderHostFallsBackToExactTransportTokenWithoutClaimingReconnectContinuity() {
        val firstTransport = PolarisSessionStatus(
            state = "streaming",
            sessionToken = "transport-a",
            appSessionId = "",
            gameUuid = "control"
        )
        val nextTransport = firstTransport.copy(sessionToken = "transport-b")

        val firstScope = DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, firstTransport)
        val nextScope = DoctorActionReceiptStore.scopeId("10.0.0.232", 47984, nextTransport)

        assertNotNull(firstScope)
        assertNotNull(nextScope)
        assertNotEquals(firstScope, nextScope)
        assertNull(
            DoctorActionReceiptStore.scopeId(
                "10.0.0.232",
                47984,
                firstTransport.copy(sessionToken = "")
            )
        )
    }

    @Test
    fun receiptScopeRequiresExactAppSessionAndNonBlankGameIdentity() {
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

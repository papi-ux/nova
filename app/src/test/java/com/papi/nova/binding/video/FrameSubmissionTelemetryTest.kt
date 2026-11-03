package com.papi.nova.binding.video

import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.nvstream.jni.NativeFrameSubmissionSnapshot
import com.papi.nova.runtime.DoctorTelemetryUploadGate
import org.junit.Assert.*
import org.junit.Test

class FrameSubmissionTelemetryTest {
    private val api = Any()
    private val connection = Any()
    private val snapshot = NativeFrameSubmissionSnapshot("1234567890abcdef1234567890abcdef", 7, 1, 42, 10, 10000, 1000, 999)
    private val launch = com.papi.nova.nvstream.FrameSubmissionLaunchIdentity("abcdef0123456789abcdef0123456789", "session")
    private val status = PolarisSessionStatus("streaming", streamingActive = true, ownedByClient = true,
        clientRole = "owner", appSessionId = "session", appSessionIdPresent = true, sessionGeneration = 55,
        sessionToken = "session", ownerUniqueId = "owner", frameSubmissionLaunchNonce = launch.nonce, frameSubmissionScopeTyped = true)

    private fun tracker(status: PolarisSessionStatus? = this.status) = FrameSubmissionScopeTracker().apply { observe(status) }
    private fun capture(t: FrameSubmissionScopeTracker) = FrameSubmissionBinding.capture(snapshot, api, connection,
        t.current(), 7, capable = true, active = true, launch = launch)!!
    private fun current(b: FrameSubmissionBinding, t: FrameSubmissionScopeTracker, api: Any = this.api,
                        connection: Any = this.connection, epoch: Long = 7, active: Boolean = true,
                        capable: Boolean = true, app: String = "session", generation: Long = 55, nowMs: Long = 1000) =
        b.current(api, connection, t.current(), epoch, capable, active, app, generation, nowMs)?.observation

    @Test fun immutableProductionBindingRefusesQueuedUiWorkForNewConnectionOrEpoch() {
        val t = tracker()
        val bound = capture(t)
        assertSame(snapshot, current(bound, t))
        assertNull(current(bound, t, connection = Any()))
        assertNull(current(bound, t, api = Any()))
        assertNull(current(bound, t, epoch = 8))
        assertNull(current(bound, t, epoch = 0))
        assertNull(current(bound, t, active = false))
        assertNull(current(bound, t, capable = false))
        assertNull(current(bound, t, app = "new"))
        assertNull(current(bound, t, generation = 56))
    }

    @Test fun scopeTransitionsCannotReviveOldQueuedWorkEvenWhenTheOriginalScopeReturns() {
        val changes = listOf<PolarisSessionStatus?>(null, status.copy(appSessionId = "new"),
            status.copy(sessionGeneration = 56), status.copy(ownerUniqueId = "new"),
            status.copy(sessionToken = "new"), status.copy(ownedByClient = false),
            status.copy(authorityContractValid = false), status.copy(streamingActive = false),
            status.copy(shutdownRequested = true), status.copy(clientRole = "viewer"),
            status.copy(liveTuningPresent = true, liveTuning = null))
        for (change in changes) {
            val t = tracker()
            val bound = capture(t)
            t.observe(change)
            assertNull(current(bound, t))
            t.observe(status)
            assertNull(current(bound, t))
            assertSame(snapshot, current(capture(t), t))
        }
    }

    @Test fun ordinaryStatusUpdatesRetainTheProducingLifetime() {
        val t = tracker()
        val bound = capture(t)
        t.observe(status.copy(game = "new label", viewerCount = 2))
        assertSame(snapshot, current(bound, t))
    }

    @Test fun hostInstanceChangeOrMismatchedHostGenerationRetiresQueuedWork() {
        val live = com.papi.nova.api.LiveTuningStatus(false, "off", true, "", 20000, 20000, 20000,
            "a".repeat(64), "host-a", 1, 55, "session")
        val initial = status.copy(liveTuning = live, liveTuningPresent = true)
        val t = tracker(initial)
        val bound = capture(t)
        t.observe(initial.copy(liveTuning = live.copy(hostInstance = "host-b")))
        assertNull(current(bound, t))
        t.observe(initial)
        assertNull(current(bound, t))
        assertNull(FrameSubmissionBinding.capture(snapshot, api, connection,
            tracker(initial.copy(liveTuning = live.copy(sessionGeneration = 56))).current(), 7, true, true, launch))
    }

    @Test fun queuedWorkCannotReviveOldMediaOrUseARegressingReceiverClock() {
        val t = tracker()
        val bound = capture(t)
        assertSame(snapshot, current(bound, t, nowMs = 5999))
        assertNull(current(bound, t, nowMs = 6000))
        assertNull(current(bound, t, nowMs = 999))
    }

    @Test fun incompleteOrUnvalidatedHostScopeCannotCapture() {
        val invalid = listOf(status.copy(appSessionId = ""), status.copy(appSessionIdPresent = false),
            status.copy(sessionGeneration = 0), status.copy(ownerUniqueId = ""), status.copy(sessionToken = ""),
            status.copy(authorityContractValid = false), status.copy(clientRole = "viewer"),
            status.copy(ownedByClient = false), status.copy(shutdownRequested = true), status.copy(frameSubmissionScopeTyped = false))
        for (s in invalid) assertNull(FrameSubmissionBinding.capture(snapshot, api, connection,
            tracker(s).current(), 7, true, true, launch))
        assertNull(FrameSubmissionBinding.capture(snapshot, api, connection, tracker().current(), 8, true, true, launch))
        assertNull(FrameSubmissionBinding.capture(null, api, connection, tracker().current(), 7, true, true, launch))
    }

    @Test fun lateCoroutineAndLeaseCompletionCannotPublishOrReleaseNewWork() {
        val gate = DoctorTelemetryUploadGate()
        val t = tracker()
        val oldBound = capture(t)
        val oldLease = gate.tryAcquire()!!
        val queuedCoroutine = { current(oldBound, t, active = gate.isCurrent(oldLease)) }
        gate.invalidate()
        val newLease = gate.tryAcquire()!!
        assertNull(queuedCoroutine())
        assertFalse(gate.release(oldLease))
        assertTrue(gate.isCurrent(newLease))
        assertSame(snapshot, current(capture(t), t, active = gate.isCurrent(newLease)))
        assertTrue(gate.release(newLease))
    }

    @Test fun resetWithLargerTotalsStillRequiresItsOwnNativeEpoch() {
        val t = tracker()
        val next = snapshot.copy(connectionNonce = "abcdef0123456789abcdef0123456789", nativeEpoch = 8,
            lastFrameIndex = 1000, decodeUnitsSubmittedTotal = 100, payloadBytesTotal = 100000)
        val old = capture(t)
        assertNull(current(old, t, epoch = 8))
        assertNull(FrameSubmissionBinding.capture(next, api, connection, t.current(), 7, true, true, launch))
        val fresh = FrameSubmissionBinding.capture(next, api, connection, t.current(), 8, true, true, launch)!!
        assertSame(next, current(fresh, t, epoch = 8))
    }
}

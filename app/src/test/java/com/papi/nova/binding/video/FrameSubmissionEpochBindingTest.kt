package com.papi.nova.binding.video

import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.nvstream.FrameSubmissionLaunchIdentity
import com.papi.nova.nvstream.FrameSubmissionProducer
import com.papi.nova.nvstream.jni.NativeFrameSubmissionSnapshot
import org.junit.Assert.*
import org.junit.Test

class FrameSubmissionEpochBindingTest {
    private val api = Any()
    private val connection = Any()
    private val launch = FrameSubmissionLaunchIdentity("abcdef0123456789abcdef0123456789", "session")
    private val snapshot = NativeFrameSubmissionSnapshot("1234567890abcdef1234567890abcdef", 7, 1, 42, 10, 10000, 1000, 999)
    private val status = PolarisSessionStatus("streaming", streamingActive = true, ownedByClient = true,
        clientRole = "owner", appSessionId = "session", appSessionIdPresent = true, sessionGeneration = 55,
        sessionToken = "session", ownerUniqueId = "owner", frameSubmissionLaunchNonce = launch.nonce, frameSubmissionScopeTyped = true)
    private val tracker = FrameSubmissionScopeTracker().apply { observe(status) }
    private val binding = FrameSubmissionEpochBinding()
    private fun capture(s: NativeFrameSubmissionSnapshot = snapshot, connection: Any = this.connection,
                        launch: FrameSubmissionLaunchIdentity? = this.launch,
                        producer: FrameSubmissionProducer? = FrameSubmissionProducer(connection, launch, s.nativeEpoch),
                        currentEpoch: Long = s.nativeEpoch) =
        binding.capture(s, api, connection, launch, tracker.current(), currentEpoch, true, true, producer)

    @Test fun staleInitialStatusWaitsForTheExactSuccessfulAttemptWithoutGuessingGeneration() {
        tracker.observe(status.copy(frameSubmissionLaunchNonce = "b".repeat(32)))
        assertNull(capture())
        tracker.observe(null)
        assertNull(capture())
        tracker.observe(status)
        assertNotNull(capture())
    }

    @Test fun nativeEpochCannotRebindToAnotherHostGenerationOrReturnAfterDrift() {
        assertNotNull(capture())
        tracker.observe(status.copy(sessionGeneration = 56))
        assertNull(capture())
        tracker.observe(status)
        assertNull(capture())
        assertNotNull(capture(snapshot.copy(nativeEpoch = 8)))
    }

    @Test fun nonceAbaObservedBetweenPerfCallbacksStillRetiresThePinnedEpoch() {
        assertNotNull(capture())
        tracker.observe(status.copy(frameSubmissionLaunchNonce = "b".repeat(32)))
        tracker.observe(status)
        assertNull(capture())
        assertNull(capture())
    }

    @Test fun sameNativeEpochWithAnotherNativeNonceIsNeverRelabeled() {
        assertNotNull(capture())
        assertNull(capture(snapshot.copy(connectionNonce = "c".repeat(32))))
        assertNull(capture())
        assertNotNull(capture(snapshot.copy(nativeEpoch = 8)))
    }

    @Test fun sameAppTokenResumeNeedsANewAttemptAndANewNativeEpoch() {
        assertNotNull(capture())
        val resumed = FrameSubmissionLaunchIdentity("b".repeat(32), launch.sessionToken)
        val nextConnection = Any()
        tracker.observe(status.copy(sessionGeneration = 56, frameSubmissionLaunchNonce = resumed.nonce))
        assertNull(capture(connection = nextConnection, launch = resumed))
        assertNotNull(capture(snapshot.copy(nativeEpoch = 8), nextConnection, resumed))
    }

    @Test fun oldRendererCannotBorrowNewConnectionEvenBeforeTheFirstScopeWasPinned() {
        val other = Any()
        assertNull(capture(connection = other, producer = FrameSubmissionProducer(connection, launch, 7)))
        assertNull(capture(producer = FrameSubmissionProducer(connection, launch, 6)))
        assertNull(capture(producer = null))
        assertNull(capture(currentEpoch = 8))
        assertNotNull(capture())
    }

    @Test fun stoppedEpochDoesNotReviveAndOldCallbackCannotRetireANewerEpoch() {
        assertNotNull(capture())
        binding.retire()
        assertNull(capture())
        assertNotNull(capture(snapshot.copy(nativeEpoch = 8)))
        assertNull(capture(currentEpoch = 8))
        assertNotNull(capture(snapshot.copy(nativeEpoch = 8, snapshotSequence = 2)))
    }

    @Test fun oldHostOrMismatchedConnectionTokenNeverCreatesOptionalEvidence() {
        assertNull(capture(launch = null))
        assertNull(capture(launch = launch.copy(sessionToken = "other")))
        tracker.observe(status.copy(frameSubmissionLaunchNonce = ""))
        assertNull(capture())
    }
}

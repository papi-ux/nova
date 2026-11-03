package com.papi.nova.binding.video

import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.nvstream.jni.NativeFrameSubmissionSnapshot
import com.papi.nova.nvstream.FrameSubmissionLaunchIdentity

/** Native observation plus the independently proven successful producing HTTP attempt. */
data class FrameSubmissionReport(val observation: NativeFrameSubmissionSnapshot, val launchNonce: String) {
    init { require(FrameSubmissionLaunchIdentity.parseNonce(launchNonce) != null) }
    fun toJson() = observation.toJson().put("launch_nonce", launchNonce)
}

/** Local telemetry binding only. Captured synchronously on the producing callback. */
internal class FrameSubmissionBinding private constructor(
    private val snapshot: NativeFrameSubmissionSnapshot,
    private val api: Any,
    private val connection: Any,
    private val scope: Scope,
    private val scopeLifetime: Any,
    private val launch: FrameSubmissionLaunchIdentity,
) {
    internal data class Scope(val app: String, val generation: Long, val owner: String,
                             val token: String, val hostInstance: String?, val launchNonce: String)

    fun current(api: Any?, connection: Any?, observation: FrameSubmissionScopeObservation, nativeEpoch: Long,
                capable: Boolean, active: Boolean, requestApp: String, requestGeneration: Long,
                nowMs: Long): FrameSubmissionReport? =
        snapshot.takeIf {
            active && capable && this.api === api && this.connection === connection &&
                nativeEpoch == it.nativeEpoch && scopeLifetime === observation.lifetime &&
                scope == scope(observation.status) &&
                requestApp == scope.app && requestGeneration == scope.generation &&
                // Both are receiver BOOTTIME. Reuse the existing five-second media coverage bound.
                nowMs >= it.monotonicTimestampMs && nowMs - it.lastSubmissionTimestampMs <= 5000L
        }?.let { FrameSubmissionReport(it, launch.nonce) }

    companion object {
        internal fun scope(status: PolarisSessionStatus?): Scope? {
            if (status == null || !status.frameSubmissionScopeTyped || !status.authorityContractValid || !status.streamingActive || status.shutdownRequested ||
                !status.ownedByClient || status.clientRole != "owner" || !status.appSessionIdPresent ||
                status.appSessionId.isBlank() || status.sessionGeneration <= 0 || status.ownerUniqueId.isBlank() ||
                status.sessionToken.isBlank() ||
                FrameSubmissionLaunchIdentity.parseNonce(status.frameSubmissionLaunchNonce) == null ||
                (status.liveTuningPresent && status.liveTuning == null && !status.liveTuningUnavailable)) return null
            val live = status.liveTuning
            if (live != null && (live.appSessionId != status.appSessionId ||
                    live.sessionGeneration != status.sessionGeneration || live.hostInstance.isBlank())) return null
            return Scope(status.appSessionId, status.sessionGeneration, status.ownerUniqueId,
                status.sessionToken, live?.hostInstance, status.frameSubmissionLaunchNonce)
        }

        fun capture(snapshot: NativeFrameSubmissionSnapshot?, api: Any?, connection: Any?,
                    observation: FrameSubmissionScopeObservation, nativeEpoch: Long, capable: Boolean, active: Boolean,
                    launch: FrameSubmissionLaunchIdentity?): FrameSubmissionBinding? {
            if (!active || !capable || snapshot == null || api == null || connection == null ||
                snapshot.nativeEpoch != nativeEpoch) return null
            val scope = scope(observation.status) ?: return null
            if (launch == null || scope.launchNonce != launch.nonce || scope.token != launch.sessionToken ||
                scope.app != launch.sessionToken) return null
            return FrameSubmissionBinding(snapshot, api, connection, scope, observation.lifetime, launch)
        }
    }
}

/** A native epoch may bind one successful connection/host scope, and cannot rebind after drift. */
internal class FrameSubmissionEpochBinding {
    private var epoch = 0L
    private var retired = false
    private var pinned: Pin? = null
    private data class Pin(val api: Any, val connection: Any, val launch: FrameSubmissionLaunchIdentity,
                           val scope: FrameSubmissionBinding.Scope, val lifetime: Any, val nativeNonce: String)

    @Synchronized fun retire() { retired = true }

    @Synchronized fun capture(snapshot: NativeFrameSubmissionSnapshot?, api: Any?, connection: Any?,
                              launch: FrameSubmissionLaunchIdentity?, observation: FrameSubmissionScopeObservation,
                              nativeEpoch: Long, capable: Boolean, active: Boolean,
                              producer: com.papi.nova.nvstream.FrameSubmissionProducer?): FrameSubmissionBinding? {
        if (!active || !capable || snapshot == null || snapshot.nativeEpoch != nativeEpoch ||
            api == null || connection == null || snapshot.nativeEpoch < epoch || producer == null ||
            producer.connection !== connection || producer.nativeEpoch != snapshot.nativeEpoch || producer.launch != launch) return null
        if (snapshot.nativeEpoch > epoch) {
            epoch = snapshot.nativeEpoch; retired = false; pinned = null
        }
        if (retired) return null
        val scope = FrameSubmissionBinding.scope(observation.status)
        val before = pinned
        if (before != null && (before.api !== api || before.connection !== connection || before.launch != launch ||
                before.scope != scope || before.lifetime !== observation.lifetime || before.nativeNonce != snapshot.connectionNonce)) {
            retired = true
            return null
        }
        val bound = FrameSubmissionBinding.capture(snapshot, api, connection, observation, nativeEpoch,
            capable, active, launch) ?: return null
        if (before == null) pinned = Pin(api, connection, launch!!, scope!!, observation.lifetime, snapshot.connectionNonce)
        return bound
    }
}

internal data class FrameSubmissionScopeObservation(val status: PolarisSessionStatus?, val lifetime: Any)

/** Only the optional telemetry lifetime. A -> unavailable/B -> A cannot revive queued A work. */
internal class FrameSubmissionScopeTracker {
    private var observation = FrameSubmissionScopeObservation(null, Any())
    @Synchronized fun observe(status: PolarisSessionStatus?) {
        val same = FrameSubmissionBinding.scope(status) == FrameSubmissionBinding.scope(observation.status)
        observation = FrameSubmissionScopeObservation(status, if (same) observation.lifetime else Any())
    }
    /** A discarded status may retire optional work, but cannot replace the accepted status. */
    @Synchronized fun invalidateIfDifferent(status: PolarisSessionStatus) {
        if (FrameSubmissionBinding.scope(status) != FrameSubmissionBinding.scope(observation.status)) {
            observation = observation.copy(lifetime = Any())
        }
    }
    @Synchronized fun current(): FrameSubmissionScopeObservation = observation
}

internal fun readFrameSubmissionEpoch(): Long = try {
    MoonBridge.getVideoFrameSubmissionEpoch()
} catch (_: LinkageError) { 0L }

/** The expected epoch belongs to this renderer's native DR setup, never the latest renderer. */
internal fun readFrameSubmissionSnapshot(expectedEpoch: Long): NativeFrameSubmissionSnapshot? =
    if (expectedEpoch <= 0L) null else try {
        MoonBridge.getVideoFrameSubmissionSnapshot(expectedEpoch)
    } catch (_: LinkageError) { null }

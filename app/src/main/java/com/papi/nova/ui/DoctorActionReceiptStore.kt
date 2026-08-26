package com.papi.nova.ui

import android.content.SharedPreferences
import com.papi.nova.api.PolarisDoctorActionResult
import com.papi.nova.api.PolarisSessionStatus
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Durable, session-scoped receipt for one reversible Doctor action.
 *
 * [scopeId] is a SHA-256 fingerprint. Polaris's random app-session identity binds
 * the receipt to one launched app generation across transport reconnects. Older
 * hosts fall back to their exact transport token and do not claim reconnect
 * continuity. Neither raw identity is persisted.
 */
data class DoctorActionReceipt(
    val scopeId: String,
    val runId: String,
    val state: String,
    val message: String,
    val verificationActionId: String = "",
    val verificationDueAtEpochMs: Long = 0L,
    val verificationFailureCount: Int = 0,
    val verificationAttemptCount: Int = 0,
    val undoAvailable: Boolean = false,
    val undoActionId: String = "",
    val appUuid: String = "",
    val expiresAtEpochSeconds: Long = 0L,
    val updatedAtEpochMs: Long = 0L
) {
    val isTerminal: Boolean
        get() = state in DoctorActionReceiptStore.TERMINAL_STATES

    val verificationPending: Boolean
        get() = !isTerminal && runId.isNotBlank() && verificationActionId.isNotBlank() &&
            verificationActionId != "verify_recovery_profile_next_launch"

    val postConnectVerificationPending: Boolean
        get() = !isTerminal && runId.isNotBlank() &&
            verificationActionId == "verify_recovery_profile_next_launch"
}

data class DoctorActionRequestIdentity(
    val scopeId: String,
    val runId: String,
    val generation: Long,
    val appSessionId: String = ""
)

internal class DoctorMenuRefreshRegistry {
    private var generation = 0L
    private var refresh: (() -> Unit)? = null

    @Synchronized
    fun open(): Long {
        generation += 1L
        refresh = null
        return generation
    }

    @Synchronized
    fun isCurrent(candidateGeneration: Long): Boolean = generation == candidateGeneration

    @Synchronized
    fun attach(candidateGeneration: Long, callback: () -> Unit): Boolean {
        if (generation != candidateGeneration) return false
        refresh = callback
        return true
    }

    @Synchronized
    fun close(candidateGeneration: Long): Boolean {
        if (generation != candidateGeneration) return false
        generation += 1L
        refresh = null
        return true
    }

    @Synchronized
    fun <T> runIfCurrent(candidateGeneration: Long, action: () -> T): T? {
        if (generation != candidateGeneration) return null
        return action()
    }

    fun dispatch(): Boolean {
        val callback = synchronized(this) { refresh } ?: return false
        callback()
        return true
    }
}

internal class DoctorActionPendingRegistry {
    private var generation: Long? = null

    @Synchronized
    fun begin(requestGeneration: Long): Boolean {
        if (generation != null) return false
        generation = requestGeneration
        return true
    }

    @Synchronized
    fun clearIfOwned(requestGeneration: Long): Boolean {
        if (generation != requestGeneration) return false
        generation = null
        return true
    }

    @Synchronized
    fun reset() {
        generation = null
    }

    @Synchronized
    fun isPending(): Boolean = generation != null
}

object DoctorActionReceiptStore {
    internal val TERMINAL_STATES = setOf(
        "stable", "resolved", "needs_attention", "applied", "expired", "rejected", "undone"
    )

    private const val RECEIPT_KEY_PREFIX = "nova_doctor_action_receipt_v3_"
    private const val SCOPE_VERSION = "nova-doctor-receipt-scope-v3"
    private const val MAX_SCOPE_IDENTITY_LENGTH = 2_048
    private const val RETRY_DELAY_MS = 1_000L
    private const val MAX_VERIFICATION_FAILURES = 4
    private const val MAX_VERIFICATION_ATTEMPTS = 12
    private const val MAX_FIELD_LENGTH = 2_048
    private const val MAX_RECEIPTS = 8
    private val HEX = "0123456789abcdef".toCharArray()

    fun scopeId(
        host: String,
        httpsPort: Int,
        sessionStatus: PolarisSessionStatus?
    ): String? = sessionStatus?.let {
        val (identityKind, identity) = if (it.appSessionIdPresent) {
            "app-v1" to it.appSessionId
        } else {
            "legacy-token-v1" to it.sessionToken
        }
        if (identity.isBlank()) return null
        scopeId(
            host = host,
            httpsPort = httpsPort,
            identityKind = identityKind,
            appSessionId = identity,
            gameUuid = it.gameUuid
        )
    }

    fun scopeId(
        host: String,
        httpsPort: Int,
        appSessionId: String,
        gameUuid: String
    ): String? = scopeId(host, httpsPort, "app-v1", appSessionId, gameUuid)

    fun recoveryScopeId(host: String, httpsPort: Int, appUuid: String): String? =
        scopeId(host, httpsPort, "recovery-app-v1", appUuid, appUuid)

    fun fromRecoveryReceipt(
        scopeId: String,
        receipt: PolarisSessionStatus.RecoveryReceipt,
        nowEpochMs: Long
    ): DoctorActionReceipt? {
        if (scopeId.isBlank() || receipt.runId.isBlank() || receipt.appUuid.isBlank()) return null
        val message = receipt.message.ifBlank { receipt.error }
        return DoctorActionReceipt(
            scopeId = scopeId,
            runId = receipt.runId.take(MAX_FIELD_LENGTH),
            state = receipt.normalizedState.take(MAX_FIELD_LENGTH),
            message = message.take(MAX_FIELD_LENGTH),
            verificationActionId = receipt.verificationActionId.take(MAX_FIELD_LENGTH),
            undoAvailable = receipt.undoAvailable,
            undoActionId = receipt.undoActionId.take(MAX_FIELD_LENGTH),
            appUuid = receipt.appUuid.take(MAX_FIELD_LENGTH),
            expiresAtEpochSeconds = receipt.expiresAt.coerceAtLeast(0L),
            updatedAtEpochMs = nowEpochMs.coerceAtLeast(0L)
        )
    }

    /**
     * Reconciles the transport-session receipt with Polaris's durable app-scoped record.
     *
     * Apply begins in an app-session scope. Once Polaris exposes the queued record, that
     * server receipt becomes authoritative and moves the local receipt into the durable
     * recovery scope. A successful Undo removes the server record, so its terminal local
     * receipt must move back to the current app-session scope instead of reloading the old
     * pre-reconstruction queued copy.
     */
    fun reconcileScope(
        preferences: SharedPreferences,
        currentReceipt: DoctorActionReceipt?,
        currentScopeId: String?,
        nextScopeId: String?,
        appSessionScopeId: String?,
        recoveryScopeId: String?,
        currentAppUuid: String,
        authoritativeRecovery: PolarisSessionStatus.RecoveryReceipt?,
        nowEpochMs: Long
    ): DoctorActionReceipt? {
        if (nextScopeId.isNullOrBlank()) return null
        val currentInScope = currentReceipt?.takeIf { it.scopeId == currentScopeId }

        if (authoritativeRecovery != null) {
            val reconstructed = fromRecoveryReceipt(
                scopeId = nextScopeId,
                receipt = authoritativeRecovery,
                nowEpochMs = nowEpochMs
            ) ?: return load(preferences, nextScopeId)
            val obsoleteScopes = buildSet {
                val appReceipt = appSessionScopeId
                    ?.takeIf { it != nextScopeId }
                    ?.let { scope ->
                        currentReceipt?.takeIf { it.scopeId == scope }
                            ?: load(preferences, scope)
                    }
                appReceipt?.takeIf {
                    !it.isTerminal &&
                        it.runId == reconstructed.runId &&
                        it.appUuid.isNotBlank() &&
                        it.appUuid.equals(reconstructed.appUuid, ignoreCase = true)
                }?.scopeId?.let(::add)
                currentInScope?.takeIf {
                    it.scopeId != nextScopeId &&
                        !it.isTerminal &&
                        it.runId == reconstructed.runId &&
                        it.appUuid.isNotBlank() &&
                        it.appUuid.equals(reconstructed.appUuid, ignoreCase = true)
                }?.scopeId?.let(::add)
            }
            saveReplacing(preferences, reconstructed, obsoleteScopes)
            return reconstructed
        }

        val storedRecovery = currentInScope?.takeIf { it.scopeId == recoveryScopeId }
            ?: recoveryScopeId
            ?.takeIf { it != nextScopeId }
            ?.let { load(preferences, it) }

        val matchingRecovery = storedRecovery?.takeIf {
            it.appUuid.isNotBlank() &&
                it.appUuid.equals(currentAppUuid, ignoreCase = true)
        }
        if (matchingRecovery?.isTerminal == true) {
            return matchingRecovery.copy(scopeId = nextScopeId).also {
                saveReplacing(preferences, it, setOf(matchingRecovery.scopeId))
            }
        }

        if (matchingRecovery != null) {
            // Polaris no longer exposes this queued record. Its absence is authoritative:
            // a client crash after host-side Undo must not resurrect either durable copy.
            val appReceipt = currentInScope?.takeIf { it.scopeId == nextScopeId }
                ?: load(preferences, nextScopeId)
            val staleScopes = buildSet {
                add(matchingRecovery.scopeId)
                appReceipt?.takeIf {
                    !it.isTerminal &&
                        it.appUuid.isNotBlank() &&
                        it.appUuid.equals(currentAppUuid, ignoreCase = true)
                }?.scopeId?.let(::add)
            }
            removeScopes(preferences, staleScopes)
            return appReceipt?.takeUnless { it.scopeId in staleScopes }
        }

        if (currentScopeId == nextScopeId) return currentInScope
        return load(preferences, nextScopeId)
    }

    private fun scopeId(
        host: String,
        httpsPort: Int,
        identityKind: String,
        appSessionId: String,
        gameUuid: String
    ): String? {
        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isBlank() || appSessionId.isBlank() || gameUuid.isBlank()) return null
        if (httpsPort !in 1..65_535) return null
        val components = listOf(
            SCOPE_VERSION,
            identityKind,
            normalizedHost,
            httpsPort.toString(),
            appSessionId,
            gameUuid
        )
        val encoded = components.map { component ->
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = try {
                encoder.encode(CharBuffer.wrap(component))
            } catch (_: CharacterCodingException) {
                return null
            }
            ByteArray(buffer.remaining()).also(buffer::get)
        }
        if (encoded.any { it.size > MAX_SCOPE_IDENTITY_LENGTH }) return null

        val hasher = MessageDigest.getInstance("SHA-256")
        encoded.forEach { component ->
            hasher.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(component.size).array())
            hasher.update(component)
        }
        val digest = hasher.digest()
        return buildString(digest.size * 2) {
            digest.forEach { value ->
                val byte = value.toInt() and 0xff
                append(HEX[byte ushr 4])
                append(HEX[byte and 0x0f])
            }
        }
    }

    fun applyResult(
        previous: DoctorActionReceipt?,
        scopeId: String,
        result: PolarisDoctorActionResult,
        nowEpochMs: Long
    ): DoctorActionReceipt {
        val previousInScope = previous?.takeIf { it.scopeId == scopeId }
        val runId = result.runId.ifBlank { previousInScope?.runId.orEmpty() }
        val sameRun = previousInScope?.takeIf { it.runId == runId }
        val responseState = result.recoveryState.ifBlank {
            result.state.ifBlank { sameRun?.state.orEmpty() }
        }
        val terminal = responseState in TERMINAL_STATES
        val verificationActionId = when {
            terminal -> ""
            result.verificationActionId.isNotBlank() -> result.verificationActionId
            responseState == "watching" -> sameRun?.verificationActionId.orEmpty()
            else -> ""
        }
        val verificationAttemptCount = if (sameRun?.verificationPending == true) {
            (sameRun.verificationAttemptCount + 1).coerceAtMost(MAX_VERIFICATION_ATTEMPTS)
        } else {
            0
        }
        val verificationExhausted = !terminal &&
            verificationActionId.isNotBlank() &&
            verificationAttemptCount >= MAX_VERIFICATION_ATTEMPTS
        val state = if (verificationExhausted) "needs_attention" else responseState
        val verificationDelayMs = when {
            verificationActionId.isBlank() -> 0L
            result.verificationActionId.isNotBlank() ->
                result.verificationDelaySeconds.coerceAtLeast(1) * 1_000L
            else -> RETRY_DELAY_MS
        }
        val undone = state == "undone"
        val undoAvailable = when {
            undone -> false
            result.undoAvailable != null -> result.undoAvailable
            else -> sameRun?.undoAvailable == true
        }
        val undoActionId = when {
            !undoAvailable -> ""
            result.undoActionId.isNotBlank() -> result.undoActionId
            else -> sameRun?.undoActionId.orEmpty()
        }
        val safeNow = nowEpochMs.coerceAtLeast(0L)

        return DoctorActionReceipt(
            scopeId = scopeId,
            runId = runId.take(MAX_FIELD_LENGTH),
            state = state.take(MAX_FIELD_LENGTH),
            message = result.message.ifBlank { sameRun?.message.orEmpty() }.take(MAX_FIELD_LENGTH),
            verificationActionId = if (verificationExhausted) "" else verificationActionId.take(MAX_FIELD_LENGTH),
            verificationDueAtEpochMs = if (!verificationExhausted && verificationDelayMs > 0L) {
                safeNow + verificationDelayMs
            } else {
                0L
            },
            verificationFailureCount = 0,
            verificationAttemptCount = verificationAttemptCount,
            undoAvailable = undoAvailable,
            undoActionId = undoActionId.take(MAX_FIELD_LENGTH),
            appUuid = result.appUuid.ifBlank { sameRun?.appUuid.orEmpty() }.take(MAX_FIELD_LENGTH),
            expiresAtEpochSeconds = result.expiresAt.takeIf { it > 0L }
                ?: sameRun?.expiresAtEpochSeconds
                ?: 0L,
            updatedAtEpochMs = safeNow
        )
    }

    fun deferVerification(receipt: DoctorActionReceipt, nowEpochMs: Long): DoctorActionReceipt {
        if (!receipt.verificationPending) return receipt
        val safeNow = nowEpochMs.coerceAtLeast(0L)
        val nextFailureCount = (receipt.verificationFailureCount + 1).coerceAtMost(MAX_VERIFICATION_FAILURES)
        if (nextFailureCount >= MAX_VERIFICATION_FAILURES) {
            return receipt.copy(
                state = "needs_attention",
                verificationActionId = "",
                verificationDueAtEpochMs = 0L,
                verificationFailureCount = nextFailureCount,
                updatedAtEpochMs = safeNow
            )
        }
        val retryDelayMs = RETRY_DELAY_MS * (1L shl (nextFailureCount - 1))
        return receipt.copy(
            verificationDueAtEpochMs = safeNow + retryDelayMs,
            verificationFailureCount = nextFailureCount,
            updatedAtEpochMs = safeNow
        )
    }

    fun stopVerification(
        receipt: DoctorActionReceipt,
        result: PolarisDoctorActionResult,
        nowEpochMs: Long
    ): DoctorActionReceipt {
        return receipt.copy(
            state = result.state.takeIf { it in TERMINAL_STATES } ?: "needs_attention",
            message = result.message.ifBlank { result.error.ifBlank { receipt.message } }.take(MAX_FIELD_LENGTH),
            verificationActionId = "",
            verificationDueAtEpochMs = 0L,
            verificationFailureCount = MAX_VERIFICATION_FAILURES,
            undoAvailable = false,
            undoActionId = "",
            updatedAtEpochMs = nowEpochMs.coerceAtLeast(0L)
        )
    }

    fun retireUndo(
        receipt: DoctorActionReceipt,
        result: PolarisDoctorActionResult,
        nowEpochMs: Long
    ): DoctorActionReceipt = receipt.copy(
        state = "needs_attention",
        message = result.message.ifBlank { result.error.ifBlank { receipt.message } }.take(MAX_FIELD_LENGTH),
        verificationActionId = "",
        verificationDueAtEpochMs = 0L,
        verificationFailureCount = MAX_VERIFICATION_FAILURES,
        undoAvailable = false,
        undoActionId = "",
        updatedAtEpochMs = nowEpochMs.coerceAtLeast(0L)
    )

    fun nextVerificationDelayMs(receipt: DoctorActionReceipt, nowEpochMs: Long): Long {
        if (!receipt.verificationPending) return -1L
        return (receipt.verificationDueAtEpochMs - nowEpochMs).coerceAtLeast(0L)
    }

    fun requestIsCurrent(
        current: DoctorActionReceipt?,
        activeScopeId: String?,
        activeGeneration: Long,
        request: DoctorActionRequestIdentity
    ): Boolean {
        if (activeScopeId != request.scopeId || activeGeneration != request.generation) return false
        if (request.runId.isBlank()) return true
        return current?.scopeId == request.scopeId && current.runId == request.runId
    }

    fun undoIsAuthorized(
        current: DoctorActionReceipt?,
        candidate: DoctorActionReceipt,
        activeScopeId: String?,
        validatedScopeId: String?,
        canAdjustHostTuning: Boolean
    ): Boolean = canAdjustHostTuning &&
        activeScopeId != null &&
        validatedScopeId == activeScopeId &&
        current != null &&
        current.scopeId == activeScopeId &&
        candidate.scopeId == activeScopeId &&
        current.runId == candidate.runId &&
        current.undoAvailable &&
        current.undoActionId.isNotBlank() &&
        current.undoActionId == candidate.undoActionId

    fun responseMatches(
        current: DoctorActionReceipt?,
        activeScopeId: String?,
        activeGeneration: Long,
        request: DoctorActionRequestIdentity,
        result: PolarisDoctorActionResult
    ): Boolean {
        if (!result.status || !responseIdentityMatches(current, activeScopeId, activeGeneration, request, result)) {
            return false
        }
        return true
    }

    fun responseIdentityMatches(
        current: DoctorActionReceipt?,
        activeScopeId: String?,
        activeGeneration: Long,
        request: DoctorActionRequestIdentity,
        result: PolarisDoctorActionResult
    ): Boolean {
        if (!requestIsCurrent(current, activeScopeId, activeGeneration, request)) return false
        val responseRunId = result.runId.trim()
        return if (request.runId.isBlank()) {
            responseRunId.isNotBlank()
        } else {
            responseRunId.isBlank() || responseRunId == request.runId
        }
    }

    fun successfulLegacyNewRunResult(
        request: DoctorActionRequestIdentity,
        result: PolarisDoctorActionResult
    ): Boolean = request.runId.isBlank() && result.status && result.runId.isBlank()

    fun validationGenerationIsCurrent(activeGeneration: Long, requestGeneration: Long): Boolean =
        activeGeneration == requestGeneration

    fun visibleReceipt(
        receipt: DoctorActionReceipt?,
        activeScopeId: String?,
        validatedScopeId: String?
    ): DoctorActionReceipt? {
        if (activeScopeId.isNullOrBlank() || activeScopeId != validatedScopeId) return null
        return receipt?.takeIf { it.scopeId == activeScopeId }
    }

    fun save(preferences: SharedPreferences, receipt: DoctorActionReceipt) {
        saveReplacing(preferences, receipt, obsoleteScopeIds = emptySet())
    }

    private fun saveReplacing(
        preferences: SharedPreferences,
        receipt: DoctorActionReceipt,
        obsoleteScopeIds: Set<String>
    ) {
        if (receipt.scopeId.isBlank() || receipt.runId.isBlank()) return
        val savedAt = System.currentTimeMillis().coerceAtLeast(0L)
        val json = JSONObject().apply {
            put("scope_id", receipt.scopeId)
            put("run_id", receipt.runId)
            put("state", receipt.state)
            put("message", receipt.message)
            put("verification_action_id", receipt.verificationActionId)
            put("verification_due_at_epoch_ms", receipt.verificationDueAtEpochMs)
            put("verification_failure_count", receipt.verificationFailureCount)
            put("verification_attempt_count", receipt.verificationAttemptCount)
            put("undo_available", receipt.undoAvailable)
            put("undo_action_id", receipt.undoActionId)
            put("app_uuid", receipt.appUuid)
            put("expires_at_epoch_seconds", receipt.expiresAtEpochSeconds)
            put("updated_at_epoch_ms", receipt.updatedAtEpochMs)
            put("saved_at_epoch_ms", savedAt)
        }
        val currentKey = keyForScope(receipt.scopeId)
        val editor = preferences.edit().putString(currentKey, json.toString())
        val obsoleteKeys = obsoleteScopeIds
            .filter { it.isNotBlank() && it != receipt.scopeId }
            .mapTo(mutableSetOf(), ::keyForScope)
        obsoleteKeys.forEach(editor::remove)

        val savedKeys = preferences.all.keys
            .filter {
                it.startsWith(RECEIPT_KEY_PREFIX) &&
                    it != currentKey &&
                    it !in obsoleteKeys
            }
            .map { key ->
                val timestamp = runCatching {
                    JSONObject(preferences.getString(key, "{}") ?: "{}")
                        .optLong("saved_at_epoch_ms", 0L)
                }.getOrDefault(0L)
                key to timestamp
            }
            .plus(currentKey to savedAt)
            .sortedBy { it.second }
        savedKeys.take((savedKeys.size - MAX_RECEIPTS).coerceAtLeast(0)).forEach { (key, _) ->
            if (key != currentKey) editor.remove(key)
        }
        editor.commit()
    }

    private fun removeScopes(preferences: SharedPreferences, scopeIds: Set<String>) {
        if (scopeIds.isEmpty()) return
        val editor = preferences.edit()
        scopeIds.filter { it.isNotBlank() }.forEach { editor.remove(keyForScope(it)) }
        editor.commit()
    }

    fun load(preferences: SharedPreferences, scopeId: String): DoctorActionReceipt? {
        if (scopeId.isBlank()) return null
        val encoded = preferences.getString(keyForScope(scopeId), null) ?: return null
        return runCatching {
            val json = JSONObject(encoded)
            val storedScope = json.optString("scope_id").take(MAX_FIELD_LENGTH)
            if (storedScope != scopeId) return@runCatching null
            val runId = json.optString("run_id").take(MAX_FIELD_LENGTH)
            if (runId.isBlank()) return@runCatching null
            DoctorActionReceipt(
                scopeId = storedScope,
                runId = runId,
                state = json.optString("state").take(MAX_FIELD_LENGTH),
                message = json.optString("message").take(MAX_FIELD_LENGTH),
                verificationActionId = json.optString("verification_action_id").take(MAX_FIELD_LENGTH),
                verificationDueAtEpochMs = json.optLong("verification_due_at_epoch_ms", 0L).coerceAtLeast(0L),
                verificationFailureCount = json.optInt("verification_failure_count", 0)
                    .coerceIn(0, MAX_VERIFICATION_FAILURES),
                verificationAttemptCount = json.optInt("verification_attempt_count", 0)
                    .coerceIn(0, MAX_VERIFICATION_ATTEMPTS),
                undoAvailable = json.optBoolean("undo_available", false),
                undoActionId = json.optString("undo_action_id").take(MAX_FIELD_LENGTH),
                appUuid = json.optString("app_uuid").take(MAX_FIELD_LENGTH),
                expiresAtEpochSeconds = json.optLong("expires_at_epoch_seconds", 0L).coerceAtLeast(0L),
                updatedAtEpochMs = json.optLong("updated_at_epoch_ms", 0L).coerceAtLeast(0L)
            )
        }.getOrNull()
    }

    fun clear(preferences: SharedPreferences, scopeId: String) {
        if (scopeId.isNotBlank()) preferences.edit().remove(keyForScope(scopeId)).apply()
    }

    private fun keyForScope(scopeId: String): String = RECEIPT_KEY_PREFIX + scopeId
}

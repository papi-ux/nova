package com.papi.nova.manager

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Diagnostic equality only. The canonical byte recipe is documented in live-bitrate-scope.md. */
internal object NovaBitrateScopeFingerprint {
    fun of(hostInstance: String?, appSessionId: String?, generation: Long?): String? {
        if (hostInstance.isNullOrBlank() || appSessionId.isNullOrBlank() || generation == null || generation <= 0) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("nova-live-bitrate-scope-v1\u0000".toByteArray(Charsets.US_ASCII))
        for (part in listOf(hostInstance, appSessionId, generation.toString())) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

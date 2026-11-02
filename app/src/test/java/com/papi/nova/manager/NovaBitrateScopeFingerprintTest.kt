package com.papi.nova.manager

import org.junit.Assert.*
import org.junit.Test

class NovaBitrateScopeFingerprintTest {
    @Test fun canonicalUtf8ByteLengthsAndBigEndianEncodingMatchIndependentVector() {
        // Python hashlib/struct vector, documented with exact input bytes.
        assertEquals("914fa66fea778731b6dd56771f5e2e9dfafb07ae607f9eaa7ac202321cccb268",
            NovaBitrateScopeFingerprint.of("host-α", "session/β", 7))
        assertNotEquals(NovaBitrateScopeFingerprint.of("a", "bc", 7),
            NovaBitrateScopeFingerprint.of("ab", "c", 7))
        assertNotEquals(NovaBitrateScopeFingerprint.of("a", "bc", 7),
            NovaBitrateScopeFingerprint.of("a", "bc", 8))
    }

    @Test fun incompleteScopeHasNoFingerprintOrFallback() {
        assertNull(NovaBitrateScopeFingerprint.of(null, "app", 7))
        assertNull(NovaBitrateScopeFingerprint.of(" ", "app", 7))
        assertNull(NovaBitrateScopeFingerprint.of("host", "", 7))
        assertNull(NovaBitrateScopeFingerprint.of("host", null, 7))
        assertNull(NovaBitrateScopeFingerprint.of("host", "app", 0))
        assertNull(NovaBitrateScopeFingerprint.of("host", "app", -1))
        assertNull(NovaBitrateScopeFingerprint.of("host", "app", null))
    }
}

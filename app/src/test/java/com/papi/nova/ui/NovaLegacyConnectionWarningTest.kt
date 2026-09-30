package com.papi.nova.ui

import com.papi.nova.api.PolarisSessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-game #9: the legacy connection warning gives way where the host tunes or diagnoses the stream. */
class NovaLegacyConnectionWarningTest {
    @Test
    fun theWarningGivesWayWhereLiveTuningOrDoctorSpeaks() {
        assertFalse("with no host reading it shows as it always has", NovaLegacyConnectionWarning.suppressed(null))
        assertFalse(NovaLegacyConnectionWarning.suppressed(PolarisSessionStatus(state = "streaming")))
        assertTrue(
            "Live Tuning already lowers the bitrate itself",
            NovaLegacyConnectionWarning.suppressed(
                PolarisSessionStatus(state = "streaming", tuning = PolarisSessionStatus.TuningStatus(adaptiveBitrateEnabled = true)),
            ),
        )
        assertTrue(
            "Doctor reads the stream, and may say not to lower quality",
            NovaLegacyConnectionWarning.suppressed(
                PolarisSessionStatus(
                    state = "streaming",
                    doctor = PolarisSessionStatus.DoctorStatus(available = true, version = 2, resultId = "doctor-1"),
                ),
            ),
        )
    }
}

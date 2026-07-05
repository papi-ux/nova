package com.papi.nova.utils

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaExternalDisplayRoutingSourceGuardTest {
    @Test
    fun serverHelperExposesCompanionDisplaySelectionAdapter() {
        val source = File("src/main/java/com/papi/nova/utils/ServerHelper.kt").readText()

        assertTrue(source.contains("AndroidDisplayCandidateMap"))
        assertTrue(source.contains("buildDisplayCandidateMap"))
        assertTrue(source.contains("getAndroidCompanionDisplay"))
        assertTrue(source.contains("selectCompanion"))
    }
}

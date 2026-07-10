package com.papi.nova.preferences

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaUpdateStartupPromptIntegrationTest {
    @Test
    fun pcViewRunsAutomaticUpdateCheckAfterMainUiIsReady() {
        val source = File("src/main/java/com/papi/nova/PcView.kt").readText()

        assertTrue(source.contains("private fun maybeRunAutomaticNovaUpdateCheck()"))
        assertTrue(source.contains("maybeRunAutomaticNovaUpdateCheck()"))
        assertTrue(source.contains("NovaUpdatePromptPreferences.shouldRunAutomaticCheck"))
        assertTrue(source.contains("NovaUpdateChecker.checkLatest"))
        assertTrue(source.contains("NovaUpdatePromptPreferences.shouldShowAutomaticPrompt"))
        assertTrue(source.contains("NovaUpdatePromptPreferences.skipRelease"))
    }
}

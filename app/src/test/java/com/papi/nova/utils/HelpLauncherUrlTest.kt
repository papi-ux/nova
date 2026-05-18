package com.papi.nova.utils

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpLauncherUrlTest {
    @Test
    fun setupGuideLaunchesNovaGithubInsteadOfMoonlightDocs() {
        val source = String(
            Files.readAllBytes(Path.of("src/main/java/com/papi/nova/utils/HelpLauncher.kt")),
            StandardCharsets.UTF_8
        )
        val setupGuide = source.substring(
            source.indexOf("fun launchSetupGuide"),
            source.indexOf("fun launchTroubleshooting")
        )

        assertTrue(
            "Help should open Nova's GitHub project",
            setupGuide.contains("https://github.com/papi-ux/nova")
        )
        assertFalse(
            "Help should no longer send the dashboard button to Moonlight setup docs",
            setupGuide.contains("moonlight-stream/moonlight-docs")
        )
    }
}

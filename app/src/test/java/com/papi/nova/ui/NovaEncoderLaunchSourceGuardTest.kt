package com.papi.nova.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class NovaEncoderLaunchSourceGuardTest {

    @Test
    fun playSetupChoiceTravelsOnlyAsSessionScopedExactLaunchAuthority() {
        val detail = source("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt")
        val library = source("src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt")
        val helper = source("src/main/java/com/papi/nova/utils/ServerHelper.kt")
        val game = source("src/main/java/com/papi/nova/Game.kt")
        val nvhttp = source("src/main/java/com/papi/nova/nvstream/http/NvHTTP.kt")
        val shortcut = source("src/main/java/com/papi/nova/ShortcutTrampoline.kt")

        assertTrue(detail.contains("row = NovaPlaySetupRow.ENCODER,"))
        assertTrue(detail.contains("encoderBackend = optimizationEncoder"))
        assertTrue(detail.contains(".put(RESULT_KEY_ENCODER_BACKEND, encoderBackend)"))
        assertTrue(library.contains("encoderBackend = request.optString("))
        assertTrue(library.contains("encoderBackend = encoderBackend,"))
        assertTrue(helper.contains("gameIntent.putExtra(Game.EXTRA_ENCODER_BACKEND, encoderBackend)"))
        assertTrue(game.contains("NovaEncoderLaunchContract.honors("))
        assertTrue(game.contains("encoderBackend = encoderBackend)"))
        assertTrue(game.contains(".setExpectedEncoder(if (launchResolvedProfileTrusted) encoderBackend else \"\")"))
        assertTrue(nvhttp.contains("&encoderBackend="))
        assertTrue(nvhttp.contains("&expectedEncoder="))
        assertTrue(shortcut.contains("NovaEncoderBackendOverrides.loadAvailable("))
        assertTrue(shortcut.contains("if (clientSettings != null)"))
        assertTrue(shortcut.contains("loadAvailable(this, polarisGame, clientSettings).orEmpty()"))
        assertTrue(shortcut.contains("encoderBackend = readyLaunchPlan.encoderBackend"))
    }

    @Test
    fun hostDefaultAndAutoRemainDifferentChoices() {
        val detail = source("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt")
        val override = source("src/main/java/com/papi/nova/ui/NovaEncoderBackendOverrides.kt")
        val nvhttp = source("src/main/java/com/papi/nova/nvstream/http/NvHTTP.kt")

        assertTrue(detail.contains("onSelect = { chooseEncoderBackend(null) }"))
        assertTrue(detail.contains("overridden = selectedEncoder.isNotBlank()"))
        assertTrue(override.contains("fun clear(context: Context, game: PolarisGame)"))
        assertTrue(nvhttp.contains("streamConfig.getEncoderBackend().isNotBlank()"))
    }

    private fun source(path: String): String =
        String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)
}

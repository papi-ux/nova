package com.papi.nova

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarisTypedRejectionSourceGuardTest {

    @Test
    fun currentPolarisRejectionsStayFailClosedAndReachTheUser() {
        val api = readSource("src/main/java/com/papi/nova/api/PolarisApiClient.kt")
        val game = readSource("src/main/java/com/papi/nova/Game.kt")
        val detail = readSource("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt")
        val sync = readSource("src/main/java/com/papi/nova/ui/NovaPolarisSyncEngine.kt")

        assertTrue(api.contains("parseTypedRejection(it.code, body, mutationEnvelope = false)"))
        assertTrue(api.contains("parseTypedRejection(response.code, responseBody, mutationEnvelope = true)"))
        assertTrue(game.contains("return blocked(e.rejection.error)"))
        assertTrue(game.contains("policyMessage ?: getString(R.string.nova_launch_deterministic_host_required)"))
        assertTrue(detail.contains("NovaSnackbar.showError(this@NovaGameDetailActivity, e.rejection.error)"))
        assertTrue(sync.contains("rejectionMessage = e.rejection.error"))
        assertTrue(sync.contains("onTextMessage.invoke(exactRejection, true)"))
    }

    private fun readSource(relative: String): String =
        String(Files.readAllBytes(Path.of(relative)), StandardCharsets.UTF_8)
}

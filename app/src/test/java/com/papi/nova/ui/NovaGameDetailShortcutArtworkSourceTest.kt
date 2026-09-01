package com.papi.nova.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaGameDetailShortcutArtworkSourceTest {
    @Test
    fun pinShortcutResolvesTheSelectedGameIconOffMainThread() {
        val source = File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText()
        val pinBlock = source
            .substringAfter("onPinShortcut = pinShortcut@ {")
            .substringBefore("artworkState = artworkState")

        assertTrue(pinBlock.contains("val pinnedGame = currentGame"))
        assertTrue(pinBlock.contains("lifecycleScope.launch"))
        assertTrue(pinBlock.contains("withContext(Dispatchers.IO)"))
        assertTrue(pinBlock.contains("apiClient.loadShortcutIcon(pinnedGame)"))
        assertTrue(pinBlock.contains("iconBits = iconBits"))
        assertFalse(pinBlock.contains("iconBits = null"))
        assertTrue(source.contains("private var pinShortcutJob: Job? = null"))
        assertTrue(pinBlock.contains("if (pinShortcutJob?.isActive == true) return@pinShortcut"))
        assertTrue(pinBlock.contains("pinShortcutJob = lifecycleScope.launch"))
        assertTrue(pinBlock.contains("finally"))
        assertTrue(pinBlock.contains("pinShortcutJob = null"))
        val destroyBlock = source
            .substringAfter("override fun onDestroy() {")
            .substringBefore("super.onDestroy()")
        assertTrue(destroyBlock.contains("pinShortcutJob?.cancel()"))
    }
}

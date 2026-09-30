package com.papi.nova

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Results on Nova's own screens are said in place, on a Notice in the edge panel, or by the
 * screen's own state, never in a Toast that floats and is gone before it can be read (audit X2).
 * These screens and helpers once floated one. What is left elsewhere is in-stream, where the
 * in-game pass owns it, the Legacy settings awaiting their restyle, or background work with no
 * screen of its own.
 */
class NoFloatingResultsSourceGuardTest {
    @Test
    fun theseScreensFloatNoToast() {
        val offenders = RESULTS_IN_PLACE.filter { path ->
            val text = File(MAIN, path).readText()
            text.contains("Toast.makeText") || text.contains("import android.widget.Toast")
        }
        assertEquals("say results in place, not in a Toast", emptyList<String>(), offenders)
    }

    private companion object {
        val MAIN = File("src/main/java/com/papi/nova")

        val RESULTS_IN_PLACE = listOf(
            "AppView.kt",
            "DebugInfoActivity.kt",
            "PcView.kt",
            "preferences/AddComputerManually.kt",
            "preferences/NovaUpdateInstaller.kt",
            "profiles/ProfilesAdapter.kt",
            "ui/NovaGameDetailActivity.kt",
            "ui/NovaLibraryActivity.kt",
            "ui/NovaLibraryPanels.kt",
            "utils/ServerHelper.kt",
        )
    }
}

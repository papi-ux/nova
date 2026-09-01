package com.papi.nova.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A resolution choice answers "how should this run on that display". Changing which
 * display a game runs on — a new launch mode, a full-panel mode pick, or dropping back
 * to the host default — changes the question, so the answer must not carry over.
 *
 * Each of those three entry points already cleared the in-memory chosenResolution, but
 * left the durable NovaResolutionOverrides entry untouched: the next screen open re-read
 * the stale saved id and the cleared choice silently came back, as if it had never been
 * cleared. Every entry point that resets chosenResolution to null must also clear the
 * durable override in the same breath. Guarded at the source since these are private
 * closures inside NovaGameDetailActivity.onCreate, not reachable from a JVM unit test.
 */
class NovaResolutionOverrideClearedOnDisplayChangeSourceGuardTest {
    private val source =
        File("src/main/java/com/papi/nova/ui/NovaGameDetailActivity.kt").readText()

    private fun assertClearsDurableOverrideAlongsideInMemoryState(functionSignature: String) {
        val functionStart = source.indexOf(functionSignature)
        assertTrue("$functionSignature must exist in NovaGameDetailActivity.kt", functionStart >= 0)

        val nullAssignment = source.indexOf("chosenResolution = null", functionStart)
        assertTrue(
            "$functionSignature must reset chosenResolution to null.",
            nullAssignment in functionStart until (functionStart + 2000),
        )

        val clearCall = source.indexOf("clearResolutionOverride(currentGame)", functionStart)
        assertTrue(
            "$functionSignature clears chosenResolution in memory but must also call " +
                "clearResolutionOverride(currentGame) — otherwise the saved choice id survives and " +
                "silently reapplies the next time this screen opens.",
            clearCall in functionStart until (functionStart + 2000),
        )
    }

    @Test
    fun selectLaunchModeClearsTheDurableResolutionOverride() {
        assertClearsDurableOverrideAlongsideInMemoryState("fun selectLaunchMode(mode: String)")
    }

    @Test
    fun pickPlayModeClearsTheDurableResolutionOverride() {
        assertClearsDurableOverrideAlongsideInMemoryState("fun pickPlayMode(mode: String)")
    }

    @Test
    fun pickHostDefaultClearsTheDurableResolutionOverride() {
        assertClearsDurableOverrideAlongsideInMemoryState("fun pickHostDefault()")
    }
}

package com.papi.nova.manager

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launcher target grammar, pinned to the same vectors the host and the
 * worker are pinned to. The file is a copy of polaris tests/fixtures/launcher-targets.json;
 * a target Nova refuses never reaches the host, and one it mangles reaches it wrong.
 *
 * Nova carries a target rather than deciding which family may use it, so a
 * target any family accepts is accepted here. The gamescope vectors are the
 * host's own test workload and are not a Space a person opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkerLaunchContractTargetTest {
    private fun vectors(): JSONArray =
        JSONArray(javaClass.getResource("/launcher-targets.json")!!.readText())

    @Test fun acceptsEveryTargetAHostMaySend() {
        val fixtures = vectors()
        var accepted = 0
        for (i in 0 until fixtures.length()) {
            val vector = fixtures.getJSONObject(i)
            if (vector.getString("profile") == "gamescope" || !vector.getBoolean("accepted")) continue
            accepted++
            val target = vector.getString("target")
            assertEquals("${target}: ${vector.getString("why")}", true, WorkerLaunchContract.validTarget(target))
        }
        assertEquals("the vectors must carry targets to accept", true, accepted > 0)
    }

    @Test fun knowsTheEntryThatOpensALauncherItself() {
        assertTrue(WorkerLaunchContract.isLauncherEntry("big-picture-v1"))
        assertTrue(WorkerLaunchContract.isLauncherEntry("library-v1"))
        for (title in listOf("620", "epic.AlanWake2", "id.12", "", "library-v2", null)) {
            assertFalse("$title is one title, not a launcher", WorkerLaunchContract.isLauncherEntry(title))
        }
    }

    @Test fun refusesWhatNoLauncherWouldEverSend() {
        // A target one family refuses can be another's: "1" is not a Lutris
        // target but is a perfectly good Steam app id, and an identity does not
        // say which family it belongs to. So the refusals asserted here are the
        // ones no family's grammar accepts.
        for (target in listOf("", "0", "0440", "4294967296", "big-picture-v2", "library-v2",
                              "id.", "id.0", "epic.", "epic.bad name", "sideload.a.b",
                              "store.Thing", "../steam", "440 ")) {
            assertEquals(target, false, WorkerLaunchContract.validTarget(target))
        }
    }

    @Test fun keepsTheDotsInsideALauncherTarget() {
        // space.<profile>.<target>, and a Heroic target carries a dot of its
        // own: splitting on every dot dropped the title and failed the library.
        val identity = WorkerLaunchContract.libraryIdentity("space.15ab1141-72db-4e28-a138-463a0dd1d98a.epic.AlanWake2")
        assertEquals("15ab1141-72db-4e28-a138-463a0dd1d98a" to "epic.AlanWake2", identity)
        assertEquals("papi" to "library-v1", WorkerLaunchContract.libraryIdentity("space.papi.library-v1"))
        assertEquals("papi" to "big-picture-v1", WorkerLaunchContract.libraryIdentity("space.papi.big-picture-v1"))
        assertEquals("papi" to "id.42", WorkerLaunchContract.libraryIdentity("space.papi.id.42"))
        assertNull(WorkerLaunchContract.libraryIdentity("space.papi"))
        assertNull(WorkerLaunchContract.libraryIdentity("space.papi.epic."))
        assertNull(WorkerLaunchContract.libraryIdentity("desktop.papi.440"))
    }
}

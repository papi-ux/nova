package com.papi.nova

import com.papi.nova.manager.WorkerLaunchContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Space the launch policy gate refused offered Retry as a plain relaunch of the stream, which
 * skipped the Space's own check, because the gate refuses before the launch is set up and the page
 * read the Space from that setup (X1). The refusal now says whether the app it asked for was a
 * Space before it builds the page, which reads it for its Retry. Driven through the function the
 * gate calls, on a Game as the gate leaves it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameLaunchPolicyGateSpaceTest {
    private fun refusedAtTheGate(appUuid: String?, appId: Int): Game {
        val game = Robolectric.buildActivity(Game::class.java).get()
        setField(game, "appUUID", appUuid)
        setField(game, "appId", appId)
        game.refuseAtLaunchPolicyGate("The host refused this launch.")
        return game
    }

    @Test
    fun aSpaceRefusedAtTheGateIsASpaceSessionForTheIssuePage() {
        assertTrue("the Space's own app", refusedAtTheGate(WorkerLaunchContract.APP_UUID, 0).isSpaceSession())
        assertTrue("by its app id when it has no id of its own", refusedAtTheGate(null, WorkerLaunchContract.APP_ID).isSpaceSession())
    }

    @Test
    fun aGameRefusedAtTheGateIsNot() {
        assertFalse(refusedAtTheGate("992FF124-4652-5708-501D-EDDBBB80EA8E", 7).isSpaceSession())
    }

    private fun setField(game: Game, name: String, value: Any?) {
        Game::class.java.getDeclaredField(name).apply { isAccessible = true }.set(game, value)
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}

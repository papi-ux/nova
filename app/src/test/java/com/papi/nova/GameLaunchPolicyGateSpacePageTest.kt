package com.papi.nova

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.manager.WorkerLaunchContract
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.novaSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page a gate refusal actually builds, and the Space retry the player can run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameLaunchPolicyGateSpacePageTest {
    private val pc = "5E1F5A0B-2C3D-4E5F-8A9B-0C1D2E3F4A5B"

    private fun pageAfterTheGate(appUuid: String?, appId: Int): Pair<Game, NovaStatePage.Problem> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), Game::class.java).putExtra(Game.EXTRA_PC_UUID, pc)
        val game = Robolectric.buildActivity(Game::class.java, intent).get()
        setField(game, "appUUID", appUuid)
        setField(game, "appId", appId)
        game.refuseAtLaunchPolicyGate("The host refused this launch.")
        return game to (game.novaSurfaces.states.value.last() as NovaStatePage.Problem)
    }

    @Test
    fun aSpaceRefusedAtTheGateShowsTheSpacesPageWithTheSpacesRetry() {
        val (game, page) = pageAfterTheGate(WorkerLaunchContract.APP_UUID, 0)
        assertEquals("the Space's page", game.getString(R.string.nova_space_launch_issue_title), page.title)
        assertEquals("the Space's retry", game.getString(R.string.nova_space_launch_issue_retry), page.primary.label)
    }

    @Test
    fun theSpacePagesRetryHandsTheRetryToTheLibrary() {
        val (game, page) = pageAfterTheGate(WorkerLaunchContract.APP_UUID, 0)
        page.primary.run()
        assertTrue("the library is asked to retry the Space", NovaSpaceRetrySignal.consume(game, pc, null))
        assertTrue("and the stream window closes for it", game.isFinishing)
    }

    @Test
    fun aGameRefusedAtTheGateShowsThePlainPage() {
        val (game, page) = pageAfterTheGate("992FF124-4652-5708-501D-EDDBBB80EA8E", 7)
        assertEquals(game.getString(R.string.nova_launch_issue_title), page.title)
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

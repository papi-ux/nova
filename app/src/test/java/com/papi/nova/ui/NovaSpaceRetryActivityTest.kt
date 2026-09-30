package com.papi.nova.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.papi.nova.Game
import com.papi.nova.NovaSpaceRetrySignal
import com.papi.nova.R
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSpace
import com.papi.nova.api.PolarisSpaces
import com.papi.nova.api.PolarisGameJson
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.NovaStatePage
import com.papi.nova.ui.panel.novaSurfaces
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Actual issue-page producer through Library.onResume, a fresh host check and the launch intent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NovaSpaceRetryActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val pc = "retry-fixture-pc"
    private val host = "127.0.0.1"
    private fun retryFromGame(identity: String) {
        val game = Robolectric.buildActivity(Game::class.java,
            Intent(context, Game::class.java).putExtra(Game.EXTRA_PC_UUID, pc).putExtra(Game.EXTRA_HOST, host)).get()
        Game::class.java.getDeclaredField("appUUID").apply { isAccessible = true; set(game, identity) }
        game.refuseAtLaunchPolicyGate("Host refused the launch.")
        (game.novaSurfaces.states.value.last() as NovaStatePage.Problem).primary.run()
        assertTrue(game.isFinishing)
    }

    @Test fun aPerTitleRetryReturnsToThatTitleInAMixedLibraryAfterAFreshCheck() =
        checkRetry("space.living-room.123", removed = false)

    @Test fun aLauncherSentinelRetryKeepsItsIdentity() =
        checkRetry("space.living-room.big-picture-v1", removed = false)

    @Test fun aRemovedTitleSaysWhyAndDoesNotOpenADifferentTitle() =
        checkRetry("space.living-room.123", removed = true)

    private fun checkRetry(identity: String, removed: Boolean) {
        context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val api = mock(PolarisApiClient::class.java)
        val target = PolarisGame(id = identity, name = "Target")
        val other = PolarisGame(id = "space.living-room.456", name = "Other")
        val games = if (removed) listOf(other) else listOf(other, target)
        `when`(api.getAllGames()).thenReturn(games)
        `when`(api.getSpaces()).thenReturn(PolarisSpaces(true, true, true, "living-room",
            listOf(PolarisSpace("living-room", "Living Room", "ready", true))))
        val intent = Intent(context, NovaLibraryActivity::class.java)
            .putExtra(NovaLibraryActivity.EXTRA_HOST, host)
            .putExtra(NovaLibraryActivity.EXTRA_PC_UUID, pc)
            .putExtra(NovaLibraryActivity.EXTRA_HTTP_PORT, 9)
            .putExtra(NovaLibraryActivity.EXTRA_HTTPS_PORT, 9)
        val controller = Robolectric.buildActivity(NovaLibraryActivity::class.java, intent).create()
        val activity = controller.get()
        var launched: Intent? = null
        // Capture the real consumer's launch intent without depending on Robolectric's
        // ActivityResult instrumentation, which does not create the destination Activity.
        val launcher = object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) { launched = input }
            override fun unregister() {}
            override fun getContract() = ActivityResultContracts.StartActivityForResult()
        }
        NovaLibraryActivity::class.java.getDeclaredField("gameDetailLauncher").apply {
            isAccessible = true; set(activity, launcher)
        }
        try {
            // Finish the create-time load before replacing its client, so a late failure
            // cannot overwrite the catalog supplied by the controlled host below.
            val loadDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (state<Boolean>(activity, "isInitialLoading").value && System.nanoTime() < loadDeadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertFalse(state<Boolean>(activity, "isInitialLoading").value)
            NovaLibraryActivity::class.java.getDeclaredField("apiClient").apply { isAccessible = true; set(activity, api) }
            NovaLibraryActivity::class.java.getDeclaredMethod("loadGames", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false, false)
            retryFromGame(identity)
            controller.start().resume().visible()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var failure: String? = null
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
                failure = state<String?>(activity, "launchErrorMessage").value
                if (launched != null || failure != null) break
                Thread.sleep(10)
            }
            if (removed) {
                assertNull(launched)
                assertEquals(context.getString(R.string.nova_space_retry_target_missing), failure)
            } else {
                assertNotNull("retry must reach the selected title; lifecycle=${activity.lifecycle.currentState}, " +
                    "finishing=${activity.isFinishing}, checked=${state<Boolean>(activity, "spacesChecked").value}, " +
                    "pending=${state<Boolean>(activity, "spaceOpenPending").value}, " +
                    "spaceError=${state<String?>(activity, "spacesError").value}, failure=$failure, " +
                    "calls=${mockingDetails(api).invocations.map { it.method.name }}", launched)
                assertEquals(NovaGameDetailActivity::class.java.name, launched!!.component!!.className)
                assertEquals(identity, PolarisGameJson.decode(launched!!.getStringExtra(NovaGameDetailActivity.EXTRA_GAME)!!)!!.id)
                verify(api, atLeast(2)).getSpaces()
            }
            assertNull("request is consumed once", NovaSpaceRetrySignal.consumeTarget(context, pc, host))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: NovaLibraryActivity, key: String): androidx.compose.runtime.MutableState<T> =
        NovaLibraryActivity::class.java.getDeclaredField(key + "\$delegate").run {
            isAccessible = true; get(activity) as androidx.compose.runtime.MutableState<T>
        }
}

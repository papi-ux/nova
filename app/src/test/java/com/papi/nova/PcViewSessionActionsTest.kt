package com.papi.nova

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Looper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.DoctorAiReadings
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.grid.PcGridAdapter
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.ui.NovaHostNowPlaying
import com.papi.nova.ui.novaHostNowPlaying
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaSurfaces
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [com.papi.nova.shadows.ShadowMoonBridge::class, com.papi.nova.shadows.ShadowGameManager::class])
class PcViewSessionActionsTest {
    private lateinit var controller: ActivityController<PcView>
    private lateinit var adapter: PcGridAdapter
    private lateinit var details: ComputerDetails
    private lateinit var end: android.view.View
    private lateinit var row: android.view.View

    @Before fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("GlPreferences", 0).edit().putString("Renderer", "TestRenderer")
            .putString("Fingerprint", Build.FINGERPRINT).commit()
        Shadows.shadowOf(context as Application).setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        val binder = mock(ComputerManagerService.ComputerManagerBinder::class.java)
        `when`(binder.uniqueId).thenReturn("synthetic-client")
        Shadows.shadowOf(context).setComponentNameAndServiceForBindService(ComponentName(context, ComputerManagerService::class.java), binder)
        controller = Robolectric.buildActivity(PcView::class.java).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        PcView::class.java.getDeclaredField("managerBinder").apply { isAccessible = true }.set(controller.get(), binder)
        adapter = PcView::class.java.getDeclaredField("pcGridAdapter").run { isAccessible = true; get(controller.get()) as PcGridAdapter }
        details = ComputerDetails().apply {
            uuid = "host-1"; name = "PC"; state = ComputerDetails.State.ONLINE
            pairState = PairingManager.PairState.PAIRED
            activeAddress = ComputerDetails.AddressTuple("127.0.0.1", 47989)
            httpsPort = 47984
            serverCert = mock(X509Certificate::class.java)
            runningGameId = 7; currentGameOwnedByClient = true
        }
        adapter.setItems(listOf(PcViewModel.ComputerObject(details)))
        adapter.setNowPlaying(mapOf("host-1" to novaHostNowPlaying(PolarisSessionStatus(
            state = "paused", gameId = 7, game = "Hades", sessionToken = "session-A", ownedByClient = true,
        ))!!.copy(host = NovaHostNowPlaying.Host(details.uuid, details.activeAddress!!.address, details.httpsPort, details.serverCert!!))))
        val holder = adapter.onCreateViewHolder(FrameLayout(controller.get()), 0)
        adapter.onBindViewHolder(holder, 0)
        row = holder.itemView
        end = holder.itemView.findViewById(R.id.host_end_session_button)
    }

    @After fun close() { controller.pause().stop().destroy() }

    private fun confirm(): NovaCommonPage.Confirm {
        end.performClick()
        return NovaSurfaces.of(controller.get()).panel.top as NovaCommonPage.Confirm
    }

    @Test fun theRowCarriesTheObservedSessionIntoTheGameIntent() {
        while (Shadows.shadowOf(controller.get()).nextStartedActivity != null) Unit
        val activity = controller.get()
        org.robolectric.util.ReflectionHelpers.setField(activity, "mBase",
            activity.baseContext.createDisplayContext(activity.windowManager.defaultDisplay))
        row.performClick()
        val intent = Shadows.shadowOf(controller.get()).nextStartedActivity!!
        assertEquals("session-A", intent.getStringExtra(Game.EXTRA_EXPECTED_SESSION_TOKEN))
        assertTrue(intent.getBooleanExtra(Game.EXTRA_EXPECTED_SESSION_OWNED, false))
        assertTrue(intent.getBooleanExtra(Game.EXTRA_RESUME_EXISTING, false))
        assertFalse(intent.getBooleanExtra(Game.EXTRA_WATCH_ONLY, true))
        assertEquals(7, intent.getIntExtra(Game.EXTRA_APP_ID, 0))
        assertEquals(7, activity.getSharedPreferences("nova_prefs", 0).getInt("last_played_host-1", 0))
    }

    @Test fun aWatcherReplacementDuringConfirmationCannotEvenOpenACancelConnection() {
        val page = confirm()
        adapter.setNowPlaying(mapOf("host-1" to novaHostNowPlaying(PolarisSessionStatus(
            state = "paused", gameId = 7, game = "Hades", sessionToken = "session-B", ownedByClient = true,
        ))!!.copy(host = NovaHostNowPlaying.Host(details.uuid, details.activeAddress!!.address, details.httpsPort, details.serverCert!!))))
        mockConstruction(NvHTTP::class.java).use {
            page.onConfirm()
            assertTrue("the stale confirmation never constructs a cancel connection", it.constructed().isEmpty())
        }
    }

    @Test fun stayAndClosingTheActualConfirmationNeverCancel() {
        mockConstruction(NvHTTP::class.java).use {
            confirm().onStay()
            NovaSurfaces.of(controller.get()).panel.close()
            assertTrue(it.constructed().isEmpty())
        }
    }

    @Test fun replacingTheGameWhileConfirmationIsOpenCannotEndItsReplacement() = attemptEnd(currentGame = 8, currentToken = "session-B")

    @Test fun replacingTheSessionOfTheSameGameAfterConfirmationCannotEndTheNewSession() = attemptEnd(currentGame = 7, currentToken = "session-B")

    @Test fun changedOwnershipCannotBeEnded() = attemptEnd(currentGame = 7, currentToken = "session-A", owned = false)

    @Test fun unknownOwnershipCannotBeEnded() = attemptEnd(currentGame = 7, currentToken = "session-A", owned = null)

    @Test fun aReplacementAfterTheFreshCheckCannotBeEndedThroughALegacyOwnerOverride() =
        attemptEnd(currentGame = 7, currentToken = "session-A", replaceAfterCheck = true)

    @Test fun anUnchangedOwnedSessionCanStillBeEnded() = attemptEnd(currentGame = 7, currentToken = "session-A", expected = listOf("session-A"))

    private fun attemptEnd(currentGame: Int, currentToken: String, expected: List<String> = emptyList(), owned: Boolean? = true, replaceAfterCheck: Boolean = false) {
        val page = confirm()
        if (currentGame != 7) details.runningGameId = currentGame
        assertTrue(page.destructive)
        assertEquals(controller.get().getString(R.string.nova_panel_stay), page.stayLabel)
        val cancelled = CopyOnWriteArrayList<String>()
        val posts = CopyOnWriteArrayList<String>()
        val intercepted = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("/polaris/v1/session/stop", request.url.encodedPath)
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            val token = org.json.JSONObject(buffer.readUtf8()).getString("session_token")
            posts += token
            val activeToken = if (replaceAfterCheck) "session-B" else currentToken
            val stopped = owned == true && token == activeToken
            if (stopped) cancelled += activeToken
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (stopped) 200 else 470).message("synthetic")
                .body(okhttp3.ResponseBody.create(null, "{\"status\":$stopped}")).build()
        }.build()
        mockConstruction(NvHTTP::class.java).use { legacy ->
            mockConstruction(PolarisApiClient::class.java, withSettings().defaultAnswer(CALLS_REAL_METHODS)) { api, _ ->
                // Construction mocks bypass field initializers; keep real AI retirement in the stop path.
                org.robolectric.util.ReflectionHelpers.setField(api, "doctorAiReadings", DoctorAiReadings())
                org.robolectric.util.ReflectionHelpers.setField(api, "baseUrl", "https://127.0.0.1:47984/polaris/v1")
                org.robolectric.util.ReflectionHelpers.setField(api, "perCallClientCache", intercepted)
            }.use {
                page.onConfirm()
                if (currentGame != 7) {
                    assertTrue("discovered replacement invalidates the confirmation before any POST", posts.isEmpty())
                    assertTrue(cancelled.isEmpty())
                    assertTrue(legacy.constructed().isEmpty())
                    return
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (cancelled.isEmpty() && NovaSurfaces.of(controller.get()).panel.top !is NovaCommonPage.Notice && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    Thread.yield()
                }
                assertTrue("the real cancellation path must settle", cancelled.isNotEmpty() || NovaSurfaces.of(controller.get()).panel.top is NovaCommonPage.Notice)
                assertEquals("End must use only the observed session's token", expected, cancelled.toList())
                assertEquals("one atomic mutation with captured A; no token refresh", listOf("session-A"), posts.toList())
                assertTrue("no legacy cancel connection or fallback", legacy.constructed().isEmpty())
            }
        }
    }
}

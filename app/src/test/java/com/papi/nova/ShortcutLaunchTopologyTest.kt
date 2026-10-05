package com.papi.nova

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.computers.ComputerManagerService
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.preferences.NovaSettingsMigration
import com.papi.nova.preferences.NovaTierRuntime
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.shadows.ShadowMoonBridge
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.NovaLaunchModeOverrides
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.cert.X509Certificate

/** Exercises the shortcut's real preflight and the intent used by both launch branches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [ShadowMoonBridge::class])
class ShortcutLaunchTopologyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val game = PolarisGame(id = "shortcut-game", appId = 7, name = "Control")

    @Before
    @After
    fun reset() {
        ProfilesManager.instance = null
        NovaTierRuntime.installForTest(null)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private data class Launch(val intent: Intent, val optimizationMode: String?, val topologyLocked: Boolean?)

    private fun launch(
        hostMode: String,
        legacyVirtualToggle: Boolean,
        savedMode: String? = null,
        unavailableMode: String? = null,
        sessionRestrictedMode: String? = null,
        effectiveOnly: Boolean = false,
        settingsUnavailable: Boolean = false,
        polarisMetadata: Boolean = true,
    ): Launch {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("list_resolution", "1920x1080").putString("list_fps", "120").commit()
        NovaSettingsMigration.apply(context)
        savedMode?.let { NovaLaunchModeOverrides.save(context, game, it) }
        val settings = PolarisClientSettings(
            desired = PolarisClientSettings.Desired(streamDisplayMode = if (effectiveOnly) "" else hostMode),
            effective = PolarisClientSettings.Effective(streamDisplayMode = hostMode),
            capabilities = PolarisClientSettings.Capabilities(modes = listOf(
                "headless_stream", "host_virtual_display", "desktop_display", "desktop_takeover",
                "windowed_stream", "gamescope_stream",
            ).map {
                PolarisClientSettings.ModeOption(value = it, available = it != unavailableMode,
                    sessionOverridable = it != sessionRestrictedMode)
            }),
        )
        val activity = Robolectric.buildActivity(ShortcutTrampoline::class.java).get()
        val planType = ShortcutTrampoline::class.java.declaredClasses.single { it.simpleName == "ShortcutLaunchPlan" }
        val plan = planType.declaredConstructors.single { it.parameterCount == 12 }.apply { isAccessible = true }
            .newInstance(NvApp(game.name, game.id, game.appId, false), "auto", null,
                if (polarisMetadata) game else null, null, false, "", "", "", 0, 0, 0f)
        val cert = Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(cert.encoded).thenReturn(byteArrayOf(1))
        val details = ComputerDetails().apply {
            uuid = "fixture-host"
            activeAddress = ComputerDetails.AddressTuple("127.0.0.1", 47989)
            serverCert = cert
        }
        val binder = Mockito.mock(ComputerManagerService.ComputerManagerBinder::class.java)
        Mockito.`when`(binder.uniqueId).thenReturn("fixture-client")
        var requestedMode: String? = null
        var locked: Boolean? = null
        var startIntent: Intent? = null
        Mockito.mockConstruction(PolarisApiClient::class.java, Mockito.withSettings().defaultAnswer { call ->
            when (call.method.name) {
                "setMangoHud" -> true
                "getClientSettings" -> if (settingsUnavailable) null else settings
                "getOptimization" -> {
                    requestedMode = call.arguments[3] as String
                    locked = call.arguments[4] as Boolean
                    JSONObject()
                }
                "updateClientSettings" -> {
                    // A shortcut can sync size/bitrate, but cannot rewrite the host's topology.
                    assertNull(call.arguments[0])
                    settings
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(call)
            }
        }).use {
            val preflight = ShortcutTrampoline::class.java.declaredMethods.single {
                it.name == "applyPolarisShortcutLaunchPreflight"
            }.apply { isAccessible = true }
            val readyPlan = preflight.invoke(activity, details, plan, legacyVirtualToggle)
            val createIntent = ShortcutTrampoline::class.java.declaredMethods.single {
                it.name == "createShortcutStartIntent"
            }.apply { isAccessible = true }
            startIntent = createIntent.invoke(activity, details, binder, readyPlan, legacyVirtualToggle) as Intent
        }
        return Launch(requireNotNull(startIntent), requestedMode, locked)
    }

    private fun assertLaunch(result: Launch, mode: String, virtual: Boolean, override: String = "") {
        assertEquals(mode, result.optimizationMode)
        assertEquals(true, result.topologyLocked)
        assertEquals(virtual, result.intent.getBooleanExtra(Game.EXTRA_VDISPLAY, !virtual))
        assertTrue(result.intent.getBooleanExtra(Game.EXTRA_DISPLAY_MODE_EXPLICIT, false))
        assertEquals(override, result.intent.getStringExtra(Game.EXTRA_STREAM_MODE).orEmpty())
        assertFalse(result.intent.getBooleanExtra(Game.EXTRA_MIRROR_DESKTOP, true))
    }

    @Test fun staleVirtualToggleCannotChangeTheWindowedHostDefault() {
        assertLaunch(launch("windowed_stream", true), "windowed_stream", false)
    }

    @Test fun hostVirtualDefaultRemainsVirtualWhenTheOldToggleIsOff() {
        assertLaunch(launch("host_virtual_display", false), "host_virtual_display", true)
    }

    @Test fun savedVirtualChoiceTravelsFromPreflightToLaunch() {
        assertLaunch(launch("windowed_stream", false, "host_virtual_display"),
            "host_virtual_display", true, "host_virtual_display")
    }

    @Test fun savedGamescopeChoiceTravelsWithoutCollapsingIntoHeadless() {
        assertLaunch(launch("windowed_stream", false, "gamescope_stream"),
            "gamescope_stream", false, "gamescope_stream")
    }

    @Test fun unavailableSavedChoiceIsRetiredAndTheCurrentHostDefaultWins() {
        assertLaunch(launch("windowed_stream", true, "host_virtual_display", "host_virtual_display"),
            "windowed_stream", false)
        assertNull(NovaLaunchModeOverrides.load(context, game))
    }

    @Test fun hostOnlySavedChoiceCannotBecomeASessionOverride() {
        assertLaunch(launch("windowed_stream", true, "host_virtual_display",
            sessionRestrictedMode = "host_virtual_display"), "windowed_stream", false)
        assertNull(NovaLaunchModeOverrides.load(context, game))
    }

    @Test fun effectiveHostModeIsUsedWhenDesiredModeIsEmpty() {
        assertLaunch(launch("windowed_stream", true, effectiveOnly = true), "windowed_stream", false)
    }

    @Test fun transientSettingsFailureDoesNotEraseASavedVirtualChoice() {
        assertLaunch(launch("windowed_stream", false, "host_virtual_display", settingsUnavailable = true),
            "host_virtual_display", true, "host_virtual_display")
        assertEquals("host_virtual_display", NovaLaunchModeOverrides.load(context, game))
    }

    @Test fun stockHostShortcutKeepsItsLegacyIntent() {
        val result = launch("windowed_stream", true, polarisMetadata = false)
        assertNull(result.optimizationMode)
        assertTrue(result.intent.getBooleanExtra(Game.EXTRA_VDISPLAY, false))
        assertFalse(result.intent.getBooleanExtra(Game.EXTRA_DISPLAY_MODE_EXPLICIT, true))
        assertFalse(result.intent.hasExtra(Game.EXTRA_STREAM_MODE))
    }
}

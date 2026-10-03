package com.papi.nova

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.preferences.*
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.shadows.ShadowMoonBridge
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.cert.X509Certificate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,shadows=[ShadowMoonBridge::class])
class ShortcutTierPreflightTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    @Before fun reset() { ProfilesManager.instance=null;NovaTierRuntime.installForTest(null) }
    @After fun clean() { ProfilesManager.instance=null;NovaTierRuntime.installForTest(null) }
    private fun runPreflight(unavailable:Boolean):List<String> {
        org.robolectric.Shadows.shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).setActiveNetworkInfo(
            org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,
                android.net.ConnectivityManager.TYPE_WIFI,0,true,true))
        assertFalse(com.papi.nova.manager.StreamSyncManager.isMeteredNetwork(context))
        val prefs=PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().putString("list_resolution","1920x1080").putString("list_fps","60")
            .putString("video_format","forcepyrowave").putInt("seekbar_bitrate_kbps",150000).commit()
        NovaSettingsMigration.apply(context)
        if(unavailable) {
            NovaTierRuntime.installForTest(NovaTierInputs(NovaSize(1920,1080),listOf(60),NovaDistance.HAND,NovaDeviceCapabilities(emptyList())))
            NovaStreamSettings.select(context,NovaTier.RECOMMENDED)
        }
        val activity=Robolectric.buildActivity(ShortcutTrampoline::class.java).get()
        val game=PolarisGame(id="fixture-game",appId=1,name="Fixture")
        val planType=ShortcutTrampoline::class.java.declaredClasses.single { it.simpleName=="ShortcutLaunchPlan" }
        val plan=planType.declaredConstructors.single { it.parameterCount==12 }.apply { isAccessible=true }
            .newInstance(NvApp("Fixture","fixture-game",1,false),"auto",null,game,null,false,"","","",0,0,0f)
        val cert=Mockito.mock(X509Certificate::class.java)
        Mockito.`when`(cert.encoded).thenReturn(byteArrayOf(1))
        val details=ComputerDetails().apply { uuid="fixture-host";activeAddress=ComputerDetails.AddressTuple("127.0.0.1",47989);serverCert=cert }
        val calls=mutableListOf<String>()
        Mockito.mockConstruction(PolarisApiClient::class.java,Mockito.withSettings().defaultAnswer { invocation ->
            calls+=invocation.method.name
            when(invocation.method.name) {
                "setMangoHud" -> true
                "getOptimization" -> {
                    assertEquals(true,invocation.arguments[12])
                    assertEquals(150000,invocation.arguments[11])
                    val path=PolarisApiClient.buildOptimizationPath("fixture","fixture-game",
                        bitrateKbps=invocation.arguments[11] as Int,bitrateLocked=invocation.arguments[12] as Boolean)
                    assertTrue(path.contains("bitrate_locked=1"))
                    JSONObject()
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }).use {
            val method=ShortcutTrampoline::class.java.declaredMethods.single { it.name=="applyPolarisShortcutLaunchPreflight" }
            method.isAccessible=true
            val result=method.invoke(activity,details,plan,false)
            if(unavailable) assertSame(plan,result)
        }
        return calls
    }
    @Test fun pyrowaveShortcutSendsLockedRequestUnits() {
        assertTrue(runPreflight(false).contains("getOptimization"))
    }
    @Test fun unavailableGeneratedShortcutDoesNotContactOrWriteTheHost() {
        assertTrue(runPreflight(true).isEmpty())
    }
}

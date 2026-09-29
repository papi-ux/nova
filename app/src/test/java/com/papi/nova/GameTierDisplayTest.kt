package com.papi.nova

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisLaunchHostKind
import com.papi.nova.manager.LaunchRefusalReason
import com.papi.nova.manager.StreamSyncManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shadows.ShadowMoonBridge
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],shadows=[ShadowMoonBridge::class])
class GameTierDisplayTest {
    private fun response():JSONObject {
        fun field(v:Any)=JSONObject().put("value",v).put("source","client_launch_request")
            .put("reason_code","requested").put("locked",false).put("normalized",false)
        val fields=JSONObject()
        for((k,v) in listOf("display_mode" to "1920x1080x120","display_width" to 1920,"display_height" to 1080,
            "target_fps" to 120,"target_bitrate_kbps" to 30000,"hdr" to false)) fields.put(k,field(v))
        return JSONObject().put("source","deterministic_preset_v1")
            .put("resolved_profile",JSONObject().put("policy_version",1).put("fields",fields))
            .put("topology_resolution",JSONObject().put("requested","host_default").put("resolved","headless_stream")
                .put("locked",false).put("source","host_configuration").put("reason_code","default")
                .put("normalized",false).put("mirror_desktop_requested",false)
                .put("force_private_after_steam_close_requested",false).put("app_uuid","fixture-game").put("app_id","1"))
    }
    private fun mode(hz:Float):Display.Mode=ReflectionHelpers.callStaticMethod(ShadowDisplayManager::class.java,"displayModeOf",
        from(Int::class.javaPrimitiveType,0),from(Int::class.javaPrimitiveType,1920),from(Int::class.javaPrimitiveType,1080),
        from(Float::class.javaPrimitiveType,hz))
    @Test fun actualGameRequestAndBothEnvelopeChecksUseTheStreamDisplay() {
        val manager=ApplicationProvider.getApplicationContext<Context>().getSystemService(DisplayManager::class.java)
        val external=ShadowDisplayManager.addDisplay("w1920dp-h1080dp-land")
        ShadowDisplayManager.setSupportedModes(0,mode(60f))
        ShadowDisplayManager.setSupportedModes(external,mode(120f))
        shadowOf(manager.getDisplay(0)).setRefreshRate(60f)
        shadowOf(manager.getDisplay(external)).setRefreshRate(120f)
        val payload=response()
        assertTrue("Fixture must exercise the trusted envelope",StreamSyncManager.hasTrustedResolvedProfile(payload))
        for(preflight in listOf(false,true)) for(id in listOf(0,external)) {
            val game=Robolectric.buildActivity(Game::class.java).get()
            game.prefConfig=PreferenceConfiguration().apply { bitrate=30000;meteredBitrate=10000;videoFormat=PreferenceConfiguration.FormatOption.AUTO }
            ReflectionHelpers.setField(game,"streamingDisplayId",id)
            ReflectionHelpers.setField(game,"appUUID","fixture-game")
            if(preflight) ReflectionHelpers.setField(game,"launchOptimizationJson",payload.toString())
            val requestedFps=mutableListOf<Float>()
            game.novaApiClient=Mockito.mock(PolarisApiClient::class.java) { invocation -> when(invocation.method.name) {
                "identifyLaunchHost" -> PolarisLaunchHostKind.CURRENT_POLARIS
                "getOptimization" -> { requestedFps+=invocation.arguments[14] as Float;payload }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            } }
            val load=Game::class.java.declaredMethods.single { it.name=="loadLaunchOptimization" }.apply { isAccessible=true }
            val result=load.invoke(game,"Fixture",false,1920,1080,120f,false,false,false,"auto")
            val blocked=ReflectionHelpers.getField<Boolean>(result,"policyBlocked")
            assertEquals("display=$id preflight=$preflight",id==0,blocked)
            if(id==0) assertEquals(LaunchRefusalReason.DISPLAY_MODE,ReflectionHelpers.getField<Any>(result,"policyReason"))
            if(!preflight || id==0) assertEquals(listOf(if(id==0) 60f else 120f),requestedFps)
            else assertTrue("The valid external-display preflight must be reused",requestedFps.isEmpty())
        }
    }
}

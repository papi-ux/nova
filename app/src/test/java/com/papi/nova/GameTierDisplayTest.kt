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
    @Test fun launchClampsCustomPinToThisHostsAdvertisedLimitWithoutChangingThePin() {
        ShadowDisplayManager.setSupportedModes(0,mode(120f))
        for (maximum in listOf(null,200000,500000)) {
            val expected=minOf(450000,maximum ?: 300000)
            val payload=response()
            payload.getJSONObject("resolved_profile").getJSONObject("fields")
                .getJSONObject("target_bitrate_kbps").put("value",expected).put("locked",true)
            val game=Robolectric.buildActivity(Game::class.java).get()
            game.prefConfig=PreferenceConfiguration().apply { bitrate=450000; videoFormat=PreferenceConfiguration.FormatOption.FORCE_PYROWAVE }
            ReflectionHelpers.setField(game,"appUUID","fixture-game")
            val calls=mutableListOf<Int>()
            val features=JSONObject();maximum?.let { features.put("manual_bitrate_max_kbps",it) }
            game.novaApiClient=Mockito.mock(PolarisApiClient::class.java) { invocation -> when(invocation.method.name) {
                "identifyLaunchHost" -> PolarisLaunchHostKind.CURRENT_POLARIS
                "getLaunchCapabilities" -> PolarisApiClient.parseCapabilitiesResponse(JSONObject().put("features",features))
                "getOptimization" -> { calls+=invocation.arguments[11] as Int;payload }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            } }
            val load=Game::class.java.declaredMethods.single { it.name=="loadLaunchOptimization" }.apply { isAccessible=true }
            val result=load.invoke(game,"Fixture",false,1920,1080,120f,false,false,false,"auto")
            assertEquals(maximum ?: 300000,ReflectionHelpers.getField<Int>(result,"manualBitrateMaximumKbps"))
            assertEquals("limit=$maximum",listOf(expected),calls)
            assertFalse("limit=$maximum",ReflectionHelpers.getField<Boolean>(result,"policyBlocked"))
            assertEquals(450000,game.prefConfig.bitrate)
        }
    }

    @Test fun gameUsesOneFrozenCeilingForItsEnvelopeAndRealOptimizationRequest() {
        ShadowDisplayManager.setSupportedModes(0,mode(120f))
        val game=Robolectric.buildActivity(Game::class.java).get()
        game.prefConfig=PreferenceConfiguration().apply { bitrate=450000;videoFormat=PreferenceConfiguration.FormatOption.FORCE_PYROWAVE }
        ReflectionHelpers.setField(game,"appUUID","fixture-game")
        val sent=mutableListOf<Int>()
        val api=PolarisApiClient(ApplicationProvider.getApplicationContext(),"127.0.0.1",47984)
        val http=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            assertTrue(request.url.encodedPath.endsWith("/optimize"))
            val bitrate=request.url.queryParameter("bitrate_kbps")!!.toInt()
            sent+=bitrate
            val payload=response()
            payload.getJSONObject("resolved_profile").getJSONObject("fields")
                .getJSONObject("target_bitrate_kbps").put("value",bitrate).put("locked",true)
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
                .body(okhttp3.ResponseBody.create(null,payload.toString())).build()
        }.build()
        ReflectionHelpers.setField(api,"client",http)
        val spy=Mockito.spy(api)
        Mockito.doReturn(PolarisLaunchHostKind.CURRENT_POLARIS).`when`(spy).identifyLaunchHost()
        var reads=0
        Mockito.doAnswer {
            reads++
            if(reads==1) PolarisApiClient.parseCapabilitiesResponse(JSONObject().put("features",
                JSONObject().put("manual_bitrate_max_kbps",500000))) else null
        }.`when`(spy).getLaunchCapabilities()
        game.novaApiClient=spy
        val result=Game::class.java.declaredMethods.single { it.name=="loadLaunchOptimization" }
            .apply { isAccessible=true }.invoke(game,"Fixture",false,1920,1080,120f,false,false,false,"auto")
        assertEquals(listOf(450000),sent)
        assertEquals(1,reads)
        assertFalse(ReflectionHelpers.getField<Boolean>(result,"policyBlocked"))
    }

    @Test fun pyrowaveLaunchPassesItsBitrateLockToTheApi() {
        val payload=response()
        val fields=payload.getJSONObject("resolved_profile").getJSONObject("fields")
        fields.getJSONObject("target_bitrate_kbps").put("value",150000).put("locked",true)
        ShadowDisplayManager.setSupportedModes(0,mode(120f))
        val game=Robolectric.buildActivity(Game::class.java).get()
        game.prefConfig=PreferenceConfiguration().apply {
            bitrate=150000;meteredBitrate=10000;videoFormat=PreferenceConfiguration.FormatOption.FORCE_PYROWAVE
        }
        ReflectionHelpers.setField(game,"appUUID","fixture-game")
        val calls=mutableListOf<Array<Any>>()
        game.novaApiClient=Mockito.mock(PolarisApiClient::class.java) { invocation -> when(invocation.method.name) {
            "identifyLaunchHost" -> PolarisLaunchHostKind.CURRENT_POLARIS
            "getOptimization" -> { calls+=invocation.arguments;payload }
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        } }
        val load=Game::class.java.declaredMethods.single { it.name=="loadLaunchOptimization" }.apply { isAccessible=true }
        val result=load.invoke(game,"Fixture",false,1920,1080,120f,false,false,false,"auto")
        assertFalse(ReflectionHelpers.getField<Boolean>(result,"policyBlocked"))
        assertEquals(true,calls.single()[12]);assertEquals(150000,calls.single()[11])
        val path=PolarisApiClient.buildOptimizationPath("fixture","fixture-game",
            bitrateKbps=calls.single()[11] as Int,bitrateLocked=calls.single()[12] as Boolean)
        assertTrue(path.contains("bitrate_locked=1"))
    }

    @Test fun spaceLaunchAcceptsItsContractDespiteADifferentSavedDisplayButHonorsMeteredBitrate() {
        val worker=com.papi.nova.manager.WorkerLaunchContract
        fun field(value:Any)=JSONObject().put("value",value).put("locked",true).put("normalized",false)
            .put("source","capability_validation").put("reason_code","worker_media_contract")
        val fields=JSONObject()
        for((key,value) in mapOf("display_mode" to "1920x1080x60","display_width" to 1920,"display_height" to 1080,
            "target_fps" to 60,"target_bitrate_kbps" to 8000,"hdr" to false,"preferred_codec" to "h264")) fields.put(key,field(value))
        val payload=JSONObject().put("status",true).put("source","worker_profile_v1")
            .put("worker_profile",JSONObject().put("version",1).put("id","fixture-space")
                .put("app_uuid",worker.APP_UUID).put("app_id",worker.APP_ID).put("codec","h264").put("audio_channels",2))
            .put("resolved_profile",JSONObject().put("policy_version",1).put("preset","worker").put("fields",fields))
            .put("topology_resolution",JSONObject().put("resolved","gamescope_stream"))
        assertTrue(StreamSyncManager.hasTrustedResolvedProfile(payload))
        ShadowDisplayManager.setSupportedModes(0,mode(120f))
        for(metered in listOf(false,true)) {
            val game=Robolectric.buildActivity(Game::class.java).get()
            game.prefConfig=PreferenceConfiguration().apply { bitrate=30000;meteredBitrate=4000;videoFormat=PreferenceConfiguration.FormatOption.FORCE_PYROWAVE }
            ReflectionHelpers.setField(game,"appUUID",worker.APP_UUID)
            ReflectionHelpers.setField(game,"launchOptimizationJson",payload.toString())
            game.novaApiClient=Mockito.mock(PolarisApiClient::class.java) { invocation -> when(invocation.method.name) {
                "identifyLaunchHost" -> PolarisLaunchHostKind.CURRENT_POLARIS
                "getOptimization" -> {
                    assertEquals(false,invocation.arguments[10])
                    assertEquals(metered,invocation.arguments[12])
                    payload
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            } }
            val load=Game::class.java.declaredMethods.single { it.name=="loadLaunchOptimization" }.apply { isAccessible=true }
            val result=load.invoke(game,"Fixture",metered,1280,720,60f,true,false,false,"auto")
            assertEquals("metered=$metered",metered,ReflectionHelpers.getField<Boolean>(result,"policyBlocked"))
            if(!metered) assertTrue(ReflectionHelpers.getField<Boolean>(result,"resolvedProfileTrusted"))
        }
    }

}

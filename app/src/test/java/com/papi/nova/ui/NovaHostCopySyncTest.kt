package com.papi.nova.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.preferences.*
import androidx.preference.PreferenceManager
import com.papi.nova.profiles.ProfilesManager
import org.json.JSONObject
import okio.Buffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [com.papi.nova.shadows.ShadowMoonBridge::class])
class NovaHostCopySyncTest {
    @Test fun closedHostSurfaceCannotSendItsOldClientCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requests = AtomicInteger()
        val api = PolarisApiClient(context, "host-a")
        val http = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(ResponseBody.create(null,"{\"state\":\"idle\",\"streaming_active\":false}")).build()
        }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible=true }.set(api,http)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val engine = NovaPolarisSyncEngine(context,api,"host-a",scope)
        engine.close()
        engine.sendNova()
        repeat(20) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
        assertEquals("a retained callback from a closed host must make no network write or read",0,requests.get())
        scope.cancel()
    }
    @After fun clearProbe() { NovaTierRuntime.installForTest(null) }
    private fun preferences() {
        ProfilesManager.instance = null
        val context = ApplicationProvider.getApplicationContext<Context>()
        NovaTierRuntime.installForTest(NovaTierInputs(NovaSize(1920,1080),listOf(60,120),NovaDistance.HAND,
            NovaDeviceCapabilities(listOf(NovaCodecCapability(NovaCodecChoice.HEVC,"fixture",listOf(
                NovaDecodePoint(NovaSize(1920,1080),120),NovaDecodePoint(NovaSize(3840,2160),60)))))))
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putString(NovaSettingsMigration.TIER,"custom").putBoolean(NovaSettingsMigration.CUSTOM_EXISTS,true)
            .putBoolean(NovaSettingsMigration.CUSTOM_AUTO,false).putBoolean(NovaSettingsMigration.AUTO,false)
            .putString("list_resolution","1920x1080").putString("list_fps","120")
            .putString("video_format","forceh265").putInt("seekbar_bitrate_kbps",500000).commit()
    }
    private fun api(handler: (Request) -> Response): PolarisApiClient {
        val client=PolarisApiClient(ApplicationProvider.getApplicationContext(),"host-a")
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible=true }
            .set(client,OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build())
        return client
    }
    private fun reply(request: Request,body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(ResponseBody.create(null,body)).build()
    private val confirmed = """{"version":1,"desired":{"stream_display_mode":"headless_stream"},
        "effective":{"stream_display_mode":"headless_stream"},"capabilities":{
        "display_mode_override":true,"target_bitrate_override":true,
        "modes":[{"value":"headless_stream","available":true}]}}"""
    private fun await(done: () -> Boolean) {
        val deadline=System.nanoTime()+5_000_000_000L
        while (!done() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertTrue("API operation settled",done())
    }
    @Test fun recoveryPostsOnlyThisClientCopyThenRechecksWithoutChangingCustomOrAuto() {
        preferences()
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=PreferenceManager.getDefaultSharedPreferences(context)
        val before=prefs.all
        val paths=CopyOnWriteArrayList<String>()
        var body:JSONObject?=null
        val client=api { request ->
            paths += request.url.encodedPath
            if(request.method=="POST") { val buffer=Buffer();request.body!!.writeTo(buffer);body=JSONObject(buffer.readUtf8()) }
            reply(request,if(request.method=="POST") confirmed else
                if(request.url.encodedPath.endsWith("/session/status")) "{\"state\":\"idle\",\"streaming_active\":false}" else "{}")
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val engine=NovaPolarisSyncEngine(context,client,"host-a",scope)
        val checked=AtomicBoolean(false)
        assertTrue(engine.sendDeviceSetting(500000,{true}, {
            scope.launch(Dispatchers.IO) {
                client.getOptimization("fixture","game",width=1920,height=1080,fps=120f,bitrateKbps=500000,
                    manualBitrateMaximumKbps=500000)
                checked.set(true)
            }
        }, { fail("the scoped write should succeed") }))
        await { checked.get() }
        assertEquals("1920x1080x120",body!!.getString("display_mode"))
        assertEquals(500000,body!!.getInt("target_bitrate_kbps"))
        assertEquals(setOf("display_mode","target_bitrate_kbps"),body!!.keys().asSequence().toSet())
        assertTrue("fresh preflight is after the client-copy POST",paths.indexOf("/polaris/v1/client-settings") < paths.indexOf("/polaris/v1/optimize"))
        assertFalse(paths.any { it.contains("/config") || it.contains("/settings/global") })
        assertEquals("device pins and their own Auto flag are untouched",before,prefs.all)
        engine.close();scope.cancel()
    }
    @Test fun legacyHostReceives300MbpsWhileThe500MbpsPinRemainsStored() {
        preferences()
        var body:JSONObject?=null
        val client=api { request -> if(request.method=="POST") {
            val buffer=Buffer();request.body!!.writeTo(buffer);body=JSONObject(buffer.readUtf8());reply(request,confirmed)
        } else reply(request,"{\"state\":\"idle\",\"streaming_active\":false}") }
        val context=ApplicationProvider.getApplicationContext<Context>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val engine=NovaPolarisSyncEngine(context,client,"host-a",scope)
        var done=false
        assertTrue(engine.sendDeviceSetting(300000,{true},{done=true},{fail("expected successful legacy copy")}))
        await { done }
        assertEquals(300000,body!!.getInt("target_bitrate_kbps"))
        assertEquals(500000,PreferenceManager.getDefaultSharedPreferences(context).getInt("seekbar_bitrate_kbps",0))
        engine.close();scope.cancel()
    }
    @Test fun changedHostAndCloseRejectLateConfirmationAndBusyRejectsSecondWrite() {
        preferences()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val posts=AtomicInteger();val current=AtomicBoolean(true)
        val client=api { request -> if(request.method=="POST") {
            posts.incrementAndGet();entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));reply(request,confirmed)
        } else reply(request,"{\"state\":\"idle\",\"streaming_active\":false}") }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var published=0;var rechecks=0
        val engine=NovaPolarisSyncEngine(ApplicationProvider.getApplicationContext(),client,"host-a",scope,
            onSettingsChanged={published++})
        assertTrue(engine.sendDeviceSetting(500000,{current.get()},{rechecks++},{fail("not a network failure")}))
        await { entered.count==0L }
        assertFalse(engine.sendDeviceSetting(500000,{true},{rechecks++},{}))
        current.set(false);release.countDown()
        await { !engine.busy }
        assertEquals(1,posts.get());assertEquals(0,published);assertEquals(0,rechecks)
        assertNull(engine.currentSettings)
        engine.close()
        assertFalse(engine.sendDeviceSetting(500000,{true},{rechecks++},{}))
        assertEquals(1,posts.get());scope.cancel()
    }
    @Test fun activeViewerCannotWriteTheClientCopyOrTriggerARecheck() {
        preferences()
        val posts=AtomicInteger()
        val client=api { request -> if(request.method=="POST") posts.incrementAndGet()
            reply(request,"""{"state":"streaming","streaming_active":true,"app_session_id":"other",
                "session_generation":9,"client_role":"viewer","owned_by_client":false,
                "controls":{"host_tuning_allowed":false}}""") }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var failed=false;var rechecks=0
        val engine=NovaPolarisSyncEngine(ApplicationProvider.getApplicationContext(),client,"host-a",scope)
        engine.sendDeviceSetting(500000,{true},{rechecks++},{failed=true})
        await { failed }
        assertEquals(0,posts.get());assertEquals(0,rechecks)
        engine.close();scope.cancel()
    }

    @Test fun closeWhileCopyIsOnTheWireSuppressesItsReceiptAndRecheck() {
        preferences()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val returned=CountDownLatch(1)
        val client=api { request -> if(request.method=="POST") {
            entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));returned.countDown();reply(request,confirmed)
        } else reply(request,"{\"state\":\"idle\",\"streaming_active\":false}") }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var published=0;var rechecks=0
        val engine=NovaPolarisSyncEngine(ApplicationProvider.getApplicationContext(),client,"host-a",scope,
            onSettingsChanged={published++})
        engine.sendDeviceSetting(500000,{true},{rechecks++},{fail("closure retires the callback")})
        await { entered.count==0L };engine.close();release.countDown();await { returned.count==0L }
        repeat(10) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertEquals(0,published);assertEquals(0,rechecks);assertNull(engine.currentSettings)
        scope.cancel()
    }
    @Test fun automaticMirrorNeverRaisesA500MbpsPinPast300Mbps() {
        preferences()
        var body:JSONObject?=null
        val client=api { request -> if(request.method=="POST") {
            val buffer=Buffer();request.body!!.writeTo(buffer);body=JSONObject(buffer.readUtf8());reply(request,confirmed)
        } else reply(request,"{\"state\":\"idle\",\"streaming_active\":false}") }
        val context=ApplicationProvider.getApplicationContext<Context>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val engine=NovaPolarisSyncEngine(context,client,"host-a",scope)
        // This is the existing mirror entry used by maybeAutoSync, including its automatic flag.
        NovaPolarisSyncEngine::class.java.getDeclaredMethod("pushNovaProfile",Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible=true }
            .invoke(engine,com.papi.nova.R.string.nova_polaris_sync_matched_to_nova,false)
        await { body!=null && !engine.busy }
        assertEquals(300000,body!!.getInt("target_bitrate_kbps"))
        assertEquals(500000,PreferenceManager.getDefaultSharedPreferences(context).getInt("seekbar_bitrate_kbps",0))
        assertFalse(PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NovaSettingsMigration.CUSTOM_AUTO,true))
        engine.close();scope.cancel()
    }

}

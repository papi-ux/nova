package com.papi.nova.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.api.PolarisApiClient
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
}

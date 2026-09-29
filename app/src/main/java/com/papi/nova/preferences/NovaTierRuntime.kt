package com.papi.nova.preferences

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Metadata and generated plans are prepared on IO, never from synchronous preference readers. */
object NovaTierRuntime {
    data class Snapshot(val inputs: NovaTierInputs, val tiers: NovaStreamTiers, val generation: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val mutableSnapshot = MutableStateFlow<Snapshot?>(null)
    val updates = mutableSnapshot.asStateFlow()
    fun snapshot(): Snapshot? = mutableSnapshot.value
    fun isPrepared(): Boolean = snapshot()?.generation == generation.get()
    @Volatile private var registered = false
    private var glListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    val pendingTiers: NovaStreamTiers by lazy {
        val plan = NovaStreamPlan(1920,1080,60,NovaCodecChoice.AUTO,20000,
            reasons=listOf(NovaReason("probe_pending","Checking this device's decoder")),available=false)
        NovaStreamTiers(plan,plan,plan,NovaFourK.Unavailable(NovaLimit("probe_pending","Checking this device's decoder")),null,"pending")
    }

    suspend fun prepare(context: Context): Snapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            var prepared: Snapshot? = null
            while (prepared == null) {
                val wanted = generation.get()
                prepared = snapshot()?.takeIf { it.generation == wanted }
                if (prepared == null) {
                    val inputs = NovaCapabilityProbe.deviceInputs(context.applicationContext)
                    val candidate = Snapshot(inputs,NovaStreamTiers.forDevice(inputs),wanted)
                    if (generation.get() == wanted) {
                        mutableSnapshot.value = candidate
                        prepared = candidate
                    }
                }
            }
            prepared
        }
    }

    fun invalidate(context: Context) {
        generation.incrementAndGet()
        scope.launch { prepare(context.applicationContext) }
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        scope.launch {
            synchronized(this@NovaTierRuntime) {
                if (!registered) {
                    registered=true
                    val display=app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    display?.registerDisplayListener(object:DisplayManager.DisplayListener {
                        override fun onDisplayAdded(id:Int) = invalidate(app)
                        override fun onDisplayRemoved(id:Int) = invalidate(app)
                        override fun onDisplayChanged(id:Int) = invalidate(app)
                    },Handler(Looper.getMainLooper()))
                    glListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> invalidate(app) }
                    app.getSharedPreferences("GlPreferences",Context.MODE_PRIVATE)
                        .registerOnSharedPreferenceChangeListener(glListener)
                    val connectivity=app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    runCatching { connectivity?.registerNetworkCallback(NetworkRequest.Builder().build(),object:ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network:Network) = invalidate(app)
                        override fun onLost(network:Network) = invalidate(app)
                        override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities) = invalidate(app)
                    }) }
                }
            }
            prepare(app)
        }
    }

    internal fun installForTest(inputs: NovaTierInputs?) {
        val current=generation.incrementAndGet()
        mutableSnapshot.value=inputs?.let { Snapshot(it,NovaStreamTiers.forDevice(it),current) }
    }
}

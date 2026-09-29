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
    fun isPrepared(): Boolean = snapshot() != null && !dirty && !preparing
    @Volatile private var registered = false
    @Volatile private var lastSignature: Any? = null
    @Volatile private var dirty = true
    @Volatile private var preparing = false
    @Volatile private var testOverride = false
    private var glListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    val pendingTiers: NovaStreamTiers by lazy {
        val plan = NovaStreamPlan(1920,1080,60,NovaCodecChoice.AUTO,20000,
            reasons=listOf(NovaReason("probe_pending","Checking this device's decoder")),available=false)
        NovaStreamTiers(plan,plan,plan,NovaFourK.Unavailable(NovaLimit("probe_pending","Checking this device's decoder")),null,"pending")
    }

    private fun signature(context: Context): Any = listOf(NovaCapabilityProbe.deviceEnvironment(context),
        context.getSharedPreferences("GlPreferences",Context.MODE_PRIVATE).all,
        context.getSharedPreferences("nova_capability_probe_v1",Context.MODE_PRIVATE).getString("failures",null))

    suspend fun prepare(context: Context): Snapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            snapshot()?.takeIf { testOverride || (!dirty && it.tiers.inputsHash!="failed") }?.let { return@withLock it }
            preparing=true
            try {
            dirty=false
            val app=context.applicationContext
            val signature=runCatching { signature(app) }.getOrNull()
            snapshot()?.takeIf { signature!=null && signature==lastSignature } ?: try {
                val inputs=NovaCapabilityProbe.deviceInputs(app)
                val previous=snapshot()
                val candidate=previous?.takeIf { it.inputs==inputs && it.tiers.inputsHash!="failed" } ?:
                    Snapshot(inputs,NovaStreamTiers.forDevice(inputs),generation.incrementAndGet())
                lastSignature=signature
                mutableSnapshot.value=candidate
                candidate
            } catch (error: Exception) {
                // A metadata failure is a visible unavailable result, never a stuck launch gate.
                val inputs=NovaTierInputs(NovaSize(1920,1080),listOf(60),NovaDistance.HAND,NovaDeviceCapabilities(emptyList()))
                val reason=NovaReason("probe_failed","Could not check this device's decoder. Try again.")
                val plan=pendingTiers.recommended.copy(reasons=emptyList(),limits=listOf(reason))
                val failed=Snapshot(inputs,NovaStreamTiers(plan,plan,plan,NovaFourK.Unavailable(reason),null,"failed"),generation.incrementAndGet())
                lastSignature=null
                mutableSnapshot.value=failed
                failed
            }
            } finally { preparing=false }
        }
    }

    fun invalidate(context: Context) {
        if(runCatching { signature(context.applicationContext)==lastSignature }.getOrDefault(false)) return
        dirty=true
        scope.launch { prepare(context.applicationContext) }
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { synchronized(this@NovaTierRuntime) {
                if (!registered) {
                    registered=true
                    val display=app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    display?.registerDisplayListener(object:DisplayManager.DisplayListener {
                        override fun onDisplayAdded(id:Int) { if(id==android.view.Display.DEFAULT_DISPLAY) invalidate(app) }
                        override fun onDisplayRemoved(id:Int) { if(id==android.view.Display.DEFAULT_DISPLAY) invalidate(app) }
                        override fun onDisplayChanged(id:Int) { if(id==android.view.Display.DEFAULT_DISPLAY) invalidate(app) }
                    },Handler(Looper.getMainLooper()))
                    glListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if(key=="Renderer" || key=="Fingerprint" || key==null) invalidate(app)
                    }
                    app.getSharedPreferences("GlPreferences",Context.MODE_PRIVATE)
                        .registerOnSharedPreferenceChangeListener(glListener)
                    val connectivity=app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val callback=object:ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network:Network) = invalidate(app)
                        override fun onLost(network:Network) = invalidate(app)
                        override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities) = invalidate(app)
                    }
                    runCatching {
                        if(android.os.Build.VERSION.SDK_INT>=24) connectivity?.registerDefaultNetworkCallback(callback)
                        else connectivity?.registerNetworkCallback(NetworkRequest.Builder().build(),callback)
                    }
                }
            } }.onFailure { com.papi.nova.LimeLog.warning("Nova: Could not register tier callbacks: ${it.message}") }
            prepare(app)
        }
    }

    internal fun installForTest(inputs: NovaTierInputs?) {
        lastSignature=null
        dirty=inputs==null
        testOverride=inputs!=null
        val current=generation.incrementAndGet()
        mutableSnapshot.value=inputs?.let { Snapshot(it,NovaStreamTiers.forDevice(it),current) }
    }
}

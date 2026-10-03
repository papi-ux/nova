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
import kotlinx.coroutines.channels.Channel
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
    private val stateLock = Any()
    private var revision = 0L
    private var epoch = 0L
    private data class Invalidation(val context: Context, val epoch: Long)
    private val invalidations = Channel<Invalidation>(Channel.CONFLATED)
    init {
        scope.launch {
            for (event in invalidations) {
                if (synchronized(stateLock) { epoch == event.epoch }) prepareInternal(event.context, retryFailure=false, event.epoch)
            }
        }
    }
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

    private data class Signature(val environment: NovaTierInputs, val gl: Map<String, *>, val failures: String?)
    private fun signature(context: Context) = Signature(NovaCapabilityProbe.deviceEnvironment(context),
        context.getSharedPreferences("GlPreferences",Context.MODE_PRIVATE).all,
        context.getSharedPreferences("nova_capability_probe_v1",Context.MODE_PRIVATE).getString("failures",null))

    suspend fun prepare(context: Context): Snapshot = prepareInternal(context, retryFailure=true)

    private suspend fun prepareInternal(context: Context, retryFailure: Boolean, expectedEpoch: Long? = null): Snapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val runEpoch = synchronized(stateLock) { epoch }
            snapshot()?.takeIf { testOverride || (!dirty && (it.tiers.inputsHash!="failed" || !retryFailure)) }?.let { return@withLock it }
            if (expectedEpoch != null && expectedEpoch != runEpoch) return@withLock snapshot() ?: failedSnapshot()
            preparing=true
            try {
                var retry = retryFailure
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val started = synchronized(stateLock) { revision }
                    val app = context.applicationContext
                    val observed = runCatching { signature(app) }.getOrNull()
                    val previous = snapshot()
                    val candidate = if (previous != null && observed == lastSignature &&
                        !(retry && previous.tiers.inputsHash == "failed")) previous else try {
                        checkNotNull(observed) { "Device environment unavailable" }
                        // Probe precisely the environment whose signature we will publish.
                        val inputs = NovaCapabilityProbe.deviceInputs(app, observed.environment)
                        previous?.takeIf { it.inputs==inputs && it.tiers.inputsHash!="failed" } ?:
                            Snapshot(inputs,NovaStreamTiers.forDevice(inputs),generation.incrementAndGet())
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        previous?.takeIf { it.tiers.inputsHash=="failed" } ?: failedSnapshot()
                    }
                    val after = runCatching { signature(app) }.getOrNull()
                    val stable = synchronized(stateLock) {
                        if (epoch != runEpoch) return@withLock snapshot() ?: candidate
                        lastSignature=observed
                        mutableSnapshot.value=candidate
                        dirty=revision != started || after != observed
                        !dirty
                    }
                    if (stable) return@withLock candidate
                    // A change (including a revert to the previous signature) during the
                    // probe always gets another pass. Background failures remain cached.
                    retry=false
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            } finally {
                synchronized(stateLock) { if (epoch==runEpoch) preparing=false }
            }
        }
    }

    private fun failedSnapshot(): Snapshot {
        val inputs=NovaTierInputs(NovaSize(1920,1080),listOf(60),NovaDistance.HAND,NovaDeviceCapabilities(emptyList()))
        val reason=NovaReason("probe_failed","Could not check this device's decoder. Try again.")
        val plan=pendingTiers.recommended.copy(reasons=emptyList(),limits=listOf(reason))
        return Snapshot(inputs,NovaStreamTiers(plan,plan,plan,NovaFourK.Unavailable(reason),null,"failed"),generation.incrementAndGet())
    }

    fun invalidate(context: Context) {
        // Callbacks only enqueue. Service queries and signature comparison run on IO.
        synchronized(stateLock) {
            dirty=true
            revision++
            invalidations.trySend(Invalidation(context,epoch))
        }
    }

    @Volatile internal var initializedApplication: Context? = null
        private set

    fun initialize(context: Context) {
        val app = context.applicationContext
        initializedApplication = app
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

    internal fun installForTest(inputs: NovaTierInputs?) = synchronized(stateLock) {
        initializedApplication=null
        epoch++
        revision++
        lastSignature=null
        dirty=inputs==null
        preparing=false
        testOverride=inputs!=null
        val current=generation.incrementAndGet()
        mutableSnapshot.value=inputs?.let { Snapshot(it,NovaStreamTiers.forDevice(it),current) }
    }
}

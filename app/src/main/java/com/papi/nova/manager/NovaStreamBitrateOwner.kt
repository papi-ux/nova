package com.papi.nova.manager

import com.papi.nova.api.*
import com.papi.nova.preferences.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class NovaLiveStreamInputs(val width:Int,val height:Int,val fps:Int,val distance:NovaDistance,
    val readOnlyReason:String?=null)
data class NovaLiveBitrateToken internal constructor(val stream:Long,val inputs:Long)
data class NovaLiveBitratePresentation(val rate:NovaLiveBitrateState=NovaLiveBitrateState(),
    val token:NovaLiveBitrateToken?=null,val numbers:String="",val reason:String="Checking this stream",
    val result:String?=null)

/** One attachment per Game, shared across menu windows and reopenings. */
class NovaStreamBitrateOwner(private val scope:CoroutineScope,
    private val currentApi:()->PolarisApiClient?,private val currentConnection:()->Any?,
    private val streamActive:()->Boolean,private val streamInputs:()->NovaLiveStreamInputs,
    private val hostMaximum:()->Int?={null}) {
    private val lock=Any()
    private var api:PolarisApiClient?=null
    private var connection:Any?=null
    private var capabilities:PolarisCapabilities?=null
    private var controller:NovaLiveBitrateController?=null
    private var controllerJob:Job?=null
    private var identity:Pair<String,Long>?=null
    private var denial:Pair<String,Long>?=null
    private var denied=false
    private var status:PolarisSessionStatus?=null
    private var inputSignature:Any?=null
    private var streamRevision=0L
    private var inputRevision=0L
    private var permit:(()->Boolean)?=null
    private var operationToken:NovaLiveBitrateToken?=null
    private var operationInputs:NovaLiveStreamInputs?=null
    private val busy=AtomicBoolean(false)
    private var result:String?=null
    private val mutableState=MutableStateFlow(NovaLiveBitratePresentation())
    val state=mutableState.asStateFlow()

    private fun current()=api!=null && api===currentApi() && connection!=null && connection===currentConnection() && streamActive()
    private fun owned(s:PolarisSessionStatus)=s.authorityContractValid && s.streamingActive && !s.shutdownRequested &&
        s.ownedByClient && s.clientRole=="owner" && !s.isViewer && s.canAdjustHostTuning
    private fun key(s:PolarisSessionStatus)=s.appSessionId to s.sessionGeneration
    private fun validKey(s:PolarisSessionStatus)=s.appSessionId.isNotBlank() && s.sessionGeneration>0

    fun retire(): Unit = synchronized(lock) {
        controllerJob?.cancel();controllerJob=null;controller=null;identity=null;denial=null;denied=false
        api=null;connection=null;capabilities=null;status=null;inputSignature=null;streamRevision++;inputRevision++
        result=null;mutableState.value=NovaLiveBitratePresentation()
    }

    fun observe(observedApi:PolarisApiClient?,observedConnection:Any?,reading:PolarisSessionStatus?,caps:PolarisCapabilities?=null): Unit = synchronized(lock) {
        if (currentApi()==null || currentConnection()==null || !streamActive()) {
            retire();return@synchronized
        }
        // A cancelled old collector may finish after the replacement stream is attached.
        if (observedApi==null || observedConnection==null || observedApi!==currentApi() ||
            observedConnection!==currentConnection()) return@synchronized
        if (api!==observedApi || connection!==observedConnection) {
            retire();api=observedApi;connection=observedConnection
        }
        if (caps!=null && capabilities==null) capabilities=caps
        status=reading
        if (reading==null) { controller?.observe(null);publish();return@synchronized }
        val actualDenial=reading.isViewer || (reading.authorityContractValid && reading.isStreaming &&
            (!reading.ownedByClient || reading.controls.hostTuningAllowed==false))
        if (actualDenial) { if (!denied) denial=key(reading);denied=true }
        else if (denied && owned(reading) && validKey(reading) && denial==key(reading)) { denied=false;denial=null }
        if (denied || !owned(reading) || !validKey(reading) || capabilities==null) {
            controller?.observe(null);publish();return@synchronized
        }
        if (identity!=key(reading)) {
            controllerJob?.cancel();streamRevision++;inputSignature=null;result=null
            identity=key(reading)
            val capturedApi=observedApi;val capturedConnection=observedConnection
            val transport=object:NovaLiveBitrateTransport {
                override fun status():PolarisSessionStatus? {
                    if (!dispatchAllowed(capturedApi,capturedConnection)) return null
                    val fresh=capturedApi.getSessionStatus()
                    observe(capturedApi,capturedConnection,fresh)
                    return fresh?.takeIf { dispatchAllowed(capturedApi,capturedConnection) }
                        ?.copy(pyrowaveBitrate=matchingAdvice(fresh))
                }
                override fun setBitrate(kbps:Int,observed:PolarisSessionStatus)=false
                override fun write(encoderKbps:Int,observed:PolarisSessionStatus):PolarisBitrateWriteResult =
                    capturedApi.setBitrateResult(encoderKbps,observed) {
                        observe(capturedApi,capturedConnection,capturedApi.sessionStatusUpdates.value)
                        dispatchAllowed(capturedApi,capturedConnection)
                    }
            }
            val features=capabilities!!.features
            val created=NovaLiveBitrateController(transport,reading.appSessionId,reading.sessionGeneration,
                features.pyrowaveAdviceV1 || features.bitrateUnitsV1,features.bitrateUnitsV1,
                features.manualBitrateMaxKbps ?: NovaBitrateAdvice.LEGACY_MANUAL_MAX_KBPS)
            controller=created
            controllerJob=scope.launch { created.state.collect { synchronized(lock) { if (controller===created) publish() } } }
        }
        val inputs=streamInputs()
        val advice=matchingAdvice(reading)
        val signature=listOf(inputs,reading.encoder.codec,reading.liveTuning?.hostInstance,
            reading.bitrateUnits?.audioKbps,reading.bitrateUnits?.fecPercent,reading.bitrateUnits?.splitKbps!=null,
            advice,hostMaximum())
        if (signature!=inputSignature) { inputSignature=signature;inputRevision++;result=null }
        val codec=when(reading.encoder.codec.lowercase()) { "h264"->NovaCodecChoice.AVC;"hevc"->NovaCodecChoice.HEVC;"av1"->NovaCodecChoice.AV1;else->null }
        val table=codec?.takeIf { inputs.width>0 && inputs.height>0 && inputs.fps in 1..1000 }
            ?.let { NovaBitrateAdvice.recommend(inputs.width,inputs.height,inputs.fps,it,inputs.distance).kbps }
        controller!!.replaceAdvice(table,hostMaximum())
        controller!!.observe(reading.copy(pyrowaveBitrate=advice))
        publish()
    }

    private fun matchingAdvice(reading:PolarisSessionStatus):PolarisPyrowaveAdvice? {
        val inputs=streamInputs()
        return reading.pyrowaveBitrate?.takeIf {
            it.width==inputs.width && it.height==inputs.height && it.fps==inputs.fps
        }
    }

    private fun dispatchAllowed(capturedApi:PolarisApiClient,capturedConnection:Any)=synchronized(lock) {
        current() && api===capturedApi && connection===capturedConnection && !denied &&
            operationInputs==streamInputs() &&
            streamInputs().readOnlyReason==null && status?.let { owned(it) && key(it)==identity }==true &&
            operationToken==NovaLiveBitrateToken(streamRevision,inputRevision) && permit?.invoke()==true
    }

    private fun publish() {
        val inputs=streamInputs()
        val rate=controller?.state?.value ?: NovaLiveBitrateState()
        val allowed=current() && !denied && status?.let { owned(it) && key(it)==identity }==true && inputs.readOnlyReason==null
        val reason=when {
            inputs.readOnlyReason!=null -> inputs.readOnlyReason
            status==null -> "Stream status unavailable"
            denied || !allowed -> "This stream does not allow tuning"
            rate.units==NovaBitrateUnits.UNKNOWN -> "Bitrate units unavailable"
            !rate.canChange -> "This host cannot change this stream's bitrate"
            else -> "Changed for this stream only"
        }
        val numbers=if(inputs.width>0 && inputs.height>0 && inputs.fps>0)
            "${NovaSize(inputs.width,inputs.height).label} · ${inputs.fps} fps · ${rate.codec.uppercase()}" else "Stream details unavailable"
        mutableState.value=NovaLiveBitratePresentation(rate.copy(canChange=allowed && rate.canChange,busy=busy.get() || rate.busy),
            identity?.let { NovaLiveBitrateToken(streamRevision,inputRevision) },numbers,reason,result)
    }

    suspend fun change(token:NovaLiveBitrateToken?,menuCurrent:()->Boolean,direction:Int?=null,kbps:Int?=null):NovaBitrateChange {
        val captured=synchronized(lock) {
            // Refresh mode ownership even before the next periodic status reading arrives.
            observe(api,connection,status)
            val attached=controller ?: return NovaBitrateChange.UNAVAILABLE
            val presentation=mutableState.value
            if (token==null || token!=presentation.token || !presentation.rate.canChange ||
                (direction==null && kbps==null && presentation.rate.recommendedKbps==null) ||
                !menuCurrent() || !busy.compareAndSet(false,true)) return NovaBitrateChange.UNAVAILABLE
            operationToken=token;operationInputs=streamInputs();permit=menuCurrent
            result="Changing for this stream";publish()
            attached
        }
        try {
            val outcome=when { direction!=null -> captured.step(direction);kbps!=null -> captured.setBitrate(kbps);else -> captured.useRecommended() }
            synchronized(lock) {
                if (controller===captured && token==mutableState.value.token) result=when(outcome) {
                    NovaBitrateChange.APPLIED -> "Changed for this stream"
                    NovaBitrateChange.AT_LIMIT -> "This stream is already at that bitrate"
                    NovaBitrateChange.SESSION_CHANGED -> "Stream changed. Reopen Command Center"
                    else -> "Could not change bitrate. Try again"
                }
            }
            return outcome
        } finally { synchronized(lock) { operationToken=null;operationInputs=null;permit=null;busy.set(false);publish() } }
    }
}

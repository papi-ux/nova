package com.papi.nova.manager

import com.papi.nova.api.*
import com.papi.nova.preferences.NovaBitrateAdvice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

interface NovaLiveBitrateTransport {
    fun status():PolarisSessionStatus?
    fun confirmedKbps():Int? = null
    fun setBitrate(kbps:Int,observed:PolarisSessionStatus):Boolean
    fun write(encoderKbps:Int,observed:PolarisSessionStatus):PolarisBitrateWriteResult =
        if(setBitrate(encoderKbps,observed)) PolarisBitrateWriteResult.Applied(confirmedKbps() ?: encoderKbps,observed)
        else PolarisBitrateWriteResult.Failed
}
data class NovaLiveBitrateState(val requestedKbps:Int?=null,val recommendedKbps:Int?=null,val receivedKbps:Int?=null,
    val codec:String="",val maximumKbps:Int=300000,val canChange:Boolean=false,val busy:Boolean=false,
    val minimumKbps:Int=2000)
enum class NovaBitrateChange { APPLIED, UNAVAILABLE, SESSION_CHANGED, FAILED, AT_LIMIT }

/** Request units in the UI; encoder units at /session/bitrate. Released hosts cannot write here. */
class NovaLiveBitrateController(private val transport:NovaLiveBitrateTransport,private val sessionId:String,
    private val generation:Long,private val streamScopedWritesSupported:Boolean=false) {
    constructor(api:PolarisApiClient,observed:PolarisSessionStatus,capabilities:PolarisCapabilities?=null):this(object:NovaLiveBitrateTransport {
        override fun status()=api.getSessionStatus()
        override fun setBitrate(kbps:Int,observed:PolarisSessionStatus)=api.setBitrate(kbps,observed)
        override fun write(encoderKbps:Int,observed:PolarisSessionStatus)=api.setBitrateResult(encoderKbps,observed)
    },observed.appSessionId,observed.sessionGeneration,capabilities?.features?.pyrowaveAdviceV1==true)

    private val mutex=Mutex()
    private val observationLock=Any()
    private val mutableState=MutableStateFlow(NovaLiveBitrateState())
    val state=mutableState.asStateFlow()
    private var hostMaximum:Int?=null
    private var lastHost=""
    private var lastSequence=-1L
    private var ackFloor=-1L
    private var ackEncoder:Int?=null
    private var ackRequest:Int?=null
    private var tableRecommendation:Int?=null
    private fun same(s:PolarisSessionStatus)=sessionId.isNotBlank() && generation>0 && s.appSessionId==sessionId && s.sessionGeneration==generation
    private fun allowed(s:PolarisSessionStatus)=streamScopedWritesSupported && same(s) && s.streamingActive && !s.shutdownRequested &&
        !s.isViewer && s.canAdjustHostTuning && !s.liveTuningUnavailable && s.liveTuning?.supported==true
    private fun maximum(s:PolarisSessionStatus)=minOf(300000,hostMaximum ?: 300000,s.pyrowaveBitrate?.hostMaximumKbps ?: 300000,s.pyrowaveBitrate?.capKbps ?: 300000)
    private fun encoder(request:Int,s:PolarisSessionStatus)=NovaBitrateAdvice.encoderForRequest(request,s.pyrowaveBitrate?.audioKbps ?: 512,s.pyrowaveBitrate?.fecPercent ?: 10)
    private fun request(encoder:Int,s:PolarisSessionStatus):Int {
        if(encoder==ackEncoder && ackRequest!=null) return ackRequest!!
        val raw=NovaBitrateAdvice.requestForEncoder(encoder,s.pyrowaveBitrate?.audioKbps ?: 512,s.pyrowaveBitrate?.fecPercent ?: 10)
        val grid=(raw/500.0).roundToInt()*500
        return if(abs(grid-raw)<=1) grid else raw
    }
    private fun minimum(s:PolarisSessionStatus)=NovaBitrateAdvice.requestForEncoder(1000,s.pyrowaveBitrate?.audioKbps ?: 512,s.pyrowaveBitrate?.fecPercent ?: 10)
    private fun recommended(s:PolarisSessionStatus):Int? = (if(s.encoder.codec.equals("pyrowave",true)) s.pyrowaveBitrate?.raiseGoalKbps
        else tableRecommendation)?.takeIf { it>=minimum(s) }?.coerceAtMost(maximum(s))

    fun observe(status:PolarisSessionStatus?,receivedKbps:Int?=null,tableRecommendedKbps:Int?=null,hostMaximumKbps:Int?=null)=synchronized(observationLock) {
        val valid=status?.takeIf(::same)
        if(valid==null) {
            mutableState.update { NovaLiveBitrateState(busy=it.busy) }
            return@synchronized
        }
        val live=valid.liveTuning
        if(live!=null && live.hostInstance==lastHost && live.sequence<lastSequence) return@synchronized
        if(live!=null && lastHost.isNotEmpty() && live.hostInstance!=lastHost) {
            ackFloor=-1;ackEncoder=null;ackRequest=null;hostMaximum=null;lastSequence=-1
        }
        if(hostMaximumKbps!=null) hostMaximum=hostMaximumKbps.takeIf { it in 1000..300000 }
        if(tableRecommendedKbps!=null) tableRecommendation=tableRecommendedKbps.takeIf { it in 1000..300000 }
        val current=if(live!=null && live.hostInstance==lastHost && live.sequence<=ackFloor) ackRequest
            else live?.requestedBitrateKbps?.takeIf { it>0 }?.let { request(it,valid) }
        if(live!=null) { lastHost=live.hostInstance;lastSequence=maxOf(lastSequence,live.sequence) }
        mutableState.update { old -> old.copy(requestedKbps=current,recommendedKbps=recommended(valid),
            receivedKbps=receivedKbps?.takeIf { valid.streamingActive && it>0 },codec=valid.encoder.codec,
            maximumKbps=maximum(valid),minimumKbps=minimum(valid),canChange=allowed(valid)) }
    }

    suspend fun useRecommended()=change(null,null)
    suspend fun setBitrate(kbps:Int)=change(kbps,null)
    suspend fun step(direction:Int):NovaBitrateChange = if(direction in listOf(-1,1)) change(null,direction) else NovaBitrateChange.UNAVAILABLE

    private suspend fun change(value:Int?,direction:Int?):NovaBitrateChange=mutex.withLock {
        if(!streamScopedWritesSupported) return@withLock NovaBitrateChange.UNAVAILABLE
        mutableState.update { it.copy(busy=true) }
        try { withContext(Dispatchers.IO) {
            val observed=transport.status() ?: return@withContext NovaBitrateChange.UNAVAILABLE
            if(!same(observed)) { observe(null);return@withContext NovaBitrateChange.SESSION_CHANGED }
            if(!allowed(observed)) { mutableState.update { it.copy(canChange=false) };return@withContext NovaBitrateChange.UNAVAILABLE }
            val desired=synchronized(observationLock) {
                val live=observed.liveTuning!!
                val current=if(live.hostInstance==lastHost && live.sequence<=ackFloor) ackRequest else
                    live.requestedBitrateKbps.takeIf { it>0 }?.let { request(it,observed) }
                val wanted=when {
                    direction!=null && current==null -> null
                    direction!=null && observed.encoder.codec.equals("pyrowave",true) -> ((current!!*(1+direction*0.1))/500).roundToInt()*500
                    direction!=null -> current!!+direction*5000
                    value!=null -> value
                    else -> recommended(observed)
                }
                if(wanted==null || maximum(observed)<minimum(observed)) null else {
                    val clamped=wanted.coerceIn(minimum(observed),maximum(observed))
                    Triple(clamped,current,encoder(clamped,observed))
                }
            } ?: return@withContext NovaBitrateChange.UNAVAILABLE
            if(desired.first==desired.second) return@withContext NovaBitrateChange.AT_LIMIT
            when(val result=transport.write(desired.third,observed)) {
                PolarisBitrateWriteResult.Failed -> NovaBitrateChange.FAILED
                PolarisBitrateWriteResult.SessionChanged -> { observe(null);NovaBitrateChange.SESSION_CHANGED }
                is PolarisBitrateWriteResult.Applied -> synchronized(observationLock) {
                    if (!same(result.observed) || result.observed.liveTuning == null) {
                        observe(null)
                        return@synchronized NovaBitrateChange.SESSION_CHANGED
                    }
                    val live=result.observed.liveTuning
                    val actual=if(result.encoderKbps==desired.third) desired.first else request(result.encoderKbps,observed)
                    ackFloor=maxOf(lastSequence,live.sequence);lastHost=live.hostInstance
                    ackEncoder=result.encoderKbps;ackRequest=actual
                    if(actual<desired.first) hostMaximum=minOf(hostMaximum ?: 300000,actual)
                    mutableState.update { it.copy(requestedKbps=actual,recommendedKbps=recommended(observed),
                        maximumKbps=maximum(observed),canChange=true) }
                    NovaBitrateChange.APPLIED
                }
            }
        } } finally { mutableState.update { it.copy(busy=false) } }
    }
}

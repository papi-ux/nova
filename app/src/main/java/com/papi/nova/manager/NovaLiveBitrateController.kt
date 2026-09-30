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

/** VIDEO is the encoder target; REQUEST is the split budget, including actual audio and FEC. */
enum class NovaBitrateUnits { VIDEO, REQUEST, UNKNOWN }
data class NovaLiveBitrateState(val requestedKbps:Int?=null,val recommendedKbps:Int?=null,val receivedKbps:Int?=null,
    val codec:String="",val maximumKbps:Int=NovaBitrateAdvice.LEGACY_MANUAL_MAX_KBPS,val canChange:Boolean=false,val busy:Boolean=false,
    val minimumKbps:Int=1000,val units:NovaBitrateUnits=NovaBitrateUnits.UNKNOWN,
    val negotiatedUnits:PolarisBitrateUnits?=null)
enum class NovaBitrateChange { APPLIED, UNAVAILABLE, SESSION_CHANGED, FAILED, AT_LIMIT }

/** Converts only with advertised session inputs. Released hosts remain read-only. */
class NovaLiveBitrateController(private val transport:NovaLiveBitrateTransport,private val sessionId:String,
    private val generation:Long,private val streamScopedWritesSupported:Boolean=false,
    private val bitrateUnitsSupported:Boolean=false,
    manualBitrateMaxKbps:Int=NovaBitrateAdvice.LEGACY_MANUAL_MAX_KBPS) {
    constructor(api:PolarisApiClient,observed:PolarisSessionStatus,capabilities:PolarisCapabilities?=null):this(object:NovaLiveBitrateTransport {
        override fun status()=api.getSessionStatus()
        override fun setBitrate(kbps:Int,observed:PolarisSessionStatus)=api.setBitrate(kbps,observed)
        override fun write(encoderKbps:Int,observed:PolarisSessionStatus)=api.setBitrateResult(encoderKbps,observed)
    },observed.appSessionId,observed.sessionGeneration,
        capabilities?.features?.let { it.pyrowaveAdviceV1 || it.bitrateUnitsV1 }==true,
        capabilities?.features?.bitrateUnitsV1==true,
        capabilities?.features?.manualBitrateMaxKbps ?: NovaBitrateAdvice.LEGACY_MANUAL_MAX_KBPS)

    private val manualMaximum=NovaBitrateAdvice.manualMaximum(manualBitrateMaxKbps)
    private val mutex=Mutex()
    private val observationLock=Any()
    private val mutableState=MutableStateFlow(NovaLiveBitrateState(maximumKbps=manualMaximum))
    val state=mutableState.asStateFlow()
    private var hostMaximum:Int?=null // request units from preflight
    private var learnedMaximum:Int?=null // current row units, learned from acknowledgements
    private var learnedMinimum:Int?=null
    private var lastHost=""
    private var lastSequence=-1L
    private var ackFloor=-1L
    private var ackEncoder:Int?=null
    private var ackRequest:Int?=null
    private var awaitingBarrier=false
    private var tableRecommendation:Int?=null
    private fun same(s:PolarisSessionStatus)=sessionId.isNotBlank() && generation>0 && s.appSessionId==sessionId && s.sessionGeneration==generation
    private fun negotiatedUnits(s:PolarisSessionStatus)=s.bitrateUnits.takeIf { bitrateUnitsSupported }
    private fun assumptions(s:PolarisSessionStatus):Pair<Int,Int>? = if(bitrateUnitsSupported) {
        // The advertised session contract is authoritative, including an inapplicable split.
        negotiatedUnits(s)?.takeIf { it.splitKbps!=null }?.let { it.audioKbps to it.fecPercent }
    } else s.pyrowaveBitrate?.takeIf { s.encoder.codec.equals("pyrowave",true) && it.assumptionsKnown }
        ?.let { it.audioKbps to it.fecPercent }
    private fun units(s:PolarisSessionStatus)=when {
        assumptions(s)!=null -> NovaBitrateUnits.REQUEST
        s.encoder.codec.lowercase() in setOf("h264","hevc","av1") -> NovaBitrateUnits.VIDEO
        else -> NovaBitrateUnits.UNKNOWN
    }
    private fun allowed(s:PolarisSessionStatus)=streamScopedWritesSupported && same(s) && s.streamingActive && !s.shutdownRequested &&
        !s.isViewer && s.canAdjustHostTuning && !s.liveTuningUnavailable && s.liveTuning?.supported==true && units(s)!=NovaBitrateUnits.UNKNOWN
    private fun maximum(s:PolarisSessionStatus):Int {
        val ceiling=manualMaximum
        // PyroWave cap_kbps limits automatic advice, not a player's manual choice.
        val requestCap=if(units(s)==NovaBitrateUnits.REQUEST) minOf(hostMaximum ?: ceiling,
            s.pyrowaveBitrate?.hostMaximumKbps ?: ceiling) else ceiling
        return minOf(ceiling,requestCap,learnedMaximum ?: ceiling)
    }
    private fun encoder(request:Int,s:PolarisSessionStatus):Int = assumptions(s)?.let {
        NovaBitrateAdvice.encoderForRequest(request,it.first,it.second)
    } ?: request
    private fun request(encoder:Int,s:PolarisSessionStatus):Int {
        val pair=assumptions(s) ?: return encoder
        // Preserve the exact negotiated budget despite integer rounding in the host formula.
        // requestedKbps is the original client request, before warp and the handshake cap.
        negotiatedUnits(s)?.takeIf { it.encoderKbps==encoder }?.splitKbps?.let { return it }
        val raw=NovaBitrateAdvice.requestForEncoder(encoder,pair.first,pair.second)
        val grid=(raw/500.0).roundToInt()*500
        return if(abs(grid-raw)<=1) grid else raw
    }
    private fun minimum(s:PolarisSessionStatus)=maxOf(learnedMinimum ?: 1000,request(1000,s))
    private fun recommended(s:PolarisSessionStatus):Int? {
        if(units(s)!=NovaBitrateUnits.REQUEST) return null
        val pyrowave=s.encoder.codec.equals("pyrowave",true)
        val goal=if(pyrowave) s.pyrowaveBitrate?.raiseGoalKbps else tableRecommendation
        val cap=minOf(NovaBitrateAdvice.AUTOMATIC_MAX_KBPS,maximum(s),
            if(pyrowave) s.pyrowaveBitrate?.capKbps ?: NovaBitrateAdvice.AUTOMATIC_MAX_KBPS else NovaBitrateAdvice.AUTOMATIC_MAX_KBPS)
        return goal?.coerceAtMost(cap)?.takeIf { it>=minimum(s) }
    }
    // The live target updates at POST time; encoder statistics arrive on a later tick.
    private fun liveEncoder(s:PolarisSessionStatus)=s.liveTuning?.requestedBitrateKbps?.takeIf { it>0 }
        ?: negotiatedUnits(s)?.liveEncoderKbps?.takeIf { it>0 }
    private fun current(s:PolarisSessionStatus):Int? {
        val live=s.liveTuning
        if(live!=null && live.hostInstance==lastHost && (awaitingBarrier || live.sequence<=ackFloor)) return ackRequest
        return liveEncoder(s)?.let { if(it==ackEncoder && ackRequest!=null) ackRequest else request(it,s) }
    }

    fun observe(status:PolarisSessionStatus?,receivedKbps:Int?=null,tableRecommendedKbps:Int?=null,hostMaximumKbps:Int?=null)=synchronized(observationLock) {
        val valid=status?.takeIf(::same)
        if(valid==null) { mutableState.update { NovaLiveBitrateState(busy=it.busy, maximumKbps=manualMaximum) };return@synchronized }
        val live=valid.liveTuning
        if(live!=null && live.hostInstance==lastHost && live.sequence<lastSequence) return@synchronized
        if((live!=null && lastHost.isNotEmpty() && live.hostInstance!=lastHost) ||
            (mutableState.value.units!=NovaBitrateUnits.UNKNOWN && mutableState.value.units!=units(valid))) {
            ackFloor=-1;ackEncoder=null;ackRequest=null;learnedMaximum=null;learnedMinimum=null;lastSequence=-1;awaitingBarrier=false
        }
        if(awaitingBarrier && live!=null && liveEncoder(valid)==ackEncoder) { ackFloor=live.sequence;awaitingBarrier=false }
        if(hostMaximumKbps!=null) hostMaximum=hostMaximumKbps.takeIf { it>=1000 }?.coerceAtMost(NovaBitrateAdvice.MANUAL_MAX_KBPS)
        if(tableRecommendedKbps!=null) tableRecommendation=tableRecommendedKbps.takeIf { it>=1000 }?.coerceAtMost(NovaBitrateAdvice.AUTOMATIC_MAX_KBPS)
        val current=current(valid)
        if(live!=null) { lastHost=live.hostInstance;lastSequence=maxOf(lastSequence,live.sequence) }
        mutableState.update { old -> old.copy(requestedKbps=current,recommendedKbps=recommended(valid),
            receivedKbps=receivedKbps?.takeIf { valid.streamingActive && it>0 },codec=valid.encoder.codec,
            maximumKbps=maximum(valid),minimumKbps=minimum(valid),canChange=allowed(valid) && !awaitingBarrier,units=units(valid),
            negotiatedUnits=negotiatedUnits(valid)) }
    }

    /** A consumer can withdraw scoped advice without losing acknowledgement barriers or learned bounds. */
    fun replaceAdvice(tableRecommendedKbps:Int?,hostMaximumKbps:Int?)=synchronized(observationLock) {
        tableRecommendation=tableRecommendedKbps?.takeIf { it>=1000 }?.coerceAtMost(NovaBitrateAdvice.AUTOMATIC_MAX_KBPS)
        hostMaximum=hostMaximumKbps?.takeIf { it>=1000 }?.coerceAtMost(NovaBitrateAdvice.MANUAL_MAX_KBPS)
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
            if(mutableState.value.units!=units(observed)) observe(observed,mutableState.value.receivedKbps)
            if(!allowed(observed)) { mutableState.update { it.copy(canChange=false) };return@withContext NovaBitrateChange.UNAVAILABLE }
            val desired=synchronized(observationLock) {
                val current=current(observed)
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
                is PolarisBitrateWriteResult.Applied -> {
                    // The API receipt contains a pre-POST observation. Only a post-POST GET
                    // fences polls generated between that observation and the acknowledged write.
                    val after=transport.status()
                    synchronized(observationLock) {
                        if(!same(result.observed) || (after!=null && !same(after))) {
                            observe(null);return@synchronized NovaBitrateChange.SESSION_CHANGED
                        }
                        val live=after?.liveTuning ?: result.observed.liveTuning
                            ?: return@synchronized NovaBitrateChange.FAILED
                        val effective=after ?: result.observed
                        val sameConversion=units(effective)==units(observed) && assumptions(effective)==assumptions(observed)
                        val actual=if(sameConversion && result.encoderKbps==desired.third) desired.first else request(result.encoderKbps,effective)
                        if(!sameConversion) { learnedMaximum=null;learnedMinimum=null }
                        ackFloor=maxOf(lastSequence,live.sequence);lastHost=live.hostInstance
                        ackEncoder=result.encoderKbps;ackRequest=actual;awaitingBarrier=after?.liveTuning==null
                        if(sameConversion && actual<desired.first) learnedMaximum=minOf(learnedMaximum ?: NovaBitrateAdvice.MANUAL_MAX_KBPS,actual)
                        if(sameConversion && actual>desired.first && desired.second!=null && desired.first<desired.second!!)
                            learnedMinimum=maxOf(learnedMinimum ?: 1000,actual)
                        mutableState.update { it.copy(requestedKbps=actual,recommendedKbps=recommended(effective),
                            maximumKbps=maximum(effective),minimumKbps=minimum(effective),canChange=!awaitingBarrier && allowed(effective),
                            codec=effective.encoder.codec,units=units(effective),negotiatedUnits=negotiatedUnits(effective)) }
                        if(sameConversion && actual==desired.second) NovaBitrateChange.AT_LIMIT else NovaBitrateChange.APPLIED
                    }
                }
            }
        } } finally { mutableState.update { it.copy(busy=false) } }
    }
}

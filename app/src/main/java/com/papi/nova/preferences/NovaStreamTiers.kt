package com.papi.nova.preferences

import java.security.MessageDigest
import kotlin.math.min

enum class NovaTier { SAVER, RECOMMENDED, MAX, CUSTOM }
enum class NovaDistance { HAND, LAP, ROOM }
enum class NovaLink { WIFI, ETHERNET, OTHER }
enum class NovaCodecChoice(val preference: String, val label: String) {
    AUTO("auto", "Auto"), AVC("neverh265", "H.264"), HEVC("forceh265", "HEVC"),
    AV1("forceav1", "AV1"), PYROWAVE("forcepyrowave", "PyroWave");
    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.preference == value } ?: AUTO
    }
}
data class NovaSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    val pixels: Long get() = width.toLong() * height
    val label: String get() = when (this) {
        NovaSize(3840, 2160) -> "4K"
        NovaSize(2560, 1440) -> "1440p"
        NovaSize(1920, 1080) -> "1080p"
        NovaSize(1280, 720) -> "720p"
        else -> "$width×$height"
    }
    fun fits(other: NovaSize) = width <= other.width && height <= other.height
}
data class NovaDisplayMode(val size: NovaSize, val fps: Int)
data class NovaDecodePoint(val size: NovaSize, val fps: Int, val covered: Boolean = true)
data class NovaFailedDecodePoint(val codec: NovaCodecChoice, val size: NovaSize, val fps: Int)
data class NovaCodecCapability(val codec: NovaCodecChoice, val decoder: String, val points: List<NovaDecodePoint>)
data class NovaDeviceCapabilities(val codecs: List<NovaCodecCapability>, val failed: List<NovaFailedDecodePoint> = emptyList()) {
    fun covered(codec: NovaCodecChoice, size: NovaSize, fps: Int): Boolean =
        codecs.any { it.codec == codec && it.points.any { p -> p.covered && size.fits(p.size) && p.fps >= fps } } &&
            failed.none { it.codec == codec && size.pixels >= it.size.pixels && fps >= it.fps }
}
data class NovaReason(val code: String, val message: String)
typealias NovaLimit = NovaReason
data class NovaStreamPlan(val width: Int, val height: Int, val fps: Int, val codec: NovaCodecChoice,
    val bitrateKbps: Int, val bitrateBasis: NovaBitrateBasis = NovaBitrateBasis.TABLE_V1,
    val reasons: List<NovaReason> = emptyList(), val limits: List<NovaLimit> = emptyList(),
    val available: Boolean = true) {
    val size get() = NovaSize(width, height)
    val numbers get() = "${size.label} · $fps fps · ${NovaBitrateAdvice.text(bitrateKbps, false)} · ${codec.label}"
    val lowestLatency get() = fps >= 90
}
sealed interface NovaFourK {
    data object IsRecommended : NovaFourK
    data object IsMax : NovaFourK
    data class Unavailable(val because: NovaLimit) : NovaFourK
}
data class NovaHostTierLimits(val maxFps: Int = 0, val bitrateCapKbps: Int = 0,
    val mirroredDesktop: NovaSize? = null, val space: Boolean = false,
    val pyrowaveRaiseGoalKbps: Int? = null, val pyrowaveFourKCapKbps: Int? = null,
    val pyrowaveAdviceSize: NovaSize? = null, val pyrowaveAdviceFps: Int? = null,
    val pyrowaveFourKCapLimited: Boolean = false)
data class NovaTierInputs(val panel: NovaSize, val refreshRates: List<Int>, val distance: NovaDistance,
    val capabilities: NovaDeviceCapabilities, val link: NovaLink = NovaLink.OTHER,
    val codec: NovaCodecChoice = NovaCodecChoice.AUTO, val host: NovaHostTierLimits? = null,
    val pyrowave: NovaPyrowaveSupport = NovaPyrowaveSupport(),
    val displayModes: List<NovaDisplayMode> = emptyList())
data class NovaPyrowaveSupport(val available: Boolean = false, val maxSize: NovaSize = NovaSize(3840,2160),
    val maxFps: Int = 240, val unavailable: NovaReason? = null) {
    companion object {
        fun from(status: com.papi.nova.binding.video.PyroWaveAvailability.Status,
            host: com.papi.nova.api.PolarisCapabilities.PyrowaveUnavailable? = null): NovaPyrowaveSupport =
            NovaPyrowaveSupport(status == com.papi.nova.binding.video.PyroWaveAvailability.Status.AVAILABLE && host == null,
                unavailable = host?.let { NovaReason(it.reason,it.message) }
                    ?: if (status != com.papi.nova.binding.video.PyroWaveAvailability.Status.AVAILABLE)
                        NovaReason("pyrowave_unavailable","PyroWave is unavailable on this device") else null)
    }
}
data class NovaStreamPins(val size: NovaSize? = null, val fps: Int? = null,
    val codec: NovaCodecChoice? = null, val bitrateKbps: Int? = null)

data class NovaStreamTiers(val saver: NovaStreamPlan, val recommended: NovaStreamPlan, val max: NovaStreamPlan,
    val fourK: NovaFourK, val custom: NovaStreamPlan?, val inputsHash: String, val version: Int = 1) {
    fun plan(tier: NovaTier): NovaStreamPlan = when (tier) {
        NovaTier.SAVER -> saver
        NovaTier.RECOMMENDED -> recommended
        NovaTier.MAX -> max
        NovaTier.CUSTOM -> custom ?: recommended
    }
    /** UI keeps fixed positions; a duplicate Max is represented by Recommended. */
    val mergedMax: Boolean get() = max.copy(reasons = emptyList(), limits = emptyList()) ==
        recommended.copy(reasons = emptyList(), limits = emptyList())
    val availableTiers: List<NovaTier> get() = listOf(NovaTier.SAVER, NovaTier.RECOMMENDED) +
        (if (mergedMax) emptyList() else listOf(NovaTier.MAX)) + (if (custom == null) emptyList() else listOf(NovaTier.CUSTOM))

    companion object {
        val FOUR_K = NovaSize(3840, 2160)
        fun scaled(panel: NovaSize, height: Int): NovaSize = if (panel.height <= height) panel else
            NovaSize((panel.width.toLong() * height / panel.height / 16 * 16).toInt().coerceAtLeast(16), height)

        fun candidateSizes(panel: NovaSize): List<NovaSize> = (listOf(NovaSize(1280, 720), NovaSize(1920, 1080),
            NovaSize(2560, 1440), FOUR_K, panel, scaled(panel, 720), scaled(panel, 1080))).distinct()

        /** Device Settings never depend on whichever host was last viewed. */
        fun forDevice(inputs: NovaTierInputs, custom: NovaStreamPlan? = null) = generate(inputs.copy(host = null), custom)

        private fun decorate(input: NovaTierInputs, plan: NovaStreamPlan, tier: NovaTier): NovaStreamPlan {
            var result=plan
            if (tier == NovaTier.RECOMMENDED && input.distance == NovaDistance.ROOM && input.link == NovaLink.WIFI && result.bitrateKbps > 50000 && result.bitrateBasis != NovaBitrateBasis.CUSTOM)
                result=result.copy(bitrateKbps=50000,limits=result.limits+NovaLimit("wifi_hold",
                    "Held to 50 Mbps on Wi-Fi · Ethernet allows ${result.bitrateKbps/1000}"))
            if (tier == NovaTier.MAX && result.size.pixels > input.panel.pixels)
                result=result.copy(reasons=result.reasons+NovaReason("above_native","Sharper than this screen"))
            return result
        }

        fun generate(inputs: NovaTierInputs, custom: NovaStreamPlan? = null): NovaStreamTiers {
            val top=inputs.refreshRates.filter { it in 1..240 }.maxOrNull() ?: 60
            val panel=inputs.host?.mirroredDesktop ?: inputs.panel
            val desired=scaled(panel,if(inputs.distance==NovaDistance.HAND) 1080 else 2160)
            var recommended=decorate(inputs,constrain(inputs,desired,top,inputs.codec,null),NovaTier.RECOMMENDED)
            if(inputs.distance==NovaDistance.HAND && panel.height>1080 && recommended.available)
                recommended=recommended.copy(reasons=listOf(NovaReason("hand_cap","Sharper than 1080p is hard to see at arm's length"))+recommended.reasons)
            val saver=constrain(inputs,scaled(panel,if(inputs.distance==NovaDistance.ROOM && recommended.height>1080) 1080 else 720),min(top,60),inputs.codec,null)
            val maximum=candidateSizes(panel).filter { it.pixels<=FOUR_K.pixels }.sortedByDescending { it.pixels }.firstNotNullOfOrNull { size ->
                constrain(inputs,size,top,inputs.codec,null).takeIf { it.available && it.size==size && it.fps>=min(top,60) }
            } ?: recommended
            val max=decorate(inputs,maximum,NovaTier.MAX)
            val fourK=when {
                max.available && max.size==FOUR_K && recommended.limits.any { it.code=="wifi_hold" } -> NovaFourK.IsMax
                recommended.available && recommended.size==FOUR_K -> NovaFourK.IsRecommended
                max.available && max.size==FOUR_K -> NovaFourK.IsMax
                else -> NovaFourK.Unavailable(fourKFailure(inputs,max))
            }
            val hash=MessageDigest.getInstance("SHA-256").digest(inputs.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            return NovaStreamTiers(saver,recommended,max,fourK,custom,hash)
        }

        fun resolve(inputs: NovaTierInputs, tier: NovaTier, custom: NovaStreamPlan? = null,
            pins: NovaStreamPins = NovaStreamPins()): NovaStreamPlan {
            val original=generate(inputs,custom).plan(tier)
            if(pins==NovaStreamPins()) return original
            val choice=pins.codec ?: if(tier==NovaTier.CUSTOM) original.codec else inputs.codec
            val plan=constrain(inputs,pins.size ?: original.size,pins.fps ?: original.fps,choice,pins.bitrateKbps,
                fixedSize=original.size.takeIf { pins.fps!=null && pins.size==null },
                fixedFps=original.fps.takeIf { pins.size!=null && pins.fps==null })
            return decorate(inputs,plan,tier)
        }

        private fun codecOrder(choice: NovaCodecChoice)=if(choice==NovaCodecChoice.AUTO)
            listOf(NovaCodecChoice.HEVC,NovaCodecChoice.AVC) else listOf(choice)

        private fun pyrowaveCapLimited(input: NovaTierInputs, fps: Int): Boolean {
            val host=input.host ?: return false
            val cap=host.pyrowaveFourKCapKbps ?: return false
            return host.pyrowaveFourKCapLimited || NovaBitrateAdvice.recommend(3840,2160,fps,
                NovaCodecChoice.PYROWAVE,input.distance).kbps > cap
        }

        private fun hostAdvice(input: NovaTierInputs, size: NovaSize, fps: Int): Int? {
            val host=input.host ?: return null
            val advisedSize=host.pyrowaveAdviceSize ?: scaled(host.mirroredDesktop ?: input.panel,
                if(input.distance==NovaDistance.HAND) 1080 else 2160)
            val advisedFps=host.pyrowaveAdviceFps ?: input.refreshRates.maxOrNull() ?: 60
            return host.pyrowaveRaiseGoalKbps.takeIf { size==advisedSize && fps==advisedFps }
        }

        private fun supports(input:NovaTierInputs,codec:NovaCodecChoice,size:NovaSize,fps:Int,choice:NovaCodecChoice):Boolean {
            if(codec==NovaCodecChoice.AVC && size.pixels>=FOUR_K.pixels) return false
            if(codec==NovaCodecChoice.PYROWAVE) return input.pyrowave.available && size.fits(input.pyrowave.maxSize) && fps<=input.pyrowave.maxFps &&
                !(size.pixels>=FOUR_K.pixels && pyrowaveCapLimited(input,fps))
            if(input.capabilities.failed.any { (it.codec==codec || choice==NovaCodecChoice.AUTO) &&
                    size.pixels>=it.size.pixels && fps>=it.fps }) return false
            if(input.capabilities.covered(codec,size,fps)) return true
            val points=input.capabilities.codecs.filter { it.codec==codec }.flatMap { it.points }
            return input.capabilities.codecs.filter { it.codec==codec }.none { it.points.any { p -> p.covered } } && points.any { size.fits(it.size) && fps<=it.fps }
        }

        private fun displayTop(input: NovaTierInputs, size: NovaSize): Int {
            if (input.displayModes.isEmpty()) return input.refreshRates.maxOrNull() ?: 60
            // Above-native streams downscale at the physical panel's refresh ceiling.
            val physical = if (size.fits(input.panel)) size else input.panel
            return input.displayModes.filter { physical.fits(it.size) }.maxOfOrNull { it.fps } ?: 0
        }

        private fun constrain(input:NovaTierInputs,requested:NovaSize,requestedFps:Int,choice:NovaCodecChoice,bitratePin:Int?,
            fixedSize:NovaSize?=null,fixedFps:Int?=null):NovaStreamPlan {
            val host=input.host
            val limits=mutableListOf<NovaLimit>()
            val choices=if(host?.space==true) listOf(NovaCodecChoice.AVC) else codecOrder(choice)
            val panelTop=input.refreshRates.maxOrNull() ?: 60
            var top=min(requestedFps,panelTop)
            if(top<requestedFps) limits+=NovaLimit("panel_fps","This screen tops out at $top Hz")
            if(host!=null && host.maxFps in 1 until top) {
                top=host.maxFps;limits+=NovaLimit("host_fps","The host caps this at $top fps")
            }
            val ceiling=host?.mirroredDesktop
            if(ceiling!=null && !requested.fits(ceiling)) limits+=NovaLimit("host_desktop","Mirroring the host's ${ceiling.width}×${ceiling.height} desktop")
            val sizes=(listOf(requested)+candidateSizes(requested)).distinct().filter {
                it.fits(requested) && (ceiling==null || it.fits(ceiling)) && (fixedSize==null || it==fixedSize)
            }.sortedByDescending { it.pixels }
            val rates=(input.refreshRates+listOfNotNull(host?.maxFps?.takeIf { it>0 })).distinct()
                .filter { it in 1..top && (fixedFps==null || it==fixedFps) }.sortedDescending()
            var selected:Triple<NovaSize,Int,NovaCodecChoice>?=null
            for(floor in listOf(min(top,60),1).distinct()) {
                selected=sizes.firstNotNullOfOrNull { size -> rates.filter { it>=floor && it<=displayTop(input,size) }.firstNotNullOfOrNull { fps ->
                    choices.firstOrNull { supports(input,it,size,fps,choice) }?.let { Triple(size,fps,it) }
                } }
                if(selected!=null) break
            }
            if(selected==null) {
                val reason=if(choice==NovaCodecChoice.PYROWAVE) input.pyrowave.unavailable else null
                return NovaStreamPlan(requested.width,requested.height,top,choices.first(),bitratePin?.coerceIn(500,NovaBitrateAdvice.MANUAL_MAX_KBPS) ?: NovaBitrateAdvice.recommend(
                    requested.width,requested.height,top.coerceAtLeast(1),choices.first(),input.distance,hostAdvice(input,requested,top)).kbps,
                    limits=limits+(reason ?: NovaLimit("decoder_unavailable","No usable decoder point for this stream")),available=false)
            }
            val (size,fps,codec)=selected
            val sizeTop=displayTop(input,size)
            if(sizeTop<top) limits+=NovaLimit("panel_fps","This screen displays ${size.label} at up to $sizeTop Hz")
            if(size!=requested || fps<min(top,sizeTop)) limits+=NovaLimit("decoder_limit","This device decodes ${size.label} at $fps fps")
            if(input.capabilities.failed.isNotEmpty()) {
                val withoutFailure=constrain(input.copy(capabilities=input.capabilities.copy(failed=emptyList())),requested,requestedFps,choice,bitratePin,fixedSize,fixedFps)
                if(withoutFailure.size!=size || withoutFailure.fps!=fps || withoutFailure.codec!=codec) {
                    val failed=input.capabilities.failed.firstOrNull { (it.codec==withoutFailure.codec || choice==NovaCodecChoice.AUTO) && withoutFailure.size.pixels>=it.size.pixels && withoutFailure.fps>=it.fps }
                    if(failed!=null) limits.add(0,NovaLimit("decoder_failed","Stepped down after the decoder failed at ${failed.size.label}"))
                }
            }
            val reasons=mutableListOf<NovaReason>()
            if(codec!=NovaCodecChoice.PYROWAVE && !input.capabilities.covered(codec,size,fps))
                reasons+=NovaReason("decoder_claimed","Decoder mode is advertised but unmeasured")
            if(size==input.panel && fps==displayTop(input,input.panel)) reasons+=NovaReason("native_panel","Fills this ${size.label} screen at its full $fps Hz")
            if(codec==NovaCodecChoice.PYROWAVE && hostAdvice(input,size,fps)!=null) reasons+=NovaReason("pyrowave_advice","The host's PyroWave figure for this screen")
            val advice=NovaBitrateAdvice.recommend(size.width,size.height,fps,codec,input.distance,hostAdvice(input,size,fps))
            var bitrate=bitratePin?.coerceIn(500,NovaBitrateAdvice.MANUAL_MAX_KBPS) ?: advice.kbps
            if(host!=null && host.bitrateCapKbps in 1 until bitrate) {
                bitrate=host.bitrateCapKbps;limits+=NovaLimit("host_bitrate","The host caps bitrate at ${bitrate/1000} Mbps")
            }
            if(host?.space==true) { bitrate=min(bitrate,8000);limits+=NovaLimit("space","This Space streams H.264 at up to 8 Mbps") }
            return NovaStreamPlan(size.width,size.height,fps,codec,bitrate,if(bitratePin==null) advice.basis else NovaBitrateBasis.CUSTOM,reasons,limits)
        }

        private fun fourKFailure(input:NovaTierInputs,max:NovaStreamPlan):NovaLimit {
            val selected=if(input.codec==NovaCodecChoice.AUTO) NovaCodecChoice.HEVC else input.codec
            val decoderCodec=if(selected==NovaCodecChoice.AVC) NovaCodecChoice.HEVC else selected
            val codecInput=input.copy(host=null)
            if(input.codec==NovaCodecChoice.AUTO && !input.capabilities.covered(NovaCodecChoice.HEVC,FOUR_K,60) &&
                input.capabilities.covered(NovaCodecChoice.AV1,FOUR_K,60))
                return NovaLimit("codec","4K at 60 fps needs AV1 · choose AV1")
            val fourKFps=min(60,input.refreshRates.maxOrNull() ?: 60)
            if(!supports(codecInput,decoderCodec,FOUR_K,fourKFps,input.codec) &&
                supports(codecInput.copy(capabilities=codecInput.capabilities.copy(failed=emptyList())),decoderCodec,FOUR_K,fourKFps,input.codec))
                return NovaLimit("decoder_failed","Stepped down after the decoder failed at 4K")
            if(!supports(codecInput,decoderCodec,FOUR_K,fourKFps,input.codec)) {
                val thirty=supports(codecInput,decoderCodec,FOUR_K,30,input.codec)
                return NovaLimit("decoder_limit",if(thirty) "4K: this decoder tops out at 30 fps" else
                    "4K: this device decodes up to ${max.width}×${max.height}")
            }
            if(selected==NovaCodecChoice.AVC) return NovaLimit("codec","4K at 60 fps needs HEVC · H.264 is chosen")
            input.host?.mirroredDesktop?.takeIf { !FOUR_K.fits(it) }?.let {
                return NovaLimit("host_desktop","4K: the host's desktop is ${it.width}×${it.height}")
            }
            if(input.host?.space==true) return NovaLimit("space","This Space streams H.264 at up to 8 Mbps")
            if(selected==NovaCodecChoice.PYROWAVE && pyrowaveCapLimited(input,fourKFps)) input.host?.pyrowaveFourKCapKbps?.let {
                return NovaLimit("pyrowave_cap","PyroWave at 4K is past the host's ${it/1000} Mbps cap")
            }
            return NovaLimit("decoder_limit","4K: this device decodes up to ${max.width}×${max.height}")
        }

        fun customDelta(custom:NovaStreamPlan,recommended:NovaStreamPlan):String {
            val parts=listOfNotNull(
                (custom.size.label to recommended.size.label).takeIf { custom.size!=recommended.size },
                ("${custom.fps} fps" to "${recommended.fps} fps").takeIf { custom.fps!=recommended.fps },
                (NovaBitrateAdvice.text(custom.bitrateKbps,false) to NovaBitrateAdvice.text(recommended.bitrateKbps,false).removeSuffix(" Mbps"))
                    .takeIf { custom.bitrateKbps!=recommended.bitrateKbps },
                (custom.codec.label to recommended.codec.label).takeIf { custom.codec!=NovaCodecChoice.AUTO && custom.codec!=recommended.codec })
            if(parts.isEmpty()) return "Custom · same as Recommended"
            for(count in min(2,parts.size) downTo 1) {
                val suffix=if(parts.size>count) " +${parts.size-count}" else ""
                val text="Custom · ${parts.take(count).joinToString(", ") { it.first }}$suffix (Recommended ${parts.take(count).joinToString(", ") { it.second }})"
                if(text.length<=56 || count==1) return text
            }
            return "Custom"
        }
    }
}

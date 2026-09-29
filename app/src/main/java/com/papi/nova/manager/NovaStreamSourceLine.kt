package com.papi.nova.manager

import com.papi.nova.preferences.NovaBitrateAdvice
import com.papi.nova.preferences.NovaSize
import org.json.JSONObject
import kotlin.math.abs

enum class NovaStreamSource { DEVICE, HOST_SAVED_COPY, HOST_CAP, HOST_POLICY, SPACE, WATCH, UNKNOWN }
data class NovaStreamSourceRequest(val width:Int,val height:Int,val fps:Double,val bitrateKbps:Int,
    val who:String="Recommended")
data class NovaStreamSourceLine(val source:NovaStreamSource,val text:String,val capKbps:Int?=null,
    val limitCodes:List<String> = emptyList()) {
    companion object {
        fun fromPreflight(optimization:JSONObject,request:NovaStreamSourceRequest?=null):NovaStreamSourceLine {
            if(optimization.optString("source")=="worker_profile_v1") return space()
            val fields=optimization.optJSONObject("resolved_profile")?.takeIf { it.optInt("policy_version")==1 }?.optJSONObject("fields")
                ?: return NovaStreamSourceLine(NovaStreamSource.UNKNOWN,"This host does not report its limits")
            val names=listOf("display_width","display_height","target_fps","target_bitrate_kbps")
            fun field(name:String)=fields.optJSONObject(name)
            fun value(name:String)=(field(name)?.opt("value") as? Number)?.toDouble()?.takeIf { it.isFinite() && it>0 }
            val asked=request?.let { listOf(it.width.toDouble(),it.height.toDouble(),it.fps,it.bitrateKbps.toDouble()) }
            val saved=asked!=null && names.withIndex().any { (i,name) -> field(name)?.optString("source") in setOf("paired_client","device_profile_v1") &&
                field(name)?.optString("reason_code") != "stability_preset_selected" && value(name)?.let { abs(it-asked[i])>0.5 }==true }
            val reasons=names.mapNotNull { name -> field(name)?.optString("reason_code")?.takeIf { it.isNotEmpty() } }
            val stability="stability_preset_selected" in reasons
            val source=when { saved -> NovaStreamSource.HOST_SAVED_COPY
                request!=null -> NovaStreamSource.DEVICE;else -> NovaStreamSource.UNKNOWN }
            val who=when(source) { NovaStreamSource.HOST_SAVED_COPY -> "Host's saved copy";NovaStreamSource.HOST_POLICY -> "Stability"
                NovaStreamSource.DEVICE -> request!!.who;else -> "Host stream settings" }
            val limits=mutableListOf<Pair<String,String>>()
            val bitrate=value("target_bitrate_kbps")?.toInt()
            if("host_bitrate_cap" in reasons && bitrate!=null) limits += "host_bitrate_cap" to "host cap ${NovaBitrateAdvice.text(bitrate,false)}"
            val fps=value("target_fps")?.toInt()
            if("host_refresh_cap" in reasons && fps!=null) limits += "host_refresh_cap" to "the host caps it at $fps fps"
            if("client_refresh_cap" in reasons && fps!=null) limits += "client_refresh_cap" to "this screen caps it at $fps fps"
            if(stability) limits += "stability_preset_selected" to "Stability preset"
            val width=value("display_width")?.toInt();val height=value("display_height")?.toInt()
            val savedDetail=when {
                !saved || request==null -> ""
                width!=null && height!=null && (width!=request.width || height!=request.height) -> "${width}×$height"
                fps!=null && abs(fps-request.fps)>0.5 -> "$fps fps"
                bitrate!=null && bitrate!=request.bitrateKbps -> NovaBitrateAdvice.text(bitrate,false)
                else -> ""
            }
            val detail=limits.firstOrNull()?.second ?: savedDetail
            val text=who+(if(detail.isEmpty()) "" else " · $detail")
            return NovaStreamSourceLine(source,text.take(56),
                bitrate.takeIf { "host_bitrate_cap" in reasons },limits.map { it.first })
        }
        fun space()=NovaStreamSourceLine(NovaStreamSource.SPACE,"Set by this Space · H.264 up to 8 Mbps",8000)
        fun watch(width:Int,height:Int,fps:Int):NovaStreamSourceLine {
            val detail=if(width in 1..16384 && height in 1..16384 && fps in 1..1000) " · ${NovaSize(width,height).label} at $fps fps" else ""
            return NovaStreamSourceLine(NovaStreamSource.WATCH,"Watching$detail")
        }
    }
}

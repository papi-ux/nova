package com.papi.nova.preferences

import android.app.UiModeManager
import android.content.SharedPreferences
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.Display
import com.papi.nova.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Queries codec metadata only. It never creates MediaCodec or probes an emulator in tests. */
object NovaCapabilityProbe {
    const val STORE = "nova_capability_probe_v1"
    private val rates = listOf(30, 60, 90, 120, 144, 165, 240)

    data class Report(val sizeSupported: Boolean, val performancePointsPresent: Boolean,
        val performancePointCovers: Boolean, val achievableFps: Double?, val claimed: Boolean)

    /** A positive performance report is required; advertised size/rate alone is only claimed. */
    fun classify(size: NovaSize, fps: Int, report: Report): NovaDecodePoint? {
        if (!report.sizeSupported) return null
        val covered = if (report.performancePointsPresent) report.performancePointCovers
            else report.achievableFps?.let { it.isFinite() && it >= fps } ?: false
        return if (covered || report.claimed) NovaDecodePoint(size, fps, covered) else null
    }

    @Synchronized
    fun inspect(context: Context, panel: NovaSize, topFps: Int): NovaDeviceCapabilities {
        val sizes = NovaStreamTiers.candidateSizes(panel)
        val bypassSoftware = com.papi.nova.binding.video.MediaCodecHelper.SHOULD_BYPASS_SOFTWARE_BLOCK
        val glRenderer = GlPreferences.readPreferences(context).glRenderer
        val adreno = if (glRenderer.contains("adreno",true)) Regex("[0-9]{3}").find(glRenderer)?.value?.toIntOrNull() ?: -1 else -1
        val glVersion = (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.deviceConfigurationInfo?.reqGlEsVersion ?: android.content.pm.ConfigurationInfo.GL_ES_VERSION_UNDEFINED
        val blocked = buildList {
            if (!bypassSoftware) {
                add("omx.google");add("avcdecoder")
                if (Build.VERSION.SDK_INT < 29) add("omx.ffmpeg")
            }
            add("omx.qcom.video.decoder.hevcswvdec");add("omx.sec.hevc.sw.dec")
            if (glVersion != android.content.pm.ConfigurationInfo.GL_ES_VERSION_UNDEFINED && adreno < 400)
                add("omx.qcom.video.decoder.hevc")
        }
        // Match the renderer's alias, software and broken-decoder exclusions without mutating
        // MediaCodecHelper's process-wide initialization or depending on its GL startup order.
        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
        val eligible = codecs.filter { info -> runCatching {
            !info.isEncoder && (Build.VERSION.SDK_INT < 29 || (!info.isAlias && (bypassSoftware || !info.isSoftwareOnly))) &&
                blocked.none { info.name.startsWith(it,true) }
        }.getOrDefault(false) }
        val decoders = listOf(NovaCodecChoice.AVC to "video/avc", NovaCodecChoice.HEVC to "video/hevc",
            NovaCodecChoice.AV1 to "video/av01").mapNotNull { (codec, mime) ->
            val candidates = eligible.filter { info -> runCatching {
                info.supportedTypes.any { type -> type.equals(mime,true) }
            }.getOrDefault(false) }
            val preferred = candidates.filter { info -> codec != NovaCodecChoice.AVC || runCatching {
                info.getCapabilitiesForType(mime).profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
            }.getOrDefault(false) }.sortedByDescending { info -> Build.VERSION.SDK_INT >= 30 && runCatching {
                info.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false) }
            (preferred.firstOrNull() ?: candidates.firstOrNull())?.let { Triple(codec, mime, it) }
        }
        val key = "${Build.FINGERPRINT}:${BuildConfig.VERSION_CODE}:$panel:$topFps:" + decoders.joinToString { it.third.name }
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val failed = failures(prefs.getString("failures", null), Build.FINGERPRINT)
        return cachedCapabilities(prefs, key, failed) { decoders.map { (codec, mime, decoder) ->
            val caps = runCatching { decoder.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
            val points = if (caps == null) emptyList() else sizes.flatMap { size ->
                (rates + topFps).distinct().filter { it <= topFps + 2 }.mapNotNull { fps -> runCatching {
                    val performance = if (Build.VERSION.SDK_INT >= 29) caps.supportedPerformancePoints?.takeIf { it.isNotEmpty() } else null
                    classify(size, fps, Report(caps.isSizeSupported(size.width, size.height), performance != null,
                        if (Build.VERSION.SDK_INT >= 29) performance?.any {
                            it.covers(MediaCodecInfo.VideoCapabilities.PerformancePoint(size.width, size.height, fps))
                        } == true else false,
                        if (performance == null && Build.VERSION.SDK_INT >= 23) runCatching {
                            caps.getAchievableFrameRatesFor(size.width, size.height)?.upper
                        }.getOrNull() else null,
                        caps.areSizeAndRateSupported(size.width, size.height, fps.toDouble())))
                }.getOrNull() }
            }
            NovaCodecCapability(codec, decoder.name, points)
        }
        }
    }

    internal fun cachedCapabilities(prefs: SharedPreferences, key: String, failed: List<NovaFailedDecodePoint>,
        query: () -> List<NovaCodecCapability>): NovaDeviceCapabilities {
        if (prefs.getString("key", null) == key) decode(prefs.getString("points", null))?.let {
            return NovaDeviceCapabilities(it, failed)
        }
        val codecs = query()
        if (codecs.isNotEmpty()) prefs.edit().putString("key", key).putString("points", encode(codecs)).apply()
        return NovaDeviceCapabilities(codecs, failed)
    }

    fun deviceInputs(context: Context): NovaTierInputs = deviceInputs(context, deviceEnvironment(context))

    internal fun deviceInputs(context: Context, environment: NovaTierInputs): NovaTierInputs =
        environment.copy(capabilities=inspect(context,environment.panel,environment.refreshRates.maxOrNull() ?: 60))

    /** Display and network metadata; callers keep these service queries on the IO worker. */
    internal fun deviceEnvironment(context: Context): NovaTierInputs {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        val room = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val current = if (Build.VERSION.SDK_INT >= 23) display?.mode else null
        val available = if (Build.VERSION.SDK_INT >= 23) display?.supportedModes.orEmpty().map {
            NovaDisplayMode(NovaSize(maxOf(it.physicalWidth,it.physicalHeight),minOf(it.physicalWidth,it.physicalHeight)),it.refreshRate.roundToInt().coerceIn(1,240))
        } else emptyList()
        fun aspect(mode: NovaDisplayMode) = mode.size.width.toDouble()/mode.size.height
        val currentAspect = current?.let { maxOf(it.physicalWidth,it.physicalHeight).toDouble()/minOf(it.physicalWidth,it.physicalHeight) }
        // Prefer UHD on TVs that also advertise DCI cinema modes, while retaining the
        // actual aspect family on 16:10, ultrawide and DCI-only sinks.
        val aspect = when {
            room && available.any { kotlin.math.abs(aspect(it)/(16.0/9)-1) < 0.02 } -> 16.0/9
            currentAspect != null && available.any { kotlin.math.abs(aspect(it)/currentAspect-1) < 0.02 } -> currentAspect
            else -> available.maxByOrNull { it.size.pixels }?.let(::aspect) ?: 16.0/9
        }
        val modes = available.filter { kotlin.math.abs(aspect(it)/aspect-1) < 0.02 }.distinct().sortedWith(
            compareBy<NovaDisplayMode> { it.size.pixels }.thenBy { it.fps })
        val size = modes.maxByOrNull { it.size.pixels }?.size ?: NovaSize(1920,1080)
        val panelRates = modes.map { it.fps }.distinct().sorted().ifEmpty { listOf(display?.refreshRate?.roundToInt() ?: 60) }
        val distance = if (room) NovaDistance.ROOM else if (context.resources.configuration.smallestScreenWidthDp >= 600)
            NovaDistance.LAP else NovaDistance.HAND
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = if (Build.VERSION.SDK_INT >= 23) runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() else null
        val link = when {
            network?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> NovaLink.ETHERNET
            network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> NovaLink.WIFI
            else -> NovaLink.OTHER
        }
        return NovaTierInputs(size, panelRates, distance, NovaDeviceCapabilities(emptyList()), link, displayModes=modes)
    }

    /** Called before the crash tombstone is committed, using the actual negotiated point. */
    fun recordCrashCandidate(context: Context, point: NovaFailedDecodePoint) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString("crashed_point", pointJson(point).put("fingerprint", Build.FINGERPRINT).toString()).commit()
    }

    @Synchronized
    fun recordResetFailure(context: Context) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val candidate = runCatching { JSONObject(prefs.getString("crashed_point", "")!!) }.getOrNull() ?: return
        if (candidate.optString("fingerprint") != Build.FINGERPRINT) return
        val point = parsePoint(candidate) ?: return
        val points = (failures(prefs.getString("failures", null), Build.FINGERPRINT) + point).distinct().takeLast(32)
        prefs.edit().putString("failures", JSONObject().put("fingerprint", Build.FINGERPRINT)
            .put("points", JSONArray(points.map(::pointJson))).toString()).remove("crashed_point").commit()
    }

    private fun pointJson(point: NovaFailedDecodePoint) = JSONObject().put("codec", point.codec.name)
        .put("width", point.size.width).put("height", point.size.height).put("fps", point.fps)
    private fun parsePoint(json: JSONObject): NovaFailedDecodePoint? = runCatching {
        val w = json.getInt("width"); val h = json.getInt("height"); val fps = json.getInt("fps")
        require(w in 1..8192 && h in 1..8192 && fps in 1..1000)
        NovaFailedDecodePoint(NovaCodecChoice.valueOf(json.getString("codec")), NovaSize(w, h), fps)
    }.getOrNull()
    private fun failures(raw: String?, fingerprint: String): List<NovaFailedDecodePoint> = runCatching {
        val json = JSONObject(raw ?: "{}"); if (json.optString("fingerprint") != fingerprint) return emptyList()
        val points = json.getJSONArray("points")
        (0 until minOf(points.length(), 32)).mapNotNull { parsePoint(points.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    private fun encode(codecs: List<NovaCodecCapability>): String = JSONArray(codecs.map { codec ->
        JSONObject().put("codec", codec.codec.name).put("decoder", codec.decoder).put("points", JSONArray(codec.points.map {
            pointJson(NovaFailedDecodePoint(codec.codec, it.size, it.fps)).put("covered", it.covered)
        }))
    }).toString()
    private fun decode(raw: String?): List<NovaCodecCapability>? = runCatching {
        val array = JSONArray(raw ?: return null); require(array.length() <= 3)
        (0 until array.length()).map { i ->
            val codec = array.getJSONObject(i); val points = codec.getJSONArray("points"); require(points.length() <= 128)
            NovaCodecCapability(NovaCodecChoice.valueOf(codec.getString("codec")), codec.getString("decoder"),
                (0 until points.length()).map { p ->
                    val json = points.getJSONObject(p); val point = requireNotNull(parsePoint(json))
                    NovaDecodePoint(point.size, point.fps, json.getBoolean("covered"))
                })
        }
    }.getOrNull()
}

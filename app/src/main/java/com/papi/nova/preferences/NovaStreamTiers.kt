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
data class NovaDecodePoint(val size: NovaSize, val fps: Int, val covered: Boolean = true)
data class NovaFailedDecodePoint(val codec: NovaCodecChoice, val size: NovaSize, val fps: Int)
data class NovaCodecCapability(val codec: NovaCodecChoice, val decoder: String, val points: List<NovaDecodePoint>)
data class NovaDeviceCapabilities(val codecs: List<NovaCodecCapability>, val failed: List<NovaFailedDecodePoint> = emptyList()) {
    fun covered(codec: NovaCodecChoice, size: NovaSize, fps: Int): Boolean =
        codecs.any { it.codec == codec && it.points.any { p -> p.covered && p.size == size && p.fps >= fps } } &&
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
    val pyrowaveRaiseGoalKbps: Int? = null, val pyrowaveFourKCapKbps: Int? = null)
data class NovaTierInputs(val panel: NovaSize, val refreshRates: List<Int>, val distance: NovaDistance,
    val capabilities: NovaDeviceCapabilities, val link: NovaLink = NovaLink.OTHER,
    val codec: NovaCodecChoice = NovaCodecChoice.AUTO, val host: NovaHostTierLimits? = null)
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

        fun generate(inputs: NovaTierInputs, custom: NovaStreamPlan? = null): NovaStreamTiers {
            val top = inputs.refreshRates.filter { it in 1..240 }.maxOrNull() ?: 60
            val panel = inputs.host?.mirroredDesktop ?: inputs.panel
            val desired = scaled(panel, if (inputs.distance == NovaDistance.HAND) 1080 else 2160)
            val recommended = constrain(inputs, desired, top, inputs.codec, null).let { plan ->
                val wifi = inputs.distance == NovaDistance.ROOM && inputs.link == NovaLink.WIFI && plan.bitrateKbps > 50000
                if (wifi) plan.copy(bitrateKbps = 50000, limits = plan.limits + NovaLimit("wifi_hold",
                    "Held to 50 Mbps on Wi-Fi · Ethernet allows ${plan.bitrateKbps / 1000}")) else plan
            }
            val saverHeight = if (inputs.distance == NovaDistance.ROOM && recommended.height > 1080) 1080 else 720
            val saver = constrain(inputs, scaled(panel, saverHeight), min(top, 60), inputs.codec, null)
            val sizes = candidateSizes(panel).filter { it.pixels <= FOUR_K.pixels }.sortedByDescending { it.pixels }
            val max = sizes.firstNotNullOfOrNull { size ->
                val plan = constrain(inputs, size, top, inputs.codec, null)
                plan.takeIf { it.available && it.size == size && it.fps >= min(top, 60) }
            } ?: recommended
            val labelledMax = if (max.size.pixels > inputs.panel.pixels)
                max.copy(reasons = max.reasons + NovaReason("above_native", "Sharper than this screen")) else max
            val fourK = fourKFailure(inputs)?.let { NovaFourK.Unavailable(it) }
                ?: if (recommended.size == FOUR_K) NovaFourK.IsRecommended else NovaFourK.IsMax
            val hash = MessageDigest.getInstance("SHA-256").digest(inputs.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            return NovaStreamTiers(saver, recommended, labelledMax, fourK, custom, hash)
        }

        fun resolve(inputs: NovaTierInputs, tier: NovaTier, custom: NovaStreamPlan? = null,
            pins: NovaStreamPins = NovaStreamPins()): NovaStreamPlan {
            val original = generate(inputs, custom).plan(tier)
            if (pins == NovaStreamPins()) return original
            return constrain(inputs, pins.size ?: original.size, pins.fps ?: original.fps,
                pins.codec ?: original.codec, pins.bitrateKbps)
        }

        private fun codecOrder(choice: NovaCodecChoice) = if (choice == NovaCodecChoice.AUTO)
            listOf(NovaCodecChoice.HEVC, NovaCodecChoice.AVC) else listOf(choice)

        private fun constrain(input: NovaTierInputs, requested: NovaSize, requestedFps: Int,
            choice: NovaCodecChoice, bitratePin: Int?): NovaStreamPlan {
            val limits = mutableListOf<NovaLimit>()
            val host = input.host
            val codecChoices = if (host?.space == true) listOf(NovaCodecChoice.AVC) else codecOrder(choice)
            var maxFps = min(requestedFps, input.refreshRates.maxOrNull() ?: 60)
            if (maxFps < requestedFps) limits += NovaLimit("panel_fps", "This screen tops out at $maxFps Hz")
            if (host != null && host.maxFps in 1 until maxFps) {
                maxFps = host.maxFps; limits += NovaLimit("host_fps", "The host caps this at $maxFps fps")
            }
            val ceiling = host?.mirroredDesktop?.takeIf { !requested.fits(it) }
            if (ceiling != null) limits += NovaLimit("host_desktop", "Mirroring the host's ${ceiling.width}×${ceiling.height} desktop")
            val sizes = (listOf(requested) + candidateSizes(input.panel)).distinct().filter {
                it.fits(requested) && (ceiling == null || it.fits(ceiling))
            }.sortedByDescending { it.pixels }
            val rates = (input.refreshRates + listOf(30, 60, 90, 120, 144, 165, 240) + maxFps)
                .distinct().filter { it in 1..maxFps }.sortedDescending()
            var result: Triple<NovaSize, Int, NovaCodecChoice>? = null
            // Prefer a smaller smooth picture over a claimed or 30-fps high resolution.
            for (floor in listOf(min(60, maxFps), 1).distinct()) {
                result = sizes.firstNotNullOfOrNull { size ->
                    rates.filter { it >= floor }.firstNotNullOfOrNull { fps ->
                        codecChoices.firstOrNull { !(it == NovaCodecChoice.AVC && size.pixels >= FOUR_K.pixels) && input.capabilities.covered(it, size, fps) }
                            ?.let { Triple(size, fps, it) }
                    }
                }
                if (result != null) break
            }
            if (result == null) return NovaStreamPlan(requested.width, requested.height, maxFps,
                codecChoices.first(), bitratePin ?: 0, limits = limits + NovaLimit("decoder_limit",
                    "No covered decoder point for this stream"), available = false)
            val (size, fps, codec) = result
            if (size != requested || fps < maxFps) limits += NovaLimit("decoder_limit",
                "This device decodes ${size.label} at $fps fps")
            input.capabilities.failed.firstOrNull { it.codec in codecChoices && requested.pixels >= it.size.pixels }?.let {
                limits.add(0, NovaLimit("decoder_failed", "Stepped down after the decoder failed at ${it.size.label}"))
            }
            val advice = NovaBitrateAdvice.recommend(size.width, size.height, fps, codec, input.distance, host?.pyrowaveRaiseGoalKbps)
            var bitrate = bitratePin?.coerceIn(500, 300000) ?: advice.kbps
            if (host != null && host.bitrateCapKbps in 1 until bitrate) {
                bitrate = host.bitrateCapKbps; limits += NovaLimit("host_bitrate", "The host caps bitrate at ${bitrate / 1000} Mbps")
            }
            if (host?.space == true) {
                bitrate = min(bitrate, 8000); limits += NovaLimit("space", "This Space streams H.264 at up to 8 Mbps")
            }
            return NovaStreamPlan(size.width, size.height, fps, codec, bitrate,
                if (bitratePin == null) advice.basis else NovaBitrateBasis.CUSTOM,
                reasons = listOf(NovaReason("native_panel", "${size.label} at this screen's $fps Hz")), limits = limits)
        }

        private fun fourKFailure(input: NovaTierInputs): NovaLimit? {
            val capable = input.capabilities.codecs.any { codec ->
                input.capabilities.covered(codec.codec, FOUR_K, 60)
            }
            if (!capable) {
                val thirty = input.capabilities.codecs.any { input.capabilities.covered(it.codec, FOUR_K, 30) }
                return NovaLimit("decoder_limit", if (thirty) "4K: this decoder tops out at 30 fps" else "4K: this decoder has no covered 60 fps point")
            }
            if (input.codec == NovaCodecChoice.AVC || (input.codec != NovaCodecChoice.PYROWAVE &&
                    codecOrder(input.codec).none { input.capabilities.covered(it, FOUR_K, 60) }))
                return NovaLimit("codec", "4K at 60 fps needs HEVC · ${input.codec.label} is chosen")
            input.host?.mirroredDesktop?.takeIf { !FOUR_K.fits(it) }?.let {
                return NovaLimit("host_desktop", "4K: the host's desktop is ${it.width}×${it.height}")
            }
            if (input.host?.space == true) return NovaLimit("space", "This Space streams H.264 at up to 8 Mbps")
            if (input.codec == NovaCodecChoice.PYROWAVE) input.host?.pyrowaveFourKCapKbps?.let {
                return NovaLimit("pyrowave_cap", "PyroWave at 4K is past the host's ${it / 1000} Mbps cap")
            }
            if ((input.refreshRates.maxOrNull() ?: 60) < 60) return NovaLimit("client_fps", "4K at 60 fps needs a faster screen")
            return null
        }

        fun customDelta(custom: NovaStreamPlan, recommended: NovaStreamPlan): String {
            val parts = listOfNotNull(custom.size.label.takeIf { custom.size != recommended.size },
                "${custom.fps} fps".takeIf { custom.fps != recommended.fps },
                NovaBitrateAdvice.text(custom.bitrateKbps, false).takeIf { custom.bitrateKbps != recommended.bitrateKbps },
                custom.codec.label.takeIf { custom.codec != recommended.codec })
            return ("Custom" + if (parts.isEmpty()) "" else " · " + parts.take(2).joinToString(", ") +
                if (parts.size > 2) " +${parts.size - 2}" else "").take(56)
        }
    }
}

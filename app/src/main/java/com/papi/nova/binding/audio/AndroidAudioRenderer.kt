package com.papi.nova.binding.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRouter
import android.media.audiofx.AudioEffect
import android.os.Build
import com.papi.nova.LimeLog
import com.papi.nova.nvstream.av.audio.AudioRenderer
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.ui.AudioHapticEngine
import kotlin.math.max

class AndroidAudioRenderer(
    private val context: Context,
    private val enableAudioFx: Boolean
) : AudioRenderer {
    private var track: AudioTrack? = null
    @Volatile
    private var trackStarted = false
    @Volatile
    private var reportedAudioRoute = false

    private fun createAudioTrack(
        channelConfig: Int,
        sampleRate: Int,
        bufferSize: Int,
        lowLatency: Boolean
    ): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && lowLatency) {
            attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val trackBuilder = AudioTrack.Builder()
                .setAudioFormat(format)
                .setAudioAttributes(attributesBuilder.build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                LimeLog.info("Nova: Android display audio context display_id=${audioContextDisplayId()} api=${Build.VERSION.SDK_INT}")
                trackBuilder.setContext(context)
            }
            trackBuilder.build()
        } else {
            AudioTrack(
                attributesBuilder.build(),
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        }
    }

    override fun setup(
        audioConfiguration: MoonBridge.AudioConfiguration,
        sampleRate: Int,
        samplesPerFrame: Int
    ): Int {
        val channelConfig = when (audioConfiguration.channelCount) {
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> 0x000018fc
            else -> {
                LimeLog.severe("Decoder returned unhandled channel count")
                return -1
            }
        }

        LimeLog.info("Audio channel config: " + String.format("0x%X", channelConfig))

        val bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2

        for (i in 0 until 4) {
            val lowLatency = when (i) {
                0, 1 -> true
                2, 3 -> false
                else -> error("Unreachable")
            }
            var bufferSize = when (i) {
                0, 2 -> bytesPerFrame * 2
                1, 3 -> {
                    val minSize = AudioTrack.getMinBufferSize(
                        sampleRate,
                        channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    max(minSize, bytesPerFrame * 2)
                }
                else -> error("Unreachable")
            }
            if (i == 1 || i == 3) {
                bufferSize = ((bufferSize + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame
            }

            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue
            }
            if (enableAudioFx && lowLatency) {
                continue
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency)
                trackStarted = false
                LimeLog.info("Audio track configuration: $bufferSize $lowLatency")
                break
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    track?.release()
                    track = null
                } catch (_: Exception) {
                }
            }
        }

        return if (track == null) -2 else 0
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        val audioTrack = track ?: return
        if (!trackStarted) {
            audioTrack.play()
            trackStarted = true
            logRoutedAudioDevice(audioTrack)
        }

        hapticEngine?.feedAudioShort(audioData, audioTrack.sampleRate, audioTrack.channelCount)

        if (MoonBridge.getPendingAudioDuration() < 40) {
            audioTrack.write(audioData, 0, audioData.size)
        } else {
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() + " ms")
        }
    }

    private fun audioContextDisplayId(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display.displayId
        } else {
            INVALID_DISPLAY_ID
        }
    }

    private fun logRoutedAudioDevice(audioTrack: AudioTrack) {
        if (reportedAudioRoute) return
        reportedAudioRoute = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val routedDevice = audioTrack.routedDevice
            if (routedDevice != null) {
                LimeLog.info(
                    "Nova: Android display audio route display_id=${audioContextDisplayId()} " +
                        "device_id=${routedDevice.id} type=${routedDevice.type}"
                )
            } else {
                LimeLog.info("Nova: Android display audio route display_id=${audioContextDisplayId()} device_id=none type=none")
            }
        }
        logAudioOutputInventory()
    }

    /**
     * Log every audio output the platform admits to, and every media route that names a display.
     *
     * The routed-device line above says where this track landed, which is not the same question as
     * where else it could have gone. On a dual-screen host both launch paths can report the same
     * builtin speaker while the user hears different screens, and from one line we cannot tell
     * whether that is one device the vendor re-points below the HAL or two devices we never asked
     * about. Only the enumeration separates those, and they have opposite fixes: a second
     * [AudioDeviceInfo] means [AudioTrack.setPreferredDevice] (API 23) can move the audio, while a
     * single device means nothing in the public API can and the display association at launch is
     * the whole story.
     *
     * [MediaRouter.RouteInfo.getPresentationDisplay] is logged beside it because it is the only
     * public API that maps an audio route onto a [android.view.Display], so it is the other way a
     * pre-34 host could offer display affinity.
     *
     * Diagnostics only -- wrapped because a vendor audio service that throws here must not be able
     * to take down the stream, and logged once per track for the same reason the route line is.
     */
    private fun logAudioOutputInventory() {
        try {
            val outputs = describeAudioOutputs()
            val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
            val routeCount = router?.routeCount ?: 0

            LimeLog.info(
                "Nova: Android display audio inventory display_id=${audioContextDisplayId()} " +
                    "outputs=${outputs.size} routes=$routeCount"
            )

            outputs.forEach { LimeLog.info(it) }

            val selectedLiveAudio = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
            for (index in 0 until routeCount) {
                val route = router?.getRouteAt(index) ?: continue
                val presentationDisplayId = route.presentationDisplay?.displayId
                val liveAudio = route.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_AUDIO != 0
                LimeLog.info(
                    "Nova: Android display audio presentation index=$index " +
                        "live_audio=$liveAudio " +
                        "presentation_display_id=${presentationDisplayId ?: "none"} " +
                        "selected=${route === selectedLiveAudio}"
                )
            }
        } catch (e: Exception) {
            LimeLog.warning("Nova: Android display audio inventory unavailable: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Describe every output device, or nothing at all when the platform predates the enumeration.
     *
     * Built as strings inside a single version gate rather than returned as [android.media.AudioDeviceInfo]
     * so that the whole API-23 surface stays behind one check. The address needs its own gate at a
     * higher level than the rest: getId and getType arrived in 23 but getAddress only in 28, and
     * reading the address unguarded against a minSdk of 21 is exactly the kind of thing that runs
     * everywhere it is tested and throws on the one old device nobody has.
     */
    private fun describeAudioOutputs(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
            val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                sanitizeDeviceAddress(device.address)
            } else {
                "unknown"
            }
            "Nova: Android display audio output device_id=${device.id} type=${device.type} " +
                "address=$address"
        }
    }

    /**
     * Reduce a device address to the shape a field report can carry.
     *
     * Vendor addresses are the discriminator worth having -- two builtin speakers are told apart by
     * theirs and by nothing else -- but they are vendor strings rather than a documented vocabulary,
     * so the report keeps the characters an identifier can be made of and drops the rest rather than
     * forwarding whatever a HAL happens to put there. Route and product names are omitted entirely:
     * those are user-visible strings and carry people's names.
     */
    private fun sanitizeDeviceAddress(address: String?): String {
        if (address.isNullOrEmpty()) return "none"
        val cleaned = address.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        return if (cleaned.isEmpty()) "none" else cleaned.take(MAX_DEVICE_ADDRESS_CHARS)
    }

    override fun start() {
        if (enableAudioFx) {
            val audioTrack = track ?: return
            val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioTrack.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME)
            context.sendBroadcast(intent)
        }
    }

    override fun stop() {
        if (enableAudioFx) {
            val audioTrack = track ?: return
            val intent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioTrack.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME)
            context.sendBroadcast(intent)
        }
    }

    override fun cleanup() {
        val audioTrack = track ?: return
        if (trackStarted) {
            audioTrack.pause()
            audioTrack.flush()
        }
        audioTrack.release()
    }

    companion object {
        private const val INVALID_DISPLAY_ID = -1
        private const val MAX_DEVICE_ADDRESS_CHARS = 32

        @Volatile
        @JvmField
        var hapticEngine: AudioHapticEngine? = null
    }
}

package com.papi.nova.binding.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
        }

        hapticEngine?.feedAudioShort(audioData, audioTrack.sampleRate, audioTrack.channelCount)

        if (MoonBridge.getPendingAudioDuration() < 40) {
            audioTrack.write(audioData, 0, audioData.size)
        } else {
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() + " ms")
        }
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
        @Volatile
        @JvmField
        var hapticEngine: AudioHapticEngine? = null
    }
}

package com.papi.nova.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.preference.PreferenceManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Audio-driven haptic feedback engine.
 * Converts low-frequency audio energy (bass) to vibration feedback.
 *
 * Inspired by Moonlight V+'s layered haptic system.
 *
 * Modes:
 *   - off: disabled
 *   - subtle: light vibration on heavy bass hits
 *   - strong: aggressive vibration tracking bass energy
 */
class AudioHapticEngine(private val context: Context) {

    private var vibrator: Vibrator? = null
    private var mode = "off"
    private var lastVibrationTime = 0L
    private val MIN_VIBRATION_INTERVAL_MS = 33L  // max 30 events/sec

    // Low-pass filter state (simple first-order IIR)
    private var filteredEnergy = 0.0

    fun start() {
        mode = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("nova_audio_haptics", "off") ?: "off"

        if (mode == "off") return

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Feed raw PCM audio samples (float, -1.0 to 1.0).
     * Extracts low-frequency energy and converts to vibration.
     *
     * Call this from the audio render callback with each buffer of samples.
     * @param samples PCM float array
     * @param sampleRate audio sample rate (e.g., 48000)
     * @param channels number of audio channels
     */
    fun feedAudio(samples: FloatArray, sampleRate: Int, channels: Int) {
        if (mode == "off" || vibrator == null) return

        // Compute RMS energy of low frequencies (simple approach: average absolute value
        // of every Nth sample to approximate bass energy, since bass = slow oscillations)
        // For 48kHz stereo, sampling every 240th sample gives us ~200Hz content
        val step = (sampleRate / 200).coerceAtLeast(1) * channels
        var sumSq = 0.0
        var count = 0
        var i = 0
        while (i < samples.size) {
            val s = samples[i].toDouble()
            sumSq += s * s
            count++
            i += step
        }
        if (count == 0) return

        val rms = sqrt(sumSq / count)

        // IIR low-pass filter to smooth energy (avoid jitter)
        val alpha = 0.3
        filteredEnergy = alpha * rms + (1.0 - alpha) * filteredEnergy

        // Map energy to vibration amplitude
        val threshold = if (mode == "subtle") 0.15 else 0.08
        if (filteredEnergy < threshold) return

        // Throttle vibration rate
        val now = System.currentTimeMillis()
        if (now - lastVibrationTime < MIN_VIBRATION_INTERVAL_MS) return
        lastVibrationTime = now

        // Scale: 0.0-1.0 energy → 1-255 amplitude
        val scale = if (mode == "subtle") 0.5 else 1.0
        val amplitude = ((filteredEnergy - threshold) / (1.0 - threshold) * 255.0 * scale)
            .toInt().coerceIn(1, 255)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(20, amplitude))
        }
    }

    /**
     * Feed raw PCM audio as short samples (Moonlight's native format).
     */
    fun feedAudioShort(samples: ShortArray, sampleRate: Int, channels: Int) {
        if (mode == "off" || vibrator == null) return

        val step = (sampleRate / 200).coerceAtLeast(1) * channels
        var sumSq = 0.0
        var count = 0
        var i = 0
        while (i < samples.size) {
            val s = samples[i].toDouble() / 32768.0
            sumSq += s * s
            count++
            i += step
        }
        if (count == 0) return

        val rms = sqrt(sumSq / count)
        val alpha = 0.3
        filteredEnergy = alpha * rms + (1.0 - alpha) * filteredEnergy

        val threshold = if (mode == "subtle") 0.15 else 0.08
        if (filteredEnergy < threshold) return

        val now = System.currentTimeMillis()
        if (now - lastVibrationTime < MIN_VIBRATION_INTERVAL_MS) return
        lastVibrationTime = now

        val scale = if (mode == "subtle") 0.5 else 1.0
        val amplitude = ((filteredEnergy - threshold) / (1.0 - threshold) * 255.0 * scale)
            .toInt().coerceIn(1, 255)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(20, amplitude))
        }
    }

    fun stop() {
        vibrator?.cancel()
        vibrator = null
        filteredEnergy = 0.0
    }
}

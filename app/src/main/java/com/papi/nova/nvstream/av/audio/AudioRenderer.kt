package com.papi.nova.nvstream.av.audio

import com.papi.nova.nvstream.jni.MoonBridge

interface AudioRenderer {
    fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int): Int

    fun start()

    fun stop()

    fun playDecodedAudio(audioData: ShortArray)

    fun cleanup()
}

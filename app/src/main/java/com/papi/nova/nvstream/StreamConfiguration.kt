package com.papi.nova.nvstream

import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.jni.MoonBridge

class StreamConfiguration private constructor() {
    private var app: NvApp? = NvApp("Steam")
    private var width = 1280
    private var height = 720
    private var refreshRate = 60f
    private var launchRefreshRate = 60f
    private var virtualDisplay = false
    private var displayModeExplicit = false
    private var resolutionScaleFactor = 100
    private var clientRefreshRateX100 = 0
    private var bitrate = 10000
    private var sops = true
    private var enableAdaptiveResolution = false
    private var playLocalAudio = false
    private var maxPacketSize = 1024
    private var remote = STREAM_CFG_AUTO
    private var audioConfiguration: MoonBridge.AudioConfiguration? = MoonBridge.AUDIO_CONFIGURATION_STEREO
    private var supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264
    private var attachedGamepadMask = 0
    private var colorRange = 0
    private var colorSpace = 0
    private var persistGamepadsAfterDisconnect = false
    private var enableUltraLowLatency = false
    private var forceFreshLaunch = false

    class Builder {
        private val config = StreamConfiguration()

        fun setApp(app: NvApp?): Builder {
            config.app = app
            return this
        }

        fun setRemoteConfiguration(remote: Int): Builder {
            config.remote = remote
            return this
        }

        fun setResolution(width: Int, height: Int): Builder {
            config.width = width
            config.height = height
            return this
        }

        fun setRefreshRate(refreshRate: Float): Builder {
            config.refreshRate = refreshRate
            return this
        }

        fun setLaunchRefreshRate(refreshRate: Float): Builder {
            config.launchRefreshRate = refreshRate
            return this
        }

        fun setVirtualDisplay(enable: Boolean): Builder {
            config.virtualDisplay = enable
            return this
        }

        fun setDisplayModeExplicit(explicit: Boolean): Builder {
            config.displayModeExplicit = explicit
            return this
        }

        fun setResolutionScaleFactor(scaleFactor: Int): Builder {
            config.resolutionScaleFactor = scaleFactor
            return this
        }

        fun setBitrate(bitrate: Int): Builder {
            config.bitrate = bitrate
            return this
        }

        fun setEnableSops(enable: Boolean): Builder {
            config.sops = enable
            return this
        }

        fun enableAdaptiveResolution(enable: Boolean): Builder {
            config.enableAdaptiveResolution = enable
            return this
        }

        fun enableLocalAudioPlayback(enable: Boolean): Builder {
            config.playLocalAudio = enable
            return this
        }

        fun setMaxPacketSize(maxPacketSize: Int): Builder {
            config.maxPacketSize = maxPacketSize
            return this
        }

        fun setAttachedGamepadMask(attachedGamepadMask: Int): Builder {
            config.attachedGamepadMask = attachedGamepadMask
            return this
        }

        fun setAttachedGamepadMaskByCount(gamepadCount: Int): Builder {
            config.attachedGamepadMask = 0
            for (i in 0 until 4) {
                if (gamepadCount > i) {
                    config.attachedGamepadMask = config.attachedGamepadMask or (1 shl i)
                }
            }
            return this
        }

        fun setPersistGamepadsAfterDisconnect(value: Boolean): Builder {
            config.persistGamepadsAfterDisconnect = value
            return this
        }

        fun setClientRefreshRateX100(refreshRateX100: Int): Builder {
            config.clientRefreshRateX100 = refreshRateX100
            return this
        }

        fun setAudioConfiguration(audioConfig: MoonBridge.AudioConfiguration?): Builder {
            config.audioConfiguration = audioConfig
            return this
        }

        fun setSupportedVideoFormats(supportedVideoFormats: Int): Builder {
            config.supportedVideoFormats = supportedVideoFormats
            return this
        }

        fun setColorRange(colorRange: Int): Builder {
            config.colorRange = colorRange
            return this
        }

        fun setColorSpace(colorSpace: Int): Builder {
            config.colorSpace = colorSpace
            return this
        }

        fun setEnableUltraLowLatency(enable: Boolean): Builder {
            config.enableUltraLowLatency = enable
            return this
        }

        fun setForceFreshLaunch(forceFreshLaunch: Boolean): Builder {
            config.forceFreshLaunch = forceFreshLaunch
            return this
        }

        fun build(): StreamConfiguration = config
    }

    fun getWidth(): Int = width

    fun getHeight(): Int = height

    fun getRefreshRate(): Int {
        return if (refreshRate == refreshRate.toInt().toFloat()) {
            refreshRate.toInt()
        } else {
            (refreshRate * 1000).toInt()
        }
    }

    fun getLaunchRefreshRate(): Int {
        return if (launchRefreshRate == launchRefreshRate.toInt().toFloat()) {
            launchRefreshRate.toInt()
        } else {
            (launchRefreshRate * 1000).toInt()
        }
    }

    fun getVirtualDisplay(): Boolean = virtualDisplay

    fun getDisplayModeExplicit(): Boolean = displayModeExplicit

    fun getResolutionScaleFactor(): Int = resolutionScaleFactor

    fun getBitrate(): Int = bitrate

    fun getMaxPacketSize(): Int = maxPacketSize

    fun getApp(): NvApp? = app

    fun getSops(): Boolean = sops

    fun getAdaptiveResolutionEnabled(): Boolean = enableAdaptiveResolution

    fun getPlayLocalAudio(): Boolean = playLocalAudio

    fun getRemote(): Int = remote

    fun getAudioConfiguration(): MoonBridge.AudioConfiguration? = audioConfiguration

    fun getSupportedVideoFormats(): Int = supportedVideoFormats

    fun getAttachedGamepadMask(): Int = attachedGamepadMask

    fun getPersistGamepadsAfterDisconnect(): Boolean = persistGamepadsAfterDisconnect

    fun getClientRefreshRateX100(): Int = clientRefreshRateX100

    fun getColorRange(): Int = colorRange

    fun getColorSpace(): Int = colorSpace

    fun getEnableUltraLowLatency(): Boolean = enableUltraLowLatency

    fun getForceFreshLaunch(): Boolean = forceFreshLaunch

    companion object {
        const val INVALID_APP_ID = 0

        const val STREAM_CFG_LOCAL = 0
        const val STREAM_CFG_REMOTE = 1
        const val STREAM_CFG_AUTO = 2
    }
}

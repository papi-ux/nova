package com.papi.nova.nvstream

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.os.Build
import com.papi.nova.LimeLog
import com.papi.nova.nvstream.av.audio.AudioRenderer
import com.papi.nova.nvstream.av.video.VideoDecoderRenderer
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.HostHttpResponseException
import com.papi.nova.nvstream.http.LimelightCryptoProvider
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.nvstream.input.MouseButtonPacket
import com.papi.nova.nvstream.jni.MoonBridge
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Objects
import java.util.concurrent.Semaphore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.math.min

class NvConnection(
    private val appContext: Context,
    host: ComputerDetails.AddressTuple,
    httpsPort: Int,
    private val uniqueId: String,
    config: StreamConfiguration,
    private val cryptoProvider: LimelightCryptoProvider,
    serverCert: X509Certificate?,
) {
    private val context: ConnectionContext = ConnectionContext()
    private val isMonkey: Boolean

    init {
        context.serverAddress = host
        context.httpsPort = httpsPort
        context.streamConfig = config
        context.serverCert = serverCert
        context.riKey = generateRiAesKey()
        context.riKeyId = generateRiKeyId()
        isMonkey = ActivityManager.isUserAMonkey()
    }

    fun stop() {
        MoonBridge.interruptConnection()

        synchronized(MoonBridge::class.java) {
            MoonBridge.stopConnection()
            MoonBridge.cleanupBridge()
        }

        connectionAllowed.release()
    }

    @Throws(IOException::class)
    private fun resolveServerAddress(): InetAddress {
        val serverAddress = context.serverAddress!!
        val addrs = InetAddress.getAllByName(serverAddress.address)
        for (addr in addrs) {
            try {
                Socket().use { socket ->
                    socket.setSoLinger(true, 0)
                    socket.connect(InetSocketAddress(addr, serverAddress.port), 1000)
                    return addr
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        if (addrs.isNotEmpty()) {
            return addrs[0]
        }
        throw IOException("No addresses found for " + context.serverAddress)
    }

    @Suppress("DEPRECATION")
    private fun detectServerConnectionType(): Int {
        val connMgr = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connMgr.activeNetwork
            if (activeNetwork != null) {
                val netCaps = connMgr.getNetworkCapabilities(activeNetwork)
                if (netCaps != null) {
                    if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    ) {
                        return StreamConfiguration.STREAM_CFG_REMOTE
                    } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return StreamConfiguration.STREAM_CFG_REMOTE
                    }
                }

                val linkProperties = connMgr.getLinkProperties(activeNetwork)
                if (linkProperties != null) {
                    val serverAddress = try {
                        resolveServerAddress()
                    } catch (e: IOException) {
                        e.printStackTrace()
                        return StreamConfiguration.STREAM_CFG_AUTO
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val nat64Prefix: IpPrefix? = linkProperties.nat64Prefix
                        if (nat64Prefix != null && nat64Prefix.contains(serverAddress)) {
                            return StreamConfiguration.STREAM_CFG_REMOTE
                        }
                    }

                    for (route in linkProperties.routes) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            route.type != android.net.RouteInfo.RTN_UNICAST
                        ) {
                            continue
                        }

                        if (route.matches(serverAddress)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                if (!route.hasGateway()) {
                                    return StreamConfiguration.STREAM_CFG_LOCAL
                                }
                            } else {
                                val gateway = route.gateway
                                if (gateway == null || gateway.isAnyLocalAddress) {
                                    return StreamConfiguration.STREAM_CFG_LOCAL
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val activeNetworkInfo = connMgr.activeNetworkInfo
            if (activeNetworkInfo != null) {
                when (activeNetworkInfo.type) {
                    ConnectivityManager.TYPE_VPN,
                    ConnectivityManager.TYPE_MOBILE,
                    ConnectivityManager.TYPE_MOBILE_DUN,
                    ConnectivityManager.TYPE_MOBILE_HIPRI,
                    ConnectivityManager.TYPE_MOBILE_MMS,
                    ConnectivityManager.TYPE_MOBILE_SUPL,
                    ConnectivityManager.TYPE_WIMAX,
                    -> return StreamConfiguration.STREAM_CFG_REMOTE
                }
            }
        }

        return StreamConfiguration.STREAM_CFG_AUTO
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun startApp(): Boolean {
        val streamConfig = context.streamConfig!!
        val listener = context.connListener!!
        val h = NvHTTP(context.serverAddress!!, context.httpsPort, uniqueId, context.serverCert, cryptoProvider)

        val serverInfo = h.getServerInfo(true)

        context.serverAppVersion = h.getServerVersion(serverInfo)
        if (context.serverAppVersion == null) {
            listener.displayMessage("Server version malformed")
            return false
        }

        val details = h.getComputerDetails(serverInfo)
        context.isNvidiaServerSoftware = details.nvidiaServer
        context.serverMaxLaunchRefreshRate = details.serverMaxLaunchRefreshRate
        context.currentGameOwnerName = details.currentGameOwnerName

        context.serverGfeVersion = h.getGfeVersion(serverInfo)

        if (h.getPairState(serverInfo) != PairingManager.PairState.PAIRED) {
            listener.displayMessage("Device not paired with computer")
            return false
        }

        context.serverCodecModeSupport = h.getServerCodecModeSupport(serverInfo).toInt()
        context.sessionToken = h.getCurrentGameSessionToken(serverInfo)
        context.currentGameOwnedByClient = h.getCurrentGameOwned(serverInfo)
        context.currentGameOwnerName = h.getCurrentGameOwner(serverInfo)

        context.negotiatedHdr = (streamConfig.getSupportedVideoFormats() and MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0
        if ((context.serverCodecModeSupport and 0x20200) == 0 && context.negotiatedHdr) {
            listener.displayTransientMessage("Your PC GPU does not support streaming HDR. The stream will be SDR.")
            context.negotiatedHdr = false
        }

        if ((streamConfig.getWidth() > 4096 || streamConfig.getHeight() > 4096) &&
            (h.getServerCodecModeSupport(serverInfo).toInt() and 0x200) == 0 &&
            context.isNvidiaServerSoftware
        ) {
            listener.displayMessage("Your host PC does not support streaming at resolutions above 4K.")
            return false
        } else if ((streamConfig.getWidth() > 4096 || streamConfig.getHeight() > 4096) &&
            (streamConfig.getSupportedVideoFormats() and MoonBridge.VIDEO_FORMAT_MASK_H264.inv()) == 0
        ) {
            listener.displayMessage("Your streaming device must support HEVC or AV1 to stream at resolutions above 4K.")
            return false
        } else if (streamConfig.getHeight() >= 2160 && !h.supports4K(serverInfo)) {
            listener.displayTransientMessage("You must update GeForce Experience to stream in 4K. The stream will be 1080p.")
            context.negotiatedWidth = 1920
            context.negotiatedHeight = 1080
        } else {
            context.negotiatedWidth = streamConfig.getWidth()
            context.negotiatedHeight = streamConfig.getHeight()
        }

        if (streamConfig.getRemote() == StreamConfiguration.STREAM_CFG_AUTO) {
            context.negotiatedRemoteStreaming = detectServerConnectionType()
            context.negotiatedPacketSize = if (context.negotiatedRemoteStreaming == StreamConfiguration.STREAM_CFG_REMOTE) {
                1024
            } else {
                streamConfig.getMaxPacketSize()
            }
        } else {
            context.negotiatedRemoteStreaming = streamConfig.getRemote()
            context.negotiatedPacketSize = streamConfig.getMaxPacketSize()
        }

        var app = streamConfig.getApp()
        if (!streamConfig.getApp()!!.isInitialized()) {
            LimeLog.info("Using deprecated app lookup method - Please specify an app ID in your StreamConfiguration instead")
            app = h.getAppByName(streamConfig.getApp()!!.appName)
            if (app == null) {
                listener.displayMessage("The app " + streamConfig.getApp()!!.appName + " is not in GFE app list")
                return false
            }
        }

        val currentGameUuid = h.getCurrentGameUUID(serverInfo)
        if (h.getCurrentGame(serverInfo) != 0 || !currentGameUuid.isNullOrEmpty()) {
            try {
                if (h.getCurrentGame(serverInfo) == app!!.appId || Objects.equals(currentGameUuid, app.appUUID)) {
                    if (java.lang.Boolean.FALSE == context.currentGameOwnedByClient) {
                        if (!context.watchOnlyRequested) {
                            listener.displayMessage(
                                "This session wasn't started by this device," +
                                    " so it cannot be resumed. End streaming on the original " +
                                    "device or the PC itself and try again.",
                            )
                            return false
                        }
                    } else if (context.watchOnlyRequested) {
                        listener.displayMessage("This stream is already owned by this device. Resume it instead of watching.")
                        return false
                    }
                    if (streamConfig.getForceFreshLaunch()) {
                        LimeLog.info("Nova: Auto Safe requested fresh launch; replacing paused session instead of resuming")
                        return quitAndLaunch(h, context)
                    }
                    if (!h.launchApp(context, "resume", app.appUUID, app.appId, context.negotiatedHdr, context.watchOnlyRequested)) {
                        listener.displayMessage(
                            if (context.watchOnlyRequested) {
                                "Failed to join active stream"
                            } else {
                                "Failed to resume existing session"
                            },
                        )
                        return false
                    }
                    if (context.watchOnlyRequested) {
                        val ownerName = context.currentGameOwnerName
                        listener.displayTransientMessage(
                            if (!ownerName.isNullOrEmpty()) {
                                "Watching $ownerName's stream."
                            } else {
                                "Watching active stream."
                            },
                        )
                    }
                } else if (Objects.equals(NvApp.REMOTE_INPUT_UUID, app!!.appUUID)) {
                    return launchNotRunningApp(h, context)
                } else {
                    if (context.watchOnlyRequested) {
                        listener.displayMessage("Watch mode can only join the active stream.")
                        return false
                    }
                    return quitAndLaunch(h, context)
                }
            } catch (e: HostHttpResponseException) {
                if (e.getErrorCode() == 470) {
                    listener.displayMessage(
                        "This session wasn't started by this device," +
                            " so it cannot be resumed. End streaming on the original " +
                            "device or the PC itself and try again. (Error code: " + e.getErrorCode() + ")",
                    )
                    return false
                } else if (e.getErrorCode() == 525) {
                    listener.displayMessage("The application is minimized. Resume it on the PC manually or quit the session and start streaming again.")
                    return false
                } else if (e.getErrorCode() == 412 && context.watchOnlyRequested) {
                    val errorMessage = e.getErrorMessage()
                    listener.displayMessage(
                        if (!errorMessage.isNullOrEmpty()) {
                            errorMessage
                        } else {
                            "Watch mode must match the active stream profile."
                        },
                    )
                    return false
                } else if (e.getErrorCode() == 409 && context.watchOnlyRequested) {
                    listener.displayMessage("No active stream is available to watch.")
                    return false
                } else {
                    throw e
                }
            }

            LimeLog.info("Resumed existing game session")
            return true
        } else {
            if (context.watchOnlyRequested) {
                listener.displayMessage("No active stream is available to watch.")
                return false
            }
            return launchNotRunningApp(h, context)
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    protected fun quitAndLaunch(h: NvHTTP, context: ConnectionContext): Boolean {
        val listener = context.connListener!!
        if (context.watchOnlyRequested) {
            listener.displayMessage("Watch mode can't quit or replace the active stream.")
            return false
        }

        if (java.lang.Boolean.FALSE == context.currentGameOwnedByClient) {
            listener.displayMessage(
                "This session wasn't started by this device," +
                    " so it cannot be quit. End streaming on the original " +
                    "device or the PC itself.",
            )
            return false
        }

        try {
            if (!h.quitApp(context.sessionToken)) {
                listener.displayMessage("Failed to quit previous session! You must quit it manually")
                return false
            }
        } catch (e: HostHttpResponseException) {
            if (e.getErrorCode() == 470 || e.getErrorCode() == 599) {
                listener.displayMessage(
                    "This session wasn't started by this device," +
                        " so it cannot be quit. End streaming on the original " +
                        "device or the PC itself. (Error code: " + e.getErrorCode() + ")",
                )
                return false
            } else {
                throw e
            }
        }

        return launchNotRunningApp(h, context)
    }

    fun getSessionToken(): String? {
        return context.sessionToken
    }

    @Throws(IOException::class, XmlPullParserException::class)
    private fun launchNotRunningApp(h: NvHTTP, context: ConnectionContext): Boolean {
        val streamConfig = context.streamConfig!!
        val listener = context.connListener!!
        if (context.watchOnlyRequested) {
            listener.displayMessage("No active stream is available to watch.")
            return false
        }

        val requestedLaunchRefreshRate = streamConfig.getLaunchRefreshRate().toFloat()
        context.negotiatedLaunchRefreshRate = negotiateLaunchRefreshRate(
            requestedLaunchRefreshRate,
            context.serverMaxLaunchRefreshRate,
        )
        if (context.serverMaxLaunchRefreshRate > 0 &&
            context.negotiatedLaunchRefreshRate < requestedLaunchRefreshRate
        ) {
            listener.displayTransientMessage(
                "This host currently advertises up to " + context.serverMaxLaunchRefreshRate +
                    " FPS. The stream will launch at " + context.serverMaxLaunchRefreshRate + " FPS.",
            )
        }

        val app = streamConfig.getApp()!!
        if (!h.launchApp(context, "launch", app.appUUID, app.appId, context.negotiatedHdr, false)) {
            listener.displayMessage("Failed to launch application")
            return false
        }

        LimeLog.info("Launched new game session")

        return true
    }

    fun start(
        audioRenderer: AudioRenderer,
        videoDecoderRenderer: VideoDecoderRenderer,
        connectionListener: NvConnectionListener,
    ) {
        Thread {
            context.connListener = connectionListener
            context.videoCapabilities = videoDecoderRenderer.getCapabilities()

            val streamConfig = context.streamConfig!!
            val appName = streamConfig.getApp()!!.appName

            connectionListener.stageStarting(appName)

            var tryCount = 0

            do {
                var retry = false
                try {
                    if (!startApp()) {
                        retry = connectionListener.stageFailed(appName, 0, 0)
                        if (!retry) {
                            return@Thread
                        }
                    }
                    connectionListener.stageComplete(appName)
                } catch (e: HostHttpResponseException) {
                    e.printStackTrace()
                    connectionListener.displayMessage(e.message ?: e.toString())
                    retry = connectionListener.stageFailed(appName, 0, e.getErrorCode())
                    if (!retry) {
                        return@Thread
                    }
                } catch (e: XmlPullParserException) {
                    e.printStackTrace()
                    connectionListener.displayMessage(e.message ?: e.toString())
                    retry = connectionListener.stageFailed(
                        appName,
                        MoonBridge.ML_PORT_FLAG_TCP_47984 or MoonBridge.ML_PORT_FLAG_TCP_47989,
                        if (tryCount < 2) 0 else -408,
                    )
                    if (!retry) {
                        return@Thread
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    connectionListener.displayMessage(e.message ?: e.toString())
                    retry = connectionListener.stageFailed(
                        appName,
                        MoonBridge.ML_PORT_FLAG_TCP_47984 or MoonBridge.ML_PORT_FLAG_TCP_47989,
                        if (tryCount < 2) 0 else -408,
                    )
                    if (!retry) {
                        return@Thread
                    }
                }

                if (!retry) break
                tryCount += 1

                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    throw RuntimeException(e)
                }
            } while (tryCount < 5)

            if (tryCount >= 5) {
                connectionListener.stageFailed(appName, 0, -408)
                return@Thread
            }

            val ib = ByteBuffer.allocate(16)
            ib.putInt(context.riKeyId)

            try {
                connectionAllowed.acquire()
            } catch (e: InterruptedException) {
                connectionListener.displayMessage(e.message ?: e.toString())
                connectionListener.stageFailed(appName, 0, 0)
                return@Thread
            }

            synchronized(MoonBridge::class.java) {
                MoonBridge.setupBridge(videoDecoderRenderer, audioRenderer, connectionListener)
                val ret = MoonBridge.startConnection(
                    context.serverAddress!!.address,
                    context.serverAppVersion,
                    context.serverGfeVersion,
                    context.rtspSessionUrl,
                    context.serverCodecModeSupport,
                    context.negotiatedWidth,
                    context.negotiatedHeight,
                    streamConfig.getRefreshRate(),
                    streamConfig.getBitrate(),
                    context.negotiatedPacketSize,
                    context.negotiatedRemoteStreaming,
                    streamConfig.getAudioConfiguration()!!.toInt(),
                    streamConfig.getSupportedVideoFormats(),
                    streamConfig.getClientRefreshRateX100(),
                    context.riKey!!.encoded,
                    ib.array(),
                    context.videoCapabilities,
                    streamConfig.getColorSpace(),
                    streamConfig.getColorRange(),
                )
                if (ret != 0) {
                    connectionAllowed.release()
                    return@Thread
                }
            }
        }.start()
    }

    fun setWatchOnlyRequested(watchOnlyRequested: Boolean) {
        context.watchOnlyRequested = watchOnlyRequested
    }

    fun sendExecServerCmd(cmdId: Int) {
        if (!isMonkey) {
            MoonBridge.sendExecServerCmd(cmdId)
        }
    }

    fun sendMouseMove(deltaX: Short, deltaY: Short) {
        if (!isMonkey) {
            MoonBridge.sendMouseMove(deltaX, deltaY)
        }
    }

    fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short) {
        if (!isMonkey) {
            MoonBridge.sendMousePosition(x, y, referenceWidth, referenceHeight)
        }
    }

    fun sendMouseMoveAsMousePosition(deltaX: Short, deltaY: Short, referenceWidth: Short, referenceHeight: Short) {
        if (!isMonkey) {
            MoonBridge.sendMouseMoveAsMousePosition(deltaX, deltaY, referenceWidth, referenceHeight)
        }
    }

    fun sendMouseButtonDown(mouseButton: Byte) {
        if (!isMonkey) {
            MoonBridge.sendMouseButton(MouseButtonPacket.PRESS_EVENT, mouseButton)
        }
    }

    fun sendMouseButtonUp(mouseButton: Byte) {
        if (!isMonkey) {
            MoonBridge.sendMouseButton(MouseButtonPacket.RELEASE_EVENT, mouseButton)
        }
    }

    fun sendControllerInput(
        controllerNumber: Short,
        activeGamepadMask: Short,
        buttonFlags: Int,
        leftTrigger: Byte,
        rightTrigger: Byte,
        leftStickX: Short,
        leftStickY: Short,
        rightStickX: Short,
        rightStickY: Short,
    ) {
        if (!isMonkey) {
            MoonBridge.sendMultiControllerInput(
                controllerNumber,
                activeGamepadMask,
                buttonFlags,
                leftTrigger,
                rightTrigger,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY,
            )
        }
    }

    fun sendKeyboardInput(keyMap: Short, keyDirection: Byte, modifier: Byte, flags: Byte) {
        if (!isMonkey) {
            MoonBridge.sendKeyboardInput(keyMap, keyDirection, modifier, flags)
        }
    }

    fun sendMouseScroll(scrollClicks: Byte) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResScroll((scrollClicks * 120).toShort())
        }
    }

    fun sendMouseHScroll(scrollClicks: Byte) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResHScroll((scrollClicks * 120).toShort())
        }
    }

    fun sendMouseHighResScroll(scrollAmount: Short) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResScroll(scrollAmount)
        }
    }

    fun sendMouseHighResHScroll(scrollAmount: Short) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResHScroll(scrollAmount)
        }
    }

    fun sendTouchEvent(
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float,
        contactAreaMinor: Float,
        rotation: Short,
    ): Int {
        return if (!isMonkey) {
            MoonBridge.sendTouchEvent(
                eventType,
                pointerId,
                x,
                y,
                pressureOrDistance,
                contactAreaMajor,
                contactAreaMinor,
                rotation,
            )
        } else {
            MoonBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendPenEvent(
        eventType: Byte,
        toolType: Byte,
        penButtons: Byte,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float,
        contactAreaMinor: Float,
        rotation: Short,
        tilt: Byte,
    ): Int {
        return if (!isMonkey) {
            MoonBridge.sendPenEvent(
                eventType,
                toolType,
                penButtons,
                x,
                y,
                pressureOrDistance,
                contactAreaMajor,
                contactAreaMinor,
                rotation,
                tilt,
            )
        } else {
            MoonBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendControllerArrivalEvent(
        controllerNumber: Byte,
        activeGamepadMask: Short,
        type: Byte,
        supportedButtonFlags: Int,
        capabilities: Short,
    ): Int {
        return MoonBridge.sendControllerArrivalEvent(
            controllerNumber,
            activeGamepadMask,
            type,
            supportedButtonFlags,
            capabilities,
        )
    }

    fun sendControllerTouchEvent(
        controllerNumber: Byte,
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
    ): Int {
        return if (!isMonkey) {
            MoonBridge.sendControllerTouchEvent(controllerNumber, eventType, pointerId, x, y, pressure)
        } else {
            MoonBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendControllerMotionEvent(
        controllerNumber: Byte,
        motionType: Byte,
        x: Float,
        y: Float,
        z: Float,
    ): Int {
        return if (!isMonkey) {
            MoonBridge.sendControllerMotionEvent(controllerNumber, motionType, x, y, z)
        } else {
            MoonBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendControllerBatteryEvent(controllerNumber: Byte, batteryState: Byte, batteryPercentage: Byte) {
        MoonBridge.sendControllerBatteryEvent(controllerNumber, batteryState, batteryPercentage)
    }

    fun sendUtf8Text(text: String?) {
        if (!isMonkey) {
            MoonBridge.sendUtf8Text(text)
        }
    }

    companion object {
        private var connectionAllowed = Semaphore(1)

        private fun generateRiAesKey(): SecretKey {
            try {
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(128)
                return keyGen.generateKey()
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        private fun generateRiKeyId(): Int {
            return SecureRandom().nextInt()
        }

        @JvmStatic
        fun negotiateLaunchRefreshRate(requestedLaunchRefreshRate: Float, serverMaxLaunchRefreshRate: Int): Float {
            if (requestedLaunchRefreshRate <= 0 || serverMaxLaunchRefreshRate <= 0) {
                return requestedLaunchRefreshRate
            }

            return min(requestedLaunchRefreshRate, serverMaxLaunchRefreshRate.toFloat())
        }

        @JvmStatic
        fun findExternalAddressForMdns(stunHostname: String?, stunPort: Int): String? {
            return MoonBridge.findExternalAddressIP4(stunHostname, stunPort)
        }
    }
}

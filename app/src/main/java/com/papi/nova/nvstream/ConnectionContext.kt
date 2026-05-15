package com.papi.nova.nvstream

import com.papi.nova.nvstream.http.ComputerDetails
import java.security.cert.X509Certificate
import javax.crypto.SecretKey

class ConnectionContext {
    @JvmField var serverAddress: ComputerDetails.AddressTuple? = null
    @JvmField var httpsPort = 0
    @JvmField var isNvidiaServerSoftware = false
    @JvmField var serverCert: X509Certificate? = null
    @JvmField var streamConfig: StreamConfiguration? = null
    @JvmField var connListener: NvConnectionListener? = null
    @JvmField var riKey: SecretKey? = null
    @JvmField var riKeyId = 0

    @JvmField var serverAppVersion: String? = null
    @JvmField var serverGfeVersion: String? = null
    @JvmField var serverCodecModeSupport = 0
    @JvmField var serverMaxLaunchRefreshRate = 0

    @JvmField var rtspSessionUrl: String? = null
    @JvmField var sessionToken: String? = null
    @JvmField var currentGameOwnedByClient: Boolean? = null
    @JvmField var currentGameOwnerName: String? = null
    @JvmField var watchOnlyRequested = false

    @JvmField var negotiatedWidth = 0
    @JvmField var negotiatedHeight = 0
    @JvmField var negotiatedHdr = false
    @JvmField var negotiatedLaunchRefreshRate = 0f

    @JvmField var negotiatedRemoteStreaming = 0
    @JvmField var negotiatedPacketSize = 0
    @JvmField var videoCapabilities = 0
}

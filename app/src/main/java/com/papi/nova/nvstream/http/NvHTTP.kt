package com.papi.nova.nvstream.http

import com.papi.nova.BuildConfig
import com.papi.nova.LimeLog
import com.papi.nova.nvstream.ConnectionContext
import com.papi.nova.nvstream.jni.MoonBridge
import com.papi.nova.utils.DeviceUtils
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.URLEncoder
import java.security.KeyManagementException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.LinkedList
import java.util.Stack
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

class NvHTTP @Throws(IOException::class) constructor(
    address: ComputerDetails.AddressTuple,
    private var httpsPort: Int,
    private val uniqueId: String,
    private var serverCert: X509Certificate?,
    cryptoProvider: LimelightCryptoProvider,
) {
    private val deviceName: String = DeviceUtils.getModel()
    private val pm: PairingManager
    private val baseUrlHttp: HttpUrl

    private lateinit var httpClientLongConnectTimeout: OkHttpClient
    private lateinit var httpClientLongConnectNoReadTimeout: OkHttpClient
    private lateinit var httpClientShortConnectTimeout: OkHttpClient

    private lateinit var defaultTrustManager: X509TrustManager
    private lateinit var trustManager: X509TrustManager
    private lateinit var keyManager: X509KeyManager

    init {
        initializeHttpState(cryptoProvider)

        try {
            var addressString = validateHost(address.address)
            if (addressString.contains(":") && addressString.contains(".")) {
                val addr = InetAddress.getByName(addressString)
                if (addr is Inet4Address) {
                    addressString = addr.hostAddress ?: addressString
                }
            }

            baseUrlHttp = HttpUrl.Builder()
                .scheme("http")
                .host(addressString)
                .port(address.port)
                .build()
        } catch (e: IllegalArgumentException) {
            throw IOException(e)
        }

        pm = PairingManager(this, cryptoProvider)
    }

    internal fun setServerCert(serverCert: X509Certificate?) {
        this.serverCert = serverCert
    }

    private fun isExpectedBaseUrl(baseUrl: HttpUrl?): Boolean {
        if (baseUrl == null) {
            return false
        }

        if (baseUrl.host != baseUrlHttp.host) {
            return false
        }

        if (baseUrl.scheme == "http") {
            return baseUrl.port == baseUrlHttp.port
        }

        if (baseUrl.scheme == "https") {
            return baseUrl.port == httpsPort
        }

        return false
    }

    private fun initializeHttpState(cryptoProvider: LimelightCryptoProvider) {
        keyManager = object : X509KeyManager {
            override fun chooseClientAlias(keyTypes: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String {
                return "Limelight-RSA"
            }

            override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? {
                return null
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate> {
                return arrayOf(cryptoProvider.clientCertificate)
            }

            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? {
                return null
            }

            override fun getPrivateKey(alias: String?): PrivateKey {
                return cryptoProvider.clientPrivateKey
            }

            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? {
                return null
            }
        }

        defaultTrustManager = getDefaultTrustManager()
        trustManager = createServerTrustManager(defaultTrustManager) { serverCert }

        val hv = HostnameVerifier { hostname: String, session: SSLSession ->
            try {
                val certificates: Array<Certificate> = session.peerCertificates
                val pinnedCert = serverCert
                if (pinnedCert != null && certificates.firstOrNull() == pinnedCert) {
                    return@HostnameVerifier true
                }
            } catch (e: SSLPeerUnverifiedException) {
                e.printStackTrace()
            }

            HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
        }

        httpClientLongConnectTimeout = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .hostnameVerifier(hv)
            .readTimeout(READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(LONG_CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .proxy(Proxy.NO_PROXY)
            .build()

        httpClientShortConnectTimeout = httpClientLongConnectTimeout.newBuilder()
            .connectTimeout(SHORT_CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .build()

        httpClientLongConnectNoReadTimeout = httpClientLongConnectTimeout.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @Throws(IOException::class)
    fun getHttpsUrl(likelyOnline: Boolean): HttpUrl {
        if (httpsPort == 0) {
            httpsPort = getHttpsPort(
                openHttpConnectionToString(
                    if (likelyOnline) httpClientLongConnectTimeout else httpClientShortConnectTimeout,
                    baseUrlHttp,
                    "serverinfo",
                ),
            )
        }

        return HttpUrl.Builder().scheme("https").host(baseUrlHttp.host).port(httpsPort).build()
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getServerInfo(likelyOnline: Boolean): String {
        val client = if (likelyOnline) httpClientLongConnectTimeout else httpClientShortConnectTimeout

        if (serverCert != null) {
            try {
                val resp = openHttpConnectionToString(client, getHttpsUrl(likelyOnline), "serverinfo")
                getServerVersion(resp)
                return resp
            } catch (e: IOException) {
                if (isServerInfoHttpFallbackAllowed(e)) {
                    return openHttpConnectionToString(client, baseUrlHttp, "serverinfo")
                }
                throw e
            }
        }

        return openHttpConnectionToString(client, baseUrlHttp, "serverinfo")
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getComputerDetails(serverInfo: String): ComputerDetails {
        val details = ComputerDetails()

        details.name = getXmlString(serverInfo, "hostname", false) ?: "UNKNOWN"
        if (details.name.isEmpty()) {
            details.name = "UNKNOWN"
        }

        details.uuid = getXmlString(serverInfo, "uniqueid", true)!!

        val permStr = getXmlString(serverInfo, "Permission", false)
        if (permStr != null) {
            details.permission = try {
                permStr.toInt()
            } catch (_: Exception) {
                -1
            }
        }

        details.httpsPort = getHttpsPort(serverInfo)
        details.macAddress = getXmlString(serverInfo, "mac", false)
        details.localAddress = makeTuple(getXmlString(serverInfo, "LocalIP", false), baseUrlHttp.port)
        details.externalPort = getExternalPort(serverInfo)
        details.remoteAddress = makeTuple(getXmlString(serverInfo, "ExternalIP", false), details.externalPort)

        details.vDisplaySupported = getServerSupportsVDisplay(serverInfo)
        if (details.vDisplaySupported) {
            details.vDisplayDriverReady = getServerVDisplayDriverReady(serverInfo)
        }

        details.serverCommands = getServerCmds(serverInfo)
        details.pairState = getPairState(serverInfo)
        details.runningGameId = getCurrentGame(serverInfo)
        details.runningGameUUID = getCurrentGameUUID(serverInfo)
        details.currentGameOwnedByClient = getCurrentGameOwned(serverInfo)
        details.currentGameOwnerName = getCurrentGameOwner(serverInfo)
        details.currentGameViewerCount = getCurrentGameViewerCount(serverInfo)
        details.serverMaxLaunchRefreshRate = getServerMaxLaunchRefreshRate(serverInfo)
        details.nvidiaServer = getXmlString(serverInfo, "state", true)!!.contains("MJOLNIR")
        details.state = ComputerDetails.State.ONLINE

        return details
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGameUUID(serverInfo: String): String? {
        return getXmlString(serverInfo, "currentgameuuid", false)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getComputerDetails(likelyOnline: Boolean): ComputerDetails {
        return getComputerDetails(getServerInfo(likelyOnline))
    }

    private fun performAndroidTlsHack(client: OkHttpClient): OkHttpClient {
        try {
            val sc = SSLContext.getInstance("TLS")
            sc.init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())
            return client.newBuilder().sslSocketFactory(sc.socketFactory, trustManager).build()
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        } catch (e: KeyManagementException) {
            throw RuntimeException(e)
        }
    }

    private fun getCompleteUrl(baseUrl: HttpUrl, path: String, query: String?): HttpUrl {
        if (!isKnownNvHttpPath(path)) {
            throw IllegalArgumentException("Unexpected NvHTTP path")
        }

        val completeUrl = baseUrl.newBuilder()
            .addPathSegments(path)
            .query(query)
            .addQueryParameter("devicename", deviceName)
            .addQueryParameter("uniqueid", uniqueId)
            .addQueryParameter("uuid", UUID.randomUUID().toString())
            .build()
        if (!isExpectedNvHttpUrl(completeUrl, path)) {
            throw IllegalArgumentException("Unexpected NvHTTP URL")
        }
        return completeUrl
    }

    private fun isExpectedNvHttpUrl(url: HttpUrl, path: String): Boolean {
        return isExpectedBaseUrl(url) &&
            url.encodedPath == "/$path" &&
            url.username.isEmpty() &&
            url.password.isEmpty()
    }

    @Throws(IOException::class)
    private fun openHttpConnection(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        path: String,
        query: String?,
        requestBody: RequestBody?,
    ): ResponseBody {
        if (!isExpectedBaseUrl(baseUrl)) {
            throw IOException("Unexpected NvHTTP target")
        }

        val completeUrl = getCompleteUrl(baseUrl, path, query)
        if (!isExpectedNvHttpUrl(completeUrl, path)) {
            throw IOException("Unexpected NvHTTP target")
        }
        val baseUri = baseUrl.toUri()
        val completeUri = completeUrl.toUri()
        val requestUrl = if (
            baseUri.host != null &&
            baseUri.host.equals(completeUri.host) &&
            baseUri.scheme.equals(completeUri.scheme) &&
            baseUri.port == completeUri.port
        ) {
            completeUri.toURL()
        } else {
            throw IOException("Unexpected NvHTTP target")
        }
        val builder = Request.Builder().url(requestUrl)
        val request = if (requestBody == null) {
            builder.get().build()
        } else {
            builder.post(requestBody).build()
        }

        val response = performAndroidTlsHack(client).newCall(request).execute()
        val body = response.body

        if (response.isSuccessful) {
            return body
        }

        body.close()

        if (response.code == 404) {
            throw FileNotFoundException(completeUrl.toString())
        } else {
            throw HostHttpResponseException(response.code, response.message)
        }
    }

    @Throws(IOException::class)
    private fun openHttpConnectionToString(client: OkHttpClient, baseUrl: HttpUrl, path: String): String {
        return openHttpConnectionToString(client, baseUrl, path, null, null)
    }

    @Throws(IOException::class)
    private fun openHttpConnectionToString(client: OkHttpClient, baseUrl: HttpUrl, path: String, query: String?): String {
        return openHttpConnectionToString(client, baseUrl, path, query, null)
    }

    @Throws(IOException::class)
    private fun openHttpConnectionToString(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        path: String,
        query: String?,
        requestBody: RequestBody?,
    ): String {
        try {
            val resp = openHttpConnection(client, baseUrl, path, query, requestBody)
            val respString = resp.string()
            resp.close()

            if (verbose && path != "serverinfo") {
                LimeLog.info(getCompleteUrl(baseUrl, path, query).toString() + " -> " + respString)
            }

            return respString
        } catch (e: IOException) {
            if (verbose && path != "serverinfo") {
                LimeLog.warning(getCompleteUrl(baseUrl, path, query).toString() + " -> " + e.message)
                e.printStackTrace()
            }

            throw e
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerVersion(serverInfo: String): String {
        return getXmlString(serverInfo, "appversion", true)!!
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerSupportsVDisplay(serverInfo: String): Boolean {
        return getXmlString(serverInfo, "VirtualDisplayCapable", false) == "true"
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerVDisplayDriverReady(serverInfo: String): Boolean {
        return getXmlString(serverInfo, "VirtualDisplayDriverReady", false) == "true"
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerCmds(serverInfo: String): List<String> {
        return getXmlArray(serverInfo, "ServerCommand", false)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getPairState(): PairingManager.PairState {
        return getPairState(getServerInfo(true))
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getPairState(serverInfo: String): PairingManager.PairState {
        return if (getXmlString(serverInfo, "PairStatus", true) == "1") {
            PairingManager.PairState.PAIRED
        } else {
            PairingManager.PairState.NOT_PAIRED
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getMaxLumaPixelsH264(serverInfo: String): Long {
        return getXmlString(serverInfo, "MaxLumaPixelsH264", false)?.toLong() ?: 0
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getMaxLumaPixelsHEVC(serverInfo: String): Long {
        return getXmlString(serverInfo, "MaxLumaPixelsHEVC", false)?.toLong() ?: 0
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerCodecModeSupport(serverInfo: String): Long {
        return getXmlString(serverInfo, "ServerCodecModeSupport", false)?.toLong() ?: 0
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerMaxLaunchRefreshRate(serverInfo: String): Int {
        return parseServerMaxLaunchRefreshRate(serverInfo)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGameOwned(serverInfo: String): Boolean? {
        return parseCurrentGameOwned(serverInfo)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGameSessionToken(serverInfo: String): String? {
        return parseCurrentGameSessionToken(serverInfo)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGameOwner(serverInfo: String): String? {
        return parseCurrentGameOwner(serverInfo)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGameViewerCount(serverInfo: String): Int {
        return parseCurrentGameViewerCount(serverInfo)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getGpuType(serverInfo: String): String? {
        return getXmlString(serverInfo, "gputype", false)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getGfeVersion(serverInfo: String): String? {
        return getXmlString(serverInfo, "GfeVersion", false)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun supports4K(serverInfo: String): Boolean {
        val gfeVersionStr = getXmlString(serverInfo, "GfeVersion", false)
        return !(gfeVersionStr == null || gfeVersionStr.startsWith("2."))
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGame(serverInfo: String): Int {
        return if (getXmlString(serverInfo, "state", true)!!.endsWith("_SERVER_BUSY")) {
            getXmlString(serverInfo, "currentgame", true)!!.toInt()
        } else {
            0
        }
    }

    fun getHttpsPort(serverInfo: String): Int {
        return try {
            getXmlString(serverInfo, "HttpsPort", true)!!.toInt()
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            DEFAULT_HTTPS_PORT
        } catch (e: IOException) {
            e.printStackTrace()
            DEFAULT_HTTPS_PORT
        }
    }

    fun getExternalPort(serverInfo: String): Int {
        return try {
            getXmlString(serverInfo, "ExternalPort", true)!!.toInt()
        } catch (_: XmlPullParserException) {
            baseUrlHttp.port
        } catch (e: IOException) {
            e.printStackTrace()
            baseUrlHttp.port
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getAppById(appId: Int): NvApp? {
        val appList = getAppList()
        for (appFromList in appList) {
            if (appFromList.appId == appId) {
                return appFromList
            }
        }
        return null
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getAppByName(appName: String): NvApp? {
        val appList = getAppList()
        for (appFromList in appList) {
            if (appFromList.appName.equals(appName, ignoreCase = true)) {
                return appFromList
            }
        }
        return null
    }

    fun getPairingManager(): PairingManager {
        return pm
    }

    @Throws(IOException::class)
    fun getAppListRaw(): String {
        return openHttpConnectionToString(httpClientLongConnectTimeout, getHttpsUrl(true), "applist")
    }

    @Throws(HostHttpResponseException::class, IOException::class, XmlPullParserException::class)
    fun getAppList(): LinkedList<NvApp> {
        return if (verbose) {
            getAppListByReader(StringReader(getAppListRaw()))
        } else {
            openHttpConnection(httpClientLongConnectTimeout, getHttpsUrl(true), "applist", null, null).use { resp ->
                getAppListByReader(InputStreamReader(resp.byteStream()))
            }
        }
    }

    @Throws(HostHttpResponseException::class, IOException::class)
    internal fun executePairingCommand(additionalArguments: String, enableReadTimeout: Boolean): String {
        return openHttpConnectionToString(
            if (enableReadTimeout) httpClientLongConnectTimeout else httpClientLongConnectNoReadTimeout,
            baseUrlHttp,
            "pair",
            "updateState=1&$additionalArguments",
        )
    }

    @Throws(HostHttpResponseException::class, IOException::class)
    internal fun executePairingChallenge(): String {
        return openHttpConnectionToString(
            httpClientLongConnectTimeout,
            getHttpsUrl(true),
            "pair",
            "updateState=1&phrase=pairchallenge",
        )
    }

    @Throws(IOException::class)
    fun unpair() {
        openHttpConnectionToString(httpClientLongConnectTimeout, baseUrlHttp, "unpair")
    }

    @Throws(IOException::class)
    fun getBoxArt(app: NvApp): InputStream {
        val resp = openHttpConnection(
            httpClientLongConnectTimeout,
            getHttpsUrl(true),
            "appasset",
            "appid=" + app.appId + "&AssetType=2&AssetIdx=0",
            null,
        )
        return resp.byteStream()
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerMajorVersion(serverInfo: String): Int {
        return getServerAppVersionQuad(serverInfo)[0]
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerAppVersionQuad(serverInfo: String): IntArray {
        val serverVersion = getServerVersion(serverInfo)
        val serverVersionSplit = serverVersion.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (serverVersionSplit.size != 4) {
            throw IllegalArgumentException("Malformed server version field: $serverVersion")
        }
        val ret = IntArray(serverVersionSplit.size)
        for (i in ret.indices) {
            ret[i] = serverVersionSplit[i].toInt()
        }
        return ret
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun launchApp(
        context: ConnectionContext,
        verb: String,
        appUUID: String?,
        appId: Int,
        enableHdr: Boolean,
        watchOnly: Boolean,
    ): Boolean {
        val streamConfig = context.streamConfig!!
        val requestedLaunchRefreshRate = if (context.negotiatedLaunchRefreshRate > 0) {
            context.negotiatedLaunchRefreshRate
        } else {
            streamConfig.getLaunchRefreshRate().toFloat()
        }

        val fps = if (context.isNvidiaServerSoftware && requestedLaunchRefreshRate > 60) {
            0f
        } else {
            requestedLaunchRefreshRate
        }

        var fpsInt = fps.toInt()
        if (fpsInt.toFloat() != fps) {
            fpsInt = (fps * 1000).toInt()
        }

        var enableSops = streamConfig.getSops()
        if (context.isNvidiaServerSoftware) {
            if (context.negotiatedWidth * context.negotiatedHeight > 1280 * 720 &&
                context.negotiatedWidth * context.negotiatedHeight != 1920 * 1080 &&
                context.negotiatedWidth * context.negotiatedHeight != 3840 * 2160
            ) {
                LimeLog.info(
                    "Disabling SOPS due to non-standard resolution: " +
                        context.negotiatedWidth + "x" + context.negotiatedHeight,
                )
                enableSops = false
            }
        }

        val profilePreference = streamConfig.getProfilePreference()
            .takeIf { it.isNotBlank() }
            ?.let { "&profilePreference=" + URLEncoder.encode(it, "UTF-8") }
            ?: ""
        val mirrorDesktop = streamConfig.getMirrorDesktop()
        val forcePrivateAfterSteamClose = streamConfig.getForcePrivateAfterSteamClose()

        val xmlStr = openHttpConnectionToString(
            httpClientLongConnectNoReadTimeout,
            getHttpsUrl(true),
            verb,
            "appid=" + appId +
                (if (appUUID == null) "" else "&appuuid=$appUUID") +
                "&mode=" + context.negotiatedWidth + "x" + context.negotiatedHeight + "x" + fpsInt +
                "&scaleFactor=" + streamConfig.getResolutionScaleFactor() +
                "&additionalStates=1&sops=" + (if (enableSops) 1 else 0) +
                "&rikey=" + bytesToHex(context.riKey!!.encoded) +
                "&rikeyid=" + context.riKeyId +
                (if (!enableHdr) "" else "&hdrMode=1&clientHdrCapVersion=0&clientHdrCapSupportedFlagsInUint32=0&clientHdrCapMetaDataId=NV_STATIC_METADATA_TYPE_1&clientHdrCapDisplayData=0x0x0x0x0x0x0x0x0x0x0") +
                (if (context.sessionToken.isNullOrEmpty()) "" else "&sessiontoken=" + context.sessionToken) +
                (if (watchOnly) "&watch=1" else "") +
                "&virtualDisplay=" + (if (streamConfig.getVirtualDisplay()) 1 else 0) +
                "&displayModeExplicit=" + (if (streamConfig.getDisplayModeExplicit()) 1 else 0) +
                "&mirrorDesktop=" + (if (mirrorDesktop) 1 else 0) +
                (if (mirrorDesktop) "&launchMode=mirror_desktop" else "") +
                (if (forcePrivateAfterSteamClose) "&closeDesktopSteamForPrivate=1&launchMode=force_private_stream" else "") +
                profilePreference +
                "&localAudioPlayMode=" + (if (streamConfig.getPlayLocalAudio()) 1 else 0) +
                "&surroundAudioInfo=" + streamConfig.getAudioConfiguration()!!.getSurroundAudioInfo() +
                "&remoteControllersBitmap=" + streamConfig.getAttachedGamepadMask() +
                "&gcmap=" + streamConfig.getAttachedGamepadMask() +
                "&gcpersist=" + (if (streamConfig.getPersistGamepadsAfterDisconnect()) 1 else 0) +
                MoonBridge.getLaunchUrlQueryParameters(),
        )
        return if ((verb == "launch" && getXmlString(xmlStr, "gamesession", true) != "0") ||
            (verb == "resume" && getXmlString(xmlStr, "resume", true) != "0")
        ) {
            context.rtspSessionUrl = getXmlString(xmlStr, "sessionUrl0", false)
            context.sessionToken = getXmlString(xmlStr, "sessionToken", false)
            context.currentGameOwnedByClient = !watchOnly
            true
        } else {
            false
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun quitApp(): Boolean {
        return quitApp(null)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun quitApp(sessionToken: String?): Boolean {
        val xmlStr = openHttpConnectionToString(
            httpClientLongConnectNoReadTimeout,
            getHttpsUrl(true),
            "cancel",
            if (sessionToken.isNullOrEmpty()) null else "sessiontoken=$sessionToken",
        )
        if (getXmlString(xmlStr, "cancel", true) == "0") {
            return false
        }

        if (getCurrentGame(getServerInfo(true)) != 0) {
            throw HostHttpResponseException(599, "")
        }

        return true
    }

    @Throws(IOException::class)
    fun getClipboard(): String {
        return openHttpConnectionToString(httpClientLongConnectTimeout, getHttpsUrl(true), "actions/clipboard", "type=text")
    }

    @Suppress("DEPRECATION")
    @Throws(IOException::class)
    fun sendClipboard(content: String): Boolean {
        val resp = openHttpConnectionToString(
            httpClientLongConnectTimeout,
            getHttpsUrl(true),
            "actions/clipboard",
            "type=text",
            content.toRequestBody("text/plain".toMediaTypeOrNull()),
        )
        return resp.isEmpty()
    }

    companion object {
        @JvmStatic
        internal fun isServerInfoHttpFallbackAllowed(error: IOException): Boolean =
            error is HostHttpResponseException && error.getErrorCode() == 401

        @JvmStatic
        internal fun createServerTrustManager(
            defaultTrustManager: X509TrustManager,
            serverCertProvider: () -> X509Certificate?,
        ): X509TrustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {
                throw IllegalStateException("Should never be called")
            }

            @Throws(CertificateException::class)
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
                val pinnedCert = serverCertProvider()
                if (pinnedCert != null) {
                    if (certs.firstOrNull() != pinnedCert) {
                        throw CertificateException("Certificate mismatch")
                    }
                    return
                }

                defaultTrustManager.checkServerTrusted(certs, authType)
            }
        }

        private const val DEFAULT_HTTPS_PORT = 47984
        const val DEFAULT_HTTP_PORT = 47989
        const val SHORT_CONNECTION_TIMEOUT = 3000
        const val LONG_CONNECTION_TIMEOUT = 5000
        const val READ_TIMEOUT = 7000

        private var verbose = BuildConfig.DEBUG
        private val hexArray = "0123456789ABCDEF".toCharArray()

        @Throws(IOException::class)
        private fun validateHost(host: String?): String {
            if (host == null) {
                throw IOException("Host cannot be null")
            }

            val trimmedHost = host.trim()
            if (trimmedHost.isEmpty()) {
                throw IOException("Host cannot be empty")
            }

            for (c in trimmedHost) {
                if (c <= ' ' || c == '/' || c == '\\' || c == '@' || c == '#' || c == '?') {
                    throw IOException("Invalid host")
                }
            }

            return trimmedHost
        }

        private fun isKnownNvHttpPath(path: String?): Boolean {
            return when (path) {
                "actions/clipboard",
                "appasset",
                "applist",
                "cancel",
                "launch",
                "pair",
                "resume",
                "serverinfo",
                "unpair",
                -> true
                else -> false
            }
        }

        private fun getDefaultTrustManager(): X509TrustManager {
            try {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)

                for (tm in tmf.trustManagers) {
                    if (tm is X509TrustManager) {
                        return tm
                    }
                }
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            } catch (e: KeyStoreException) {
                throw RuntimeException(e)
            }

            throw IllegalStateException("No X509 trust manager found")
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlString(r: Reader, tagname: String, throwIfMissing: Boolean): String? {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()

            xpp.setInput(r)
            var eventType = xpp.eventType
            val currentTag = Stack<String>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        currentTag.push(xpp.name)
                    }
                    XmlPullParser.END_TAG -> currentTag.pop()
                    XmlPullParser.TEXT -> {
                        if (currentTag.peek() == tagname) {
                            return xpp.text
                        }
                    }
                }
                eventType = xpp.next()
            }

            if (throwIfMissing) {
                throw XmlPullParserException("Missing mandatory field in host response: $tagname")
            }

            return null
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlString(str: String, tagname: String, throwIfMissing: Boolean): String? {
            return getXmlString(StringReader(str), tagname, throwIfMissing)
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlArray(r: Reader, tagname: String, throwIfMissing: Boolean): List<String> {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()

            xpp.setInput(r)
            var eventType = xpp.eventType
            val currentTag = Stack<String>()
            val array = ArrayList<String>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> currentTag.push(xpp.name)
                    XmlPullParser.END_TAG -> currentTag.pop()
                    XmlPullParser.TEXT -> {
                        if (currentTag.peek() == tagname) {
                            array.add(xpp.text)
                        }
                    }
                }
                eventType = xpp.next()
            }

            if (throwIfMissing && array.isEmpty()) {
                throw XmlPullParserException("Missing mandatory field in host response: $tagname")
            }

            return array
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlArray(str: String, tagname: String, throwIfMissing: Boolean): List<String> {
            return getXmlArray(StringReader(str), tagname, throwIfMissing)
        }

        @Throws(HostHttpResponseException::class)
        private fun verifyResponseStatus(xpp: XmlPullParser) {
            var statusCode = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_code").toLong().toInt()
            var statusMsg = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_message")
            if (statusCode != 200) {
                if (statusCode == -1 && statusMsg == "Invalid") {
                    statusCode = 418
                    statusMsg = "Missing audio capture device. Reinstall GeForce Experience."
                }
                throw HostHttpResponseException(statusCode, statusMsg)
            }
        }

        private fun makeTuple(address: String?, port: Int): ComputerDetails.AddressTuple? {
            if (address == null) {
                return null
            }

            return ComputerDetails.AddressTuple(address, port)
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun parseServerMaxLaunchRefreshRate(serverInfo: String): Int {
            val str = getXmlString(serverInfo, "ServerMaxLaunchRefreshRate", false)
            if (str != null) {
                return try {
                    str.toInt()
                } catch (_: NumberFormatException) {
                    0
                }
            }

            return 0
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun parseCurrentGameOwned(serverInfo: String): Boolean? {
            val str = getXmlString(serverInfo, "currentgameowned", false) ?: return null
            return str != "0" && !str.equals("false", ignoreCase = true)
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun parseCurrentGameSessionToken(serverInfo: String): String? {
            return getXmlString(serverInfo, "currentgamesessiontoken", false)
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun parseCurrentGameOwner(serverInfo: String): String? {
            return getXmlString(serverInfo, "currentgameowner", false)
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun parseCurrentGameViewerCount(serverInfo: String): Int {
            val str = getXmlString(serverInfo, "currentgameviewercount", false)
            if (str != null) {
                return try {
                    str.toInt()
                } catch (_: NumberFormatException) {
                    0
                }
            }

            return 0
        }

        @JvmStatic
        @Throws(XmlPullParserException::class, IOException::class)
        fun getAppListByReader(r: Reader): LinkedList<NvApp> {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()

            xpp.setInput(r)
            var eventType = xpp.eventType
            val appList = LinkedList<NvApp>()
            val currentTag = Stack<String>()
            var rootTerminated = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        currentTag.push(xpp.name)
                        if (xpp.name == "App") {
                            appList.addLast(NvApp())
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag.pop()
                        if (xpp.name == "root") {
                            rootTerminated = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (currentTag.isNotEmpty() && currentTag.contains("App") && appList.isNotEmpty()) {
                            val app = appList.last()
                            when (currentTag.peek()) {
                                "AppTitle" -> app.appName = xpp.text
                                "UUID" -> app.appUUID = xpp.text
                                "IDX" -> app.setAppIndex(xpp.text)
                                "ID" -> app.setAppId(xpp.text)
                                "IsHdrSupported" -> app.isHdrSupported = xpp.text == "1"
                            }
                        }
                    }
                }
                eventType = xpp.next()
            }

            if (!rootTerminated) {
                throw XmlPullParserException("Malformed XML: Root tag was not terminated")
            }

            val iterator = appList.listIterator()
            while (iterator.hasNext()) {
                val app = iterator.next()
                if (!app.isInitialized()) {
                    LimeLog.warning("GFE returned incomplete app: " + app.appId + " " + app.appName)
                    iterator.remove()
                }
            }

            return appList
        }

        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }
    }
}

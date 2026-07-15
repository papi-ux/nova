package com.papi.nova.computers

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.HostHttpResponseException
import com.papi.nova.nvstream.http.NvHTTP
import javax.net.ssl.SSLHandshakeException
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ScheduledFuture
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class KotlinComputerServiceMigrationTest {
    @Test
    fun computerManagerServiceIsKotlinSource() {
        val javaFile = File("src/main/java/com/papi/nova/computers/ComputerManagerService.java")
        val kotlinFile = File("src/main/java/com/papi/nova/computers/ComputerManagerService.kt")

        assertFalse("ComputerManagerService should no longer be a Java source", javaFile.exists())
        assertTrue("ComputerManagerService should be migrated to Kotlin", kotlinFile.exists())
    }

    @Test
    fun computerManagerServiceKeepsJavaCompatibleBinderAndPollerApis() {
        val booleanType = Boolean::class.javaPrimitiveType!!

        assertTrue(Service::class.java.isAssignableFrom(ComputerManagerService::class.java))
        ComputerManagerService::class.java.getConstructor()
        assertEquals(IBinder::class.java, ComputerManagerService::class.java.getMethod("onBind", Intent::class.java).returnType)
        assertEquals(booleanType, ComputerManagerService::class.java.getMethod("onUnbind", Intent::class.java).returnType)
        ComputerManagerService::class.java.getMethod("addComputerBlocking", ComputerDetails::class.java)
        ComputerManagerService::class.java.getMethod("removeComputer", ComputerDetails::class.java)

        assertTrue(Binder::class.java.isAssignableFrom(ComputerManagerService.ComputerManagerBinder::class.java))
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("startPolling", ComputerManagerListener::class.java)
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("waitForReady")
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("waitForPollingStopped")
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("addComputerBlocking", ComputerDetails::class.java)
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("removeComputer", ComputerDetails::class.java)
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("stopPolling")
        assertEquals(
            ComputerManagerService.ApplistPoller::class.java,
            ComputerManagerService.ComputerManagerBinder::class.java.getMethod(
                "createAppListPoller",
                ComputerDetails::class.java
            ).returnType
        )
        assertEquals(
            String::class.java,
            ComputerManagerService.ComputerManagerBinder::class.java.getMethod("getUniqueId").returnType
        )
        assertEquals(
            ComputerDetails::class.java,
            ComputerManagerService.ComputerManagerBinder::class.java.getMethod("getComputer", String::class.java).returnType
        )
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("persistComputer", ComputerDetails::class.java)
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("persistComputerState", String::class.java)
        ComputerManagerService.ComputerManagerBinder::class.java.getMethod("invalidateStateForComputer", String::class.java)

        val pollerConstructor = ComputerManagerService.ApplistPoller::class.java.getConstructor(
            ComputerManagerService::class.java,
            ComputerDetails::class.java
        )
        assertNotNull(pollerConstructor)
        ComputerManagerService.ApplistPoller::class.java.getMethod("pollNow")
        ComputerManagerService.ApplistPoller::class.java.getMethod("start")
        ComputerManagerService.ApplistPoller::class.java.getMethod("stop")
    }

    @Test
    fun binderAndPollerKeepFocusedLifecycleHelpers() {
        val service = ComputerManagerService()
        val binder = service.onBind(null) as ComputerManagerService.ComputerManagerBinder

        assertNull(binder.getComputer("missing"))

        val computer = ComputerDetails()
        computer.name = "Nova Test PC"
        val poller = binder.createAppListPoller(computer)

        assertNotNull(poller)
        poller.pollNow()
        poller.stop()
    }

    @Test
    fun appListPollerReportsHostDataFailuresInsteadOfEscapingRuntimeExceptions() {
        val serviceSource = File("src/main/java/com/papi/nova/computers/ComputerManagerService.kt").readText()
        val detailsSource = File("src/main/java/com/papi/nova/nvstream/http/ComputerDetails.kt").readText()

        assertTrue(serviceSource.contains("private fun reportAppListLoadFailure"))
        assertTrue(serviceSource.contains("computer.appListLoadError"))
        assertTrue(serviceSource.contains("catch (e: RuntimeException)"))
        assertTrue(serviceSource.contains("The host did not advertise any standard app list entries."))
        assertFalse(serviceSource.contains("EMPTY_LIST_THRESHOLD"))
        assertFalse(serviceSource.contains("emptyAppListResponses"))
        assertTrue(detailsSource.contains("var appListLoadError: String? = null"))
    }

    @Test
    fun blankUuidManualPollAcceptsReturnedComputerUuid() {
        val service = ComputerManagerService()
        val matcher = ComputerManagerService::class.java.getDeclaredMethod(
            "isExpectedComputerUuid",
            String::class.java,
            String::class.java
        )
        matcher.isAccessible = true

        assertEquals(true, matcher.invoke(service, null, "server-uuid"))
        assertEquals(true, matcher.invoke(service, "", "server-uuid"))
        assertEquals(true, matcher.invoke(service, "server-uuid", "server-uuid"))
        assertEquals(false, matcher.invoke(service, "other-uuid", "server-uuid"))
        assertEquals(false, matcher.invoke(service, "", null))
        assertEquals(false, matcher.invoke(service, "", ""))
        assertEquals(false, matcher.invoke(service, "", "   "))
    }

    @Test
    fun pollCandidateWiringPassesStoredCertificateAndInvokesUuidGuard() {
        // This is a narrow wiring guard. Strict pin and UUID behavior are exercised independently below.
        val source = File("src/main/java/com/papi/nova/computers/ComputerManagerService.kt").readText()
        val functionStart = source.indexOf("private fun tryPollIp")
        val functionEnd = source.indexOf("\n    private fun ", functionStart + 1)
        assertTrue("tryPollIp should remain present", functionStart >= 0 && functionEnd > functionStart)
        val body = source.substring(functionStart, functionEnd)

        val pinnedCertificate = body.indexOf("details.serverCert")
        val fetchDetails = body.indexOf("http.getComputerDetails")
        val uuidCheck = body.indexOf("else if (!isExpectedComputerUuid(expectedUuid, returnedUuid))")
        val acceptedDetails = body.indexOf("\n                newDetails", uuidCheck)

        assertTrue("polling must pass the stored server certificate before connecting", pinnedCertificate >= 0)
        assertTrue("certificate pinning must be configured before host details are fetched", pinnedCertificate < fetchDetails)
        assertTrue("poll results must be checked against the expected UUID", uuidCheck > fetchDetails)
        assertTrue("host details must only be accepted after the UUID check", acceptedDetails > uuidCheck)
    }

    @Test
    fun pollComputerWiringUsesTheVerifiedRouteUpdateBoundary() {
        val source = File("src/main/java/com/papi/nova/computers/ComputerManagerService.kt").readText()
        val functionStart = source.indexOf("private fun pollComputer")
        val functionEnd = source.indexOf("\n    override fun ", functionStart + 1)
        assertTrue("pollComputer should remain present", functionStart >= 0 && functionEnd > functionStart)
        val body = source.substring(functionStart, functionEnd)

        assertTrue(body.contains("details.updateFromVerifiedPoll(polledDetails)"))
        assertFalse(body.contains("details.update(polledDetails)"))
    }

    @Test
    fun parallelPollingRetriesSavedLocalAddressOnDefaultHttpPort() {
        val details = ComputerDetails()
        details.localAddress = ComputerDetails.AddressTuple("10.0.0.232", 49000)

        val addresses = ComputerManagerService.buildParallelPollAddresses(details)

        assertEquals(ComputerDetails.AddressTuple("10.0.0.232", 49000), addresses[0])
        assertEquals(
            ComputerDetails.AddressTuple("10.0.0.232", NvHTTP.DEFAULT_HTTP_PORT),
            addresses[1]
        )
    }

    @Test
    fun parallelPollingDoesNotDuplicateDefaultLocalAddress() {
        val details = ComputerDetails()
        details.localAddress = ComputerDetails.AddressTuple("10.0.0.232", NvHTTP.DEFAULT_HTTP_PORT)

        val addresses = ComputerManagerService.buildParallelPollAddresses(details)

        assertEquals(1, addresses.size)
        assertEquals(
            ComputerDetails.AddressTuple("10.0.0.232", NvHTTP.DEFAULT_HTTP_PORT),
            addresses[0]
        )
    }

    @Test
    fun parallelPollingIncludesRememberedRoutesWithoutDuplicates() {
        val details = ComputerDetails()
        details.localAddress = ComputerDetails.AddressTuple("100.100.20.30", NvHTTP.DEFAULT_HTTP_PORT)
        details.manualAddress = ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", NvHTTP.DEFAULT_HTTP_PORT)
        details.rememberAddress(details.localAddress)
        details.rememberAddress(details.manualAddress)
        details.rememberAddress(ComputerDetails.AddressTuple("192.168.1.25", NvHTTP.DEFAULT_HTTP_PORT))

        val addresses = ComputerManagerService.buildParallelPollAddresses(details)

        assertEquals(
            listOf(
                ComputerDetails.AddressTuple("100.100.20.30", NvHTTP.DEFAULT_HTTP_PORT),
                ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", NvHTTP.DEFAULT_HTTP_PORT),
                ComputerDetails.AddressTuple("192.168.1.25", NvHTTP.DEFAULT_HTTP_PORT)
            ),
            addresses
        )
    }

    @Test
    fun parallelPollingPrefersRecentRememberedRoutesAndRetriesTheirDefaultPort() {
        val details = ComputerDetails()
        details.manualAddress = ComputerDetails.AddressTuple("current.example.test", 48000)
        details.rememberAddress(ComputerDetails.AddressTuple("old.example.test", 49000))
        details.rememberAddress(ComputerDetails.AddressTuple("recent.example.test", 49100))

        val addresses = ComputerManagerService.buildParallelPollAddresses(details)

        assertEquals(
            listOf(
                ComputerDetails.AddressTuple("current.example.test", 48000),
                ComputerDetails.AddressTuple("recent.example.test", 49100),
                ComputerDetails.AddressTuple("recent.example.test", NvHTTP.DEFAULT_HTTP_PORT),
                ComputerDetails.AddressTuple("old.example.test", 49000),
                ComputerDetails.AddressTuple("old.example.test", NvHTTP.DEFAULT_HTTP_PORT)
            ),
            addresses
        )
    }

    @Test
    fun serverInfoPlaintextFallbackAllowsOnlyRealHttpUnauthorizedResponses() {
        assertTrue(NvHTTP.isServerInfoHttpFallbackAllowed(HostHttpResponseException(401, "Unauthorized")))
        assertFalse(NvHTTP.isServerInfoHttpFallbackAllowed(HostHttpResponseException(403, "Forbidden")))
        assertFalse(NvHTTP.isServerInfoHttpFallbackAllowed(SSLHandshakeException("Certificate mismatch")))
    }

    @Test
    fun storedCertificateRejectsDifferentPlatformTrustedCertificate() {
        val pinnedCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        val differentPlatformTrustedCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        var platformTrustChecks = 0
        val permissivePlatformTrust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                platformTrustChecks++
            }
        }

        val trustManager = NvHTTP.createServerTrustManager(permissivePlatformTrust) { pinnedCertificate }

        org.junit.Assert.assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(emptyArray(), "RSA")
        }
        org.junit.Assert.assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(differentPlatformTrustedCertificate), "RSA")
        }
        assertEquals(0, platformTrustChecks)

        trustManager.checkServerTrusted(
            arrayOf(pinnedCertificate, differentPlatformTrustedCertificate),
            "RSA"
        )
        assertEquals(0, platformTrustChecks)
    }

    @Test
    fun pinnedLeafBypassesHostnameVerificationForMultiCertificateChains() {
        val clientCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        val clientPrivateKey = org.mockito.Mockito.mock(java.security.PrivateKey::class.java)
        val pinnedServerCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        val intermediateCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        val cryptoProvider = org.mockito.Mockito.mock(com.papi.nova.nvstream.http.LimelightCryptoProvider::class.java)
        org.mockito.Mockito.`when`(cryptoProvider.clientCertificate).thenReturn(clientCertificate)
        org.mockito.Mockito.`when`(cryptoProvider.clientPrivateKey).thenReturn(clientPrivateKey)
        org.mockito.Mockito.`when`(cryptoProvider.pemEncodedClientCertificate).thenReturn(byteArrayOf(1))

        val http = NvHTTP(
            ComputerDetails.AddressTuple("127.0.0.1", NvHTTP.DEFAULT_HTTP_PORT),
            0,
            "test-client",
            pinnedServerCertificate,
            cryptoProvider
        )
        val clientField = NvHTTP::class.java.getDeclaredField("httpClientLongConnectTimeout")
        clientField.isAccessible = true
        val client = clientField.get(http) as okhttp3.OkHttpClient
        val session = org.mockito.Mockito.mock(javax.net.ssl.SSLSession::class.java)
        org.mockito.Mockito.`when`(session.peerCertificates).thenReturn(
            arrayOf(pinnedServerCertificate, intermediateCertificate)
        )

        assertTrue(client.hostnameVerifier.verify("127.0.0.1", session))
    }

    @Test
    fun nvHttpTrustManagerTracksStoredCertificateChanges() {
        val clientCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        val clientPrivateKey = org.mockito.Mockito.mock(java.security.PrivateKey::class.java)
        val firstServerCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        val secondServerCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        val cryptoProvider = org.mockito.Mockito.mock(com.papi.nova.nvstream.http.LimelightCryptoProvider::class.java)
        org.mockito.Mockito.`when`(cryptoProvider.clientCertificate).thenReturn(clientCertificate)
        org.mockito.Mockito.`when`(cryptoProvider.clientPrivateKey).thenReturn(clientPrivateKey)
        org.mockito.Mockito.`when`(cryptoProvider.pemEncodedClientCertificate).thenReturn(byteArrayOf(1))

        val http = NvHTTP(
            ComputerDetails.AddressTuple("127.0.0.1", NvHTTP.DEFAULT_HTTP_PORT),
            0,
            "test-client",
            firstServerCertificate,
            cryptoProvider
        )
        val field = NvHTTP::class.java.getDeclaredField("trustManager")
        field.isAccessible = true
        val trustManager = field.get(http) as X509TrustManager

        trustManager.checkServerTrusted(arrayOf(firstServerCertificate), "RSA")
        http.setServerCert(secondServerCertificate)
        org.junit.Assert.assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(firstServerCertificate), "RSA")
        }
        trustManager.checkServerTrusted(arrayOf(secondServerCertificate), "RSA")
    }

    @Test
    fun storedCertificateProviderIsReevaluatedAfterPairing() {
        val firstCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        val pairedCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        var storedCertificate: X509Certificate? = null
        var platformTrustChecks = 0
        val platformTrust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                platformTrustChecks++
            }
        }
        val trustManager = NvHTTP.createServerTrustManager(platformTrust) { storedCertificate }

        trustManager.checkServerTrusted(arrayOf(firstCertificate), "RSA")
        assertEquals(1, platformTrustChecks)

        storedCertificate = pairedCertificate
        org.junit.Assert.assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(arrayOf(firstCertificate), "RSA")
        }
        trustManager.checkServerTrusted(arrayOf(pairedCertificate), "RSA")
        assertEquals(1, platformTrustChecks)
    }

    @Test
    fun unpinnedConnectionDelegatesToPlatformTrustManager() {
        val platformTrustedCertificate = org.mockito.Mockito.mock(X509Certificate::class.java)
        var platformTrustChecks = 0
        val platformTrust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                platformTrustChecks++
            }
        }

        val trustManager = NvHTTP.createServerTrustManager(platformTrust) { null }
        trustManager.checkServerTrusted(arrayOf(platformTrustedCertificate), "RSA")

        assertEquals(1, platformTrustChecks)
    }

    @Test
    fun pollingTupleAndReachabilityTupleKeepJavaFieldShape() {
        val computer = ComputerDetails()
        computer.uuid = "service-test"

        val pollingTupleClass = Class.forName("com.papi.nova.computers.PollingTuple")
        val pollingTupleConstructor = pollingTupleClass.getConstructor(ComputerDetails::class.java)
        val pollingTuple = pollingTupleConstructor.newInstance(computer)

        val future = pollingTupleClass.getField("future")
        val tupleComputer = pollingTupleClass.getField("computer")
        val networkLock = pollingTupleClass.getField("networkLock")
        val lastSuccessfulPollMs = pollingTupleClass.getField("lastSuccessfulPollMs")
        val offlineCount = pollingTupleClass.getField("offlineCount")

        assertEquals(ScheduledFuture::class.java, future.type)
        assertEquals(ComputerDetails::class.java, tupleComputer.type)
        assertEquals(Any::class.java, networkLock.type)
        assertEquals(Long::class.javaPrimitiveType!!, lastSuccessfulPollMs.type)
        assertEquals(Int::class.javaPrimitiveType!!, offlineCount.type)
        assertSame(computer, tupleComputer.get(pollingTuple))
        assertNotNull(networkLock.get(pollingTuple))

        val reachabilityTupleClass = Class.forName("com.papi.nova.computers.ReachabilityTuple")
        val reachabilityTupleConstructor = reachabilityTupleClass.getConstructor(ComputerDetails::class.java, String::class.java)
        val reachabilityTuple = reachabilityTupleConstructor.newInstance(computer, "192.0.2.10")

        val reachableAddress = reachabilityTupleClass.getField("reachableAddress")
        val reachableComputer = reachabilityTupleClass.getField("computer")
        assertEquals(String::class.java, reachableAddress.type)
        assertEquals(ComputerDetails::class.java, reachableComputer.type)
        assertEquals("192.0.2.10", reachableAddress.get(reachabilityTuple))
        assertSame(computer, reachableComputer.get(reachabilityTuple))
    }
}

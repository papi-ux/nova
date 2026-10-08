package com.papi.nova.api

import com.papi.nova.LimeLog
import com.papi.nova.network.HostConnectionEvidence
import java.io.IOException
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The client half of the frozen network-connection contract, over an intercepted HTTP client. */
@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PolarisNetworkConnectionTest {
    private val logs = mutableListOf<String>()
    private val capture = object : Handler() {
        override fun publish(record: LogRecord) { synchronized(logs) { logs += record.message.orEmpty() } }
        override fun flush() = Unit
        override fun close() = Unit
    }
    private val logger = Logger.getLogger(LimeLog::class.java.name)

    @Before fun captureLogs() = logger.addHandler(capture)
    @After fun releaseLogs() = logger.removeHandler(capture)

    private fun capabilities(networkConnection: Any?) = PolarisApiClient.parseCapabilitiesResponse(
        JSONObject().put("features", JSONObject().apply { if (networkConnection != null) put("network_connection_v1", networkConnection) }),
    )

    private fun client(respond: (okhttp3.Request) -> okhttp3.Response): Pair<PolarisApiClient, MutableList<String>> {
        val client = PolarisApiClient(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "127.0.0.1", 47984)
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val intercepted = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            respond(chain.request())
        }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(client, intercepted)
        return client to paths
    }

    private fun response(request: okhttp3.Request, code: Int, body: String) =
        okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("x")
            .body(okhttp3.ResponseBody.create(null, body)).build()

    private fun freshBody(): String = JSONObject()
        .put("schema_version", 1).put("status", "tailscale").put("address_kind", "cgnat")
        .put("tailscale", JSONObject().put("confirmed", true).put("transport", "derp").put("scope", "control_connection")
            .put("source", "tailscaled_localapi").put("observed_at_ms", System.currentTimeMillis())
            .put("max_age_ms", 10_000).put("reason", "observed"))
        .toString()

    @Test fun theCapabilityIsAStrictBoolean() {
        assertTrue(capabilities(true).features.networkConnectionV1)
        assertFalse(capabilities("true").features.networkConnectionV1)
        assertFalse(capabilities(1).features.networkConnectionV1)
        assertFalse(capabilities(null).features.networkConnectionV1)
    }

    @Test fun aHostWithoutTheCapabilityIsNotAskedAndIsUnknown() {
        val (api, paths) = client { error("must not be called") }
        listOf(null, capabilities(null), capabilities(false)).forEach { caps ->
            val observation = api.getNetworkConnection(caps)
            assertFalse(observation.supported)
            assertEquals(HostConnectionEvidence.unknown(HostConnectionEvidence.Reason.HOST_UNSUPPORTED),
                observation.evaluate(System.currentTimeMillis()))
        }
        assertTrue(paths.isEmpty())
    }

    @Test fun aCapableHostIsAskedOnThePairedApiRoute() {
        val (api, paths) = client { response(it, 200, freshBody()) }
        val evidence = api.getNetworkConnection(capabilities(true)).evaluate(System.currentTimeMillis())
        assertEquals(listOf("/polaris/v1/network/connection"), paths.toList())
        assertEquals(HostConnectionEvidence.Status.TAILSCALE, evidence.status)
        assertEquals(HostConnectionEvidence.Transport.DERP, evidence.transport)
    }

    @Test fun notFoundIsAnOldHostAndOtherFailuresAreUnknown() {
        val (notFound, _) = client { response(it, 404, "") }
        assertFalse(notFound.getNetworkConnection(capabilities(true)).supported)

        val (failing, _) = client { response(it, 503, freshBody()) }
        val failed = failing.getNetworkConnection(capabilities(true))
        assertNull(failed.body)
        assertEquals(HostConnectionEvidence.Status.UNKNOWN, failed.evaluate(System.currentTimeMillis()).status)
    }

    @Test fun failuresAndAnswersNeverLogAddressesOrTheBody() {
        val (throwing, _) = client { throw IOException("failed to connect to /100.101.102.103 (gaming-pc.tail0000.ts.net)") }
        assertNull(throwing.getNetworkConnection(capabilities(true)).body)
        val (answering, _) = client { response(it, 200, freshBody().replace("\"cgnat\"", "\"cgnat\",\"x\":\"100.64.1.2\"")) }
        answering.getNetworkConnection(capabilities(true))
        val logged = synchronized(logs) { logs.joinToString("\n") }
        listOf("100.101", "100.64", "ts.net", "gaming-pc", "tailscaled_localapi").forEach {
            assertFalse("logs must not carry $it", logged.contains(it))
        }
    }
}

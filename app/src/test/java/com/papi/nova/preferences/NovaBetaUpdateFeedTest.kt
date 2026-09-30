package com.papi.nova.preferences

import com.papi.nova.BuildConfig
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaBetaUpdateFeedTest {
    private fun release(tag: String, beta: Boolean = true, draft: Boolean = false) = JSONObject()
        .put("tag_name", tag).put("html_url", "https://github.com/papi-ux/nova/releases/tag/$tag")
        .put("prerelease", beta).put("draft", draft).put("body", "Notes for $tag")
        .put("assets", JSONArray().put(JSONObject().put("name", "Nova-Beta-arm64-v8a.apk")
            .put("browser_download_url", "https://downloads.example/$tag.apk")))

    private fun client(requests: MutableList<Request>, handler: (Request) -> Pair<String, String?>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val (body, next) = handler(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(ResponseBody.create(null, body)).apply { if (next != null) header("Link", next) }.build()
        }.build()

    private fun checkBeta(vararg releases: JSONObject, current: String = "1.4.14-beta.1"): Pair<NovaUpdateCheckResult, List<Request>> {
        val requests = mutableListOf<Request>()
        val list = JSONArray(); releases.forEach(list::put)
        val http = client(requests) { request ->
            // The existing /latest route gets a valid stable object, so the red is behavioral,
            // not an array parse failure caused by the future fixture.
            if (request.url.encodedPath.endsWith("/latest")) release("v1.4.14", beta = false).toString() to null
            else list.toString() to null
        }
        return NovaUpdateChecker.checkLatest(http, current, listOf("arm64-v8a"), NovaUpdateChannel.BETA) to requests
    }

    private fun chosen(result: NovaUpdateCheckResult) = when (result) {
        is NovaUpdateCheckResult.UpdateAvailable -> result.release
        is NovaUpdateCheckResult.UpToDate -> result.release
    }

    @Test fun betaFetchesThePrereleaseListAndKeepsItsVersionSuffix() {
        val (result, requests) = checkBeta(release("v1.4.14-beta.2"))
        assertTrue(result is NovaUpdateCheckResult.UpdateAvailable)
        assertEquals("/repos/papi-ux/nova/releases", requests.single().url.encodedPath)
        assertEquals("100", requests.single().url.queryParameter("per_page"))
        assertEquals("1.4.14-beta.2", chosen(result).versionName)
        assertEquals("Nova-Beta-arm64-v8a.apk", chosen(result).apkAssetName)
        assertEquals("Notes for v1.4.14-beta.2", chosen(result).releaseNotes)
    }

    @Test fun betaIgnoresStableDraftAndMalformedTagsThenChoosesTheHighestEligibleVersion() {
        val (result, _) = checkBeta(release("v2.0.0", beta = false), release("v9.0.0-beta.1", draft = true),
            release("v1.4.14-beta.2"), release("nightly-999"), release("v1.4.14-beta.10"), release("v1.4.14-beta.3"))
        assertEquals("v1.4.14-beta.10", chosen(result).tagName)
    }

    @Test fun unchangedOrOlderBetaIsNotReportedAsAnUpdate() {
        for (current in listOf("1.4.14-beta.2", "1.4.14-rc.1", "1.4.15-beta.1")) {
            assertTrue("current=$current", checkBeta(release("v1.4.14-beta.2"), current = current).first is NovaUpdateCheckResult.UpToDate)
        }
    }

    @Test fun betaAndRcOrdinalsCompareNumericallyAndPreBuildIsTheLowestKnownPrerelease() {
        assertTrue(NovaUpdateChecker.isNewerVersion("v1.4.14-beta.2", "1.4.14-beta.1"))
        assertTrue(NovaUpdateChecker.isNewerVersion("1.4.14-beta.10", "1.4.14-beta.9"))
        assertTrue(NovaUpdateChecker.isNewerVersion("1.4.14-rc.1", "1.4.14-beta.49"))
        assertTrue(NovaUpdateChecker.isNewerVersion("1.4.14-rc.10", "1.4.14-rc.9"))
        assertTrue(NovaUpdateChecker.isNewerVersion("1.4.14-beta.1", "1.4.14-pre"))
        assertTrue(NovaUpdateChecker.isNewerVersion("1.4.14", "1.4.14-rc.49"))
        assertFalse(NovaUpdateChecker.isNewerVersion("1.4.14-beta.9+new-build", "1.4.14-beta.10"))
    }

    @Test fun betaWithNoEligiblePrereleaseDoesNotOfferTheStablePackage() {
        try {
            checkBeta(release("v2.0.0", beta = false), release("v1.4.14-beta.2", draft = true), release("unversioned"))
            fail("no eligible Beta release should produce a clear unavailable-feed failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Beta", ignoreCase = true))
        }
    }

    @Test fun betaReadsTheNextListPageBeforeSelectingTheWinner() {
        val requests = mutableListOf<Request>()
        val http = client(requests) { request ->
            when {
                request.url.encodedPath.endsWith("/latest") -> release("v1.4.14", beta = false).toString() to null
                request.url.queryParameter("page") == "2" -> JSONArray().put(release("v1.4.14-beta.10")).toString() to null
                else -> JSONArray().put(release("v2.0.0", beta = false)).put(release("v1.4.14-beta.2")).toString() to
                    "<https://api.github.com/repos/papi-ux/nova/releases?per_page=100&page=2>; rel=\"next\""
            }
        }
        val result = NovaUpdateChecker.checkLatest(http, "1.4.14-beta.1", listOf("arm64-v8a"), NovaUpdateChannel.BETA)
        assertEquals("v1.4.14-beta.10", chosen(result).tagName)
        assertEquals(2, requests.size)
    }

    @Test fun stableChannelAndDebugBuildKeepTheStableLatestEndpoint() {
        val requests = mutableListOf<Request>()
        val http = client(requests) { release("v1.4.14", beta = false).toString() to null }
        val explicit = NovaUpdateChecker.checkLatest(http, "1.4.13", listOf("arm64-v8a"), NovaUpdateChannel.STABLE)
        assertTrue(explicit is NovaUpdateCheckResult.UpdateAvailable)
        assertEquals(NovaUpdateChecker.LATEST_RELEASE_API_URL, requests.single().url.toString())
        if (BuildConfig.BUILD_TYPE != "preRelease") {
            NovaUpdateChecker.checkLatest(http, "1.4.13", listOf("arm64-v8a"))
            assertEquals(NovaUpdateChecker.LATEST_RELEASE_API_URL, requests.last().url.toString())
        }
    }

    @Test fun defaultChannelUsesTheActualBuildVariantsFeed() {
        val requests = mutableListOf<Request>()
        val http = client(requests) { request ->
            if (request.url.encodedPath.endsWith("/latest")) release("v1.4.15", beta = false).toString() to null
            else JSONArray().put(release("v1.4.15-beta.2")).toString() to null
        }
        val result = NovaUpdateChecker.checkLatest(http, "1.4.14-pre", listOf("arm64-v8a"))
        val beta = BuildConfig.BUILD_TYPE == "preRelease"
        assertEquals(if (beta) "v1.4.15-beta.2" else "v1.4.15", chosen(result).tagName)
        assertEquals(if (beta) "/repos/papi-ux/nova/releases" else "/repos/papi-ux/nova/releases/latest",
            requests.single().url.encodedPath)
    }
}

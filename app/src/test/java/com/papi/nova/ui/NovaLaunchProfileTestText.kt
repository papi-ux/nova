package com.papi.nova.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject

/** The launch summary's words from the app's own string resources, as the game page gives them. */
internal fun testLaunchProfileText(): NovaLaunchProfileText =
    NovaLaunchProfileText(ApplicationProvider.getApplicationContext<Context>().resources)

/** [buildNovaLaunchProfileSummary] with the app's own resources, for a Robolectric test. */
internal fun buildTestLaunchProfileSummary(
    optimization: JSONObject?,
    nowSeconds: Long = System.currentTimeMillis() / 1000L,
    clientAskedFps: Double = 0.0,
    clientFpsPinned: Boolean = false,
    clientAskedHdr: Boolean? = null,
    spaceName: String = "",
    clientCodecLabel: String? = null,
): NovaLaunchProfileSummary? = buildNovaLaunchProfileSummary(
    testLaunchProfileText(),
    optimization,
    nowSeconds = nowSeconds,
    clientAskedFps = clientAskedFps,
    clientFpsPinned = clientFpsPinned,
    clientAskedHdr = clientAskedHdr,
    spaceName = spaceName,
    clientCodecLabel = clientCodecLabel,
)

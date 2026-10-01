package com.papi.nova.api

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.NovaTitleCardDrawable
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual poster loader paths; HTTP is intercepted before any socket or paired host is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PolarisPosterIdentityTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val views = mutableListOf<ImageView>()
    private val gates = mutableListOf<CountDownLatch>()
    private val cache = PolarisArtworkDiskCache(context, HOST, PORT)

    @After fun close() {
        gates.forEach(CountDownLatch::countDown)
        views.forEach(PolarisApiClient::releaseArtworkView)
        cache.clear()
    }

    private fun view() = ImageView(context).also(views::add)
    private fun gate() = CountDownLatch(1).also(gates::add)
    private fun game(name: String = "Big Walk") = PolarisGame(id = "ordinary-poster", name = name)

    private fun client(reply: (Request) -> Response): PolarisApiClient {
        val api = PolarisApiClient(context, HOST, PORT)
        val http = OkHttpClient.Builder().addInterceptor { reply(it.request()) }.build()
        PolarisApiClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(api, http)
        return api
    }

    private fun response(request: Request, code: Int = 404, bytes: ByteArray = byteArrayOf()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .header("Content-Type", "image/png").body(ResponseBody.create(null, bytes)).build()

    private fun finish(view: ImageView) {
        val job = view.getTag(R.id.nova_artwork_job) as? Job ?: error("fixture never started a load")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!job.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("artwork coroutine must finish, not silently pass on its initial drawable", job.isCompleted)
    }

    private fun assertTitle(view: ImageView, expected: String) {
        assertTrue("the actual ImageView has a named poster", view.drawable is NovaTitleCardDrawable)
        assertEquals(expected, (view.drawable as NovaTitleCardDrawable).title)
    }

    private fun png(): ByteArray = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).let { bitmap ->
        ByteArrayOutputStream().use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }.also { bitmap.recycle() }
    }

    @Test fun ordinaryPostersNameTheirGameWhileTheRequestIsStillLoading() {
        val entered = CountDownLatch(1)
        val release = gate()
        val api = client { request ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            response(request)
        }
        val target = view()
        api.loadCoverInto(target, game())
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTitle(target, "Big Walk")
        release.countDown()
        finish(target)
    }

    @Test fun aFailedOrdinaryPosterKeepsItsNameAfterTheRequestCompletes() {
        val target = view()
        client { response(it) }.loadCoverInto(target, game("星のカービィ"))
        finish(target)
        assertTitle(target, "星のカービィ")
    }

    @Test fun anUndecodableSuccessfulResponseKeepsTheNamedPoster() {
        val target = view()
        client { response(it, 200, byteArrayOf(1, 2, 3)) }.loadCoverInto(target, game())
        finish(target)
        assertTitle(target, "Big Walk")
    }

    @Test fun aSuccessfulImageReplacesTheNamedLoadingPosterIncludingALegacyGenericBox() {
        val target = view()
        client { response(it, 200, png()) }.loadCoverInto(target, game())
        finish(target)
        // No placeholder detection is invented for a successful legacy image response.
        assertTrue(target.drawable is BitmapDrawable)
        assertEquals(2, (target.drawable as BitmapDrawable).bitmap.width)
        assertEquals(3, (target.drawable as BitmapDrawable).bitmap.height)
    }

    @Test fun aFailedNewRevisionStillShowsThePreviouslyCachedRealPoster() {
        cache.clear()
        assertNotNull(cache.store("ordinary-poster", "poster", "previous", png(), "image/png"))
        val target = view()
        val entry = game().copy(artwork = PolarisGame.ArtworkManifest(
            revision = "current", assets = PolarisGame.ArtworkAssets(poster = PolarisGame.ArtworkAsset(
                url = "/polaris/v1/games/ordinary-poster/artwork/poster", cached = true))))
        client { response(it) }.loadCoverInto(target, entry)
        finish(target)
        assertTrue("stale real art remains preferable to a generated title card", target.drawable is BitmapDrawable)
        assertEquals(3, (target.drawable as BitmapDrawable).bitmap.height)
    }

    private fun retiredRequest(code: Int) {
        val entered = CountDownLatch(1)
        val release = gate()
        val target = view()
        client { request ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            response(request, code, if (code == 200) png() else byteArrayOf())
        }.loadCoverInto(target, game())
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val newer = ColorDrawable(0xff112233.toInt())
        target.setTag(R.id.nova_artwork_request_key, "newer-game:poster")
        target.setImageDrawable(newer)
        // Leave the old Job alive: this specifically discriminates the request-key fence,
        // independently of cancellation hiding a late success or failure.
        release.countDown()
        finish(target)
        assertSame(newer, target.drawable)
    }

    @Test fun aRetiredRequestCannotPaintItsLateSuccessfulPoster() = retiredRequest(200)
    @Test fun aRetiredRequestCannotPaintItsLateNamedFailurePoster() = retiredRequest(404)

    @Test fun nonPosterPlaceholdersRemainUnchanged() {
        val api = client { error("non-poster without a manifest must not fetch") }
        for (kind in listOf("icon", "hero", "logo")) {
            val target = view()
            api.loadArtworkInto(target, game(), kind)
            assertNotNull(target.drawable)
            assertFalse(target.drawable is NovaTitleCardDrawable)
        }
    }

    private companion object {
        const val HOST = "poster-identity.invalid"
        const val PORT = 47984
    }
}

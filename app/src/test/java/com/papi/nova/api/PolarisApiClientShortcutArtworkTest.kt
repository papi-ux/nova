package com.papi.nova.api

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.shared.polaris.model.PolarisGame
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PolarisApiClientShortcutArtworkTest {
    private lateinit var context: Context
    private lateinit var cache: PolarisArtworkDiskCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cache = PolarisArtworkDiskCache(context, HOST, PORT)
        cache.clear()
    }

    @After
    fun tearDown() {
        cache.clear()
    }

    @Test
    fun loadShortcutIconReturnsExactValidatedManifestIconFromDiskCache() = runBlocking {
        val png = pngBytes(64, 64)
        assertNotNull(cache.store(GAME_ID, PolarisGame.ARTWORK_KIND_ICON, REVISION, png, "image/png"))

        val bitmap = PolarisApiClient(context, HOST, PORT).loadShortcutIcon(gameWithIcon())

        assertNotNull(bitmap)
        val loaded = requireNotNull(bitmap)
        assertEquals(64, loaded.width)
        assertEquals(64, loaded.height)
        loaded.recycle()
    }

    @Test
    fun loadShortcutIconReturnsNullWhenTrustedIconIsUnavailable() = runBlocking {
        val game = PolarisGame(
            id = GAME_ID,
            name = "Phasmophobia",
            artwork = PolarisGame.ArtworkManifest(
                revision = REVISION,
                assets = PolarisGame.ArtworkAssets(icon = null),
            ),
        )

        assertNull(PolarisApiClient(context, HOST, PORT).loadShortcutIcon(game))
    }

    private fun gameWithIcon() = PolarisGame(
        id = GAME_ID,
        name = "Phasmophobia",
        artwork = PolarisGame.ArtworkManifest(
            revision = REVISION,
            assets = PolarisGame.ArtworkAssets(
                icon = PolarisGame.ArtworkAsset(
                    url = "/polaris/v1/games/$GAME_ID/artwork/icon",
                    cached = true,
                ),
            ),
        ),
    )

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }.also { bitmap.recycle() }
    }

    private companion object {
        const val HOST = "offline-shortcut-icon"
        const val PORT = 47984
        const val GAME_ID = "game-shortcut-icon"
        const val REVISION = "rev-shortcut-icon"
    }
}

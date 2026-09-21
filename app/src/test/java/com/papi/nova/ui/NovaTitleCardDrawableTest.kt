package com.papi.nova.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.shared.polaris.model.PolarisGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A title the host has no artwork for used to be the same blank tile as every other one. Its
 * poster is now the placeholder with its name on it, drawn as the image itself so the poster
 * card keeps its rule of putting no text over artwork.
 *
 * This runs in Robolectric's ordinary graphics mode on purpose. A first version asked for native
 * graphics to compare pixels. It passed alone, and in the full suite it ended the test JVM with
 * exit 1 at its first method, which Gradle reports as a failed task while every class that sorts
 * after it is silently never run. What the card looks like is proven on a device instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaTitleCardDrawableTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun drawn(title: String, width: Int = 200, height: Int = 300): List<String> {
        val canvas = Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        NovaTitleCardDrawable(context, title).apply { setBounds(0, 0, width, height) }.draw(canvas)
        val shadow = shadowOf(canvas)
        return (0 until shadow.textHistoryCount).map { shadow.getDrawnTextEvent(it).text.toString() }
    }

    @Test fun writesTheGamesNameAndNothingForAGameWithoutOne() {
        assertTrue("the name is drawn on the card", drawn("Vulkan Cube").joinToString("").contains("Vulkan Cube"))
        assertEquals("a blank name draws the placeholder alone", emptyList<String>(), drawn("  "))
        assertEquals(emptyList<String>(), drawn(""))
    }

    @Test fun survivesTheNamesAndSizesALibraryReallyHas() {
        listOf("A", "The Elder Scrolls V: Skyrim Special Edition Anniversary Upgrade Bundle", "星のカービィ")
            .forEach { drawn(it) }
        drawn("Quake", width = 1, height = 1)
        // A view that has not been laid out yet draws with empty bounds.
        NovaTitleCardDrawable(context, "Quake").draw(Canvas(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)))
    }

    @Test fun isThePosterNovaSetsForATitleTheHostHasNoArtworkFor() {
        val view = ImageView(context)
        val client = PolarisApiClient(context, "127.0.0.1", 47984)
        val bare = PolarisGame(id = "space.alex.id.2", name = "GL Gears", source = "lutris",
            space = PolarisGame.SpaceContext("alex", "Alex", "id.2"))
        client.loadArtworkInto(view, bare, PolarisGame.ARTWORK_KIND_POSTER)
        assertEquals("GL Gears", (view.drawable as NovaTitleCardDrawable).title)
        assertEquals("title-card:space.alex.id.2:GL Gears", view.getTag(R.id.nova_artwork_request_key))
        // Only a poster becomes a title card. A title that has a cover is loaded as it always was,
        // which PolarisApiClientParsingTest pins through hostHasNoArtworkFor; asking the loader
        // for one here would start a real fetch.
        client.loadArtworkInto(view, bare, PolarisGame.ARTWORK_KIND_HERO)
        assertFalse(view.drawable is NovaTitleCardDrawable)
        assertFalse(PolarisApiClient.hostHasNoArtworkFor(
            bare.copy(coverUrl = "/polaris/v1/games/space.alex.id.2/space-artwork/poster")))
    }
}

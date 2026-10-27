package com.papi.nova.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a game's page hands its stream: one slot, one host and app, a bounded lifetime, a bounded
 * copy, and only real artwork reported by the page's views.
 *
 * UNRUN: written for Nova #385 and not executed in the authoring session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaLaunchArtworkTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun artwork() = NovaLaunchArtwork(
        NovaLaunchArtwork.Kind.POSTER,
        Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888),
        0xFF000096.toInt(),
    )

    @Before
    fun emptyTheSlot() {
        NovaLaunchArtworkHandoff.offer(null, 0, null, 0)
    }

    @Test
    fun theStreamTakesTheOfferedArtworkOnceForItsOwnHostAndApp() {
        val offered = artwork()
        NovaLaunchArtworkHandoff.offer("host-a", 42, offered, nowMs = 1_000)
        assertSame(offered, NovaLaunchArtworkHandoff.take("host-a", 42, nowMs = 1_500))
        assertNull("a second show, or a reconnect, finds nothing", NovaLaunchArtworkHandoff.take("host-a", 42, nowMs = 1_600))
    }

    @Test
    fun anotherHostOrAppGetsNothingAndStillEmptiesTheSlot() {
        NovaLaunchArtworkHandoff.offer("host-a", 42, artwork(), nowMs = 1_000)
        assertNull(NovaLaunchArtworkHandoff.take("host-b", 42, nowMs = 1_100))
        assertNull("a mismatch empties the slot", NovaLaunchArtworkHandoff.take("host-a", 42, nowMs = 1_200))

        NovaLaunchArtworkHandoff.offer("host-a", 42, artwork(), nowMs = 1_000)
        assertNull(NovaLaunchArtworkHandoff.take("host-a", 7, nowMs = 1_100))
        assertNull(NovaLaunchArtworkHandoff.take("host-a", 42, nowMs = 1_200))
    }

    @Test
    fun artworkPastItsLifetimeIsNotCarried() {
        NovaLaunchArtworkHandoff.offer("host-a", 42, artwork(), nowMs = 1_000)
        assertNotNull(NovaLaunchArtworkHandoff.take("host-a", 42, nowMs = 1_000 + NovaLaunchArtworkHandoff.LIFETIME_MS))

        NovaLaunchArtworkHandoff.offer("host-a", 42, artwork(), nowMs = 1_000)
        assertNull(NovaLaunchArtworkHandoff.take("host-a", 42, nowMs = 1_001 + NovaLaunchArtworkHandoff.LIFETIME_MS))
    }

    @Test
    fun anIncompleteOfferEmptiesTheSlot() {
        NovaLaunchArtworkHandoff.offer("host-a", 42, artwork(), nowMs = 1_000)
        NovaLaunchArtworkHandoff.offer("host-a", 42, null, nowMs = 1_100)
        assertNull("a page with no artwork shown replaces an earlier offer", NovaLaunchArtworkHandoff.take("host-a", 42, nowMs = 1_200))

        NovaLaunchArtworkHandoff.offer(null, 42, artwork(), nowMs = 1_000)
        assertNull(NovaLaunchArtworkHandoff.take(null, 42, nowMs = 1_100))
    }

    @Test
    fun theCarriedCopyNeverPinsAFullSizeHero() {
        val hero = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val copy = NovaLaunchArtworkHandoff.carriedCopy(hero)!!
        assertEquals(NovaLaunchArtworkHandoff.MAX_EDGE, copy.width)
        assertEquals(360, copy.height)

        val poster = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        assertSame("already small enough: carried as is", poster, NovaLaunchArtworkHandoff.carriedCopy(poster))
    }

    @Test
    fun anArtworkViewReportsRealArtworkButNotTitleCardsOrBundledResources() {
        val view = NovaArtworkImageView(context)
        val reported = mutableListOf<Bitmap?>()
        view.onArtwork = { reported += it }

        // How PolarisApiClient delivers artwork: setImageBitmap reaches setImageDrawable.
        val artwork = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        view.setImageBitmap(artwork)
        assertSame(artwork, reported.last())

        view.setImageDrawable(NovaTitleCardDrawable(context, "Celeste"))
        assertNull("a title card is not artwork", reported.last())

        // Big Picture's bundled Steam mark arrives by resource, which a framework ImageView
        // does not route through setImageDrawable.
        val before = reported.size
        view.setImageResource(R.drawable.nova_steam_big_picture)
        assertEquals(before, reported.size)
    }
}

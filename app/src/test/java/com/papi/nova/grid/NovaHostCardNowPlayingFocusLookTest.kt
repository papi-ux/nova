package com.papi.nova.grid

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.api.PolarisSessionStatus
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.NovaHostNowPlaying
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.novaHostNowPlaying
import com.papi.nova.ui.panel.NovaViewBridge
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The now-playing pill's label and its fill have to agree on whether the card has focus (#469). On
 * the RP6 and the Shield the label went to its on-accent colour while the pill still drew its
 * resting tile, dark words on a dark tile, for as long as the card held focus.
 *
 * The card is rebound whenever a host is polled. Each bind gave the pill a new background, and a
 * view hands a new background its state before it becomes the drawable's callback: a pill already
 * selected started the new background's fade from the tile to the accent with nobody to run it, so
 * the accent stayed at nothing over a tile that never left.
 *
 * What this reads is what the background holds, its current fill and how opaque it is, on a card
 * in a real window with the main looper run. It cannot see pixels: this runner draws none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostCardNowPlayingFocusLookTest {
    private val context: Context
        get() = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme)

    private val cert = org.mockito.Mockito.mock(java.security.cert.X509Certificate::class.java)

    private fun playing() =
        novaHostNowPlaying(
            PolarisSessionStatus(state = "paused", ownedByClient = true, shutdownRequested = false, gameId = 7, game = "Hades", sessionToken = "session-A"),
        )!!.copy(host = NovaHostNowPlaying.Host("host-1", "127.0.0.1", 47984, cert))

    private fun details() = ComputerDetails().apply {
        uuid = "host-1"
        name = "PC"
        state = ComputerDetails.State.ONLINE
        pairState = PairingManager.PairState.PAIRED
        serverCert = cert
        activeAddress = ComputerDetails.AddressTuple("127.0.0.1", 47989)
        httpsPort = 47984
        runningGameId = 7
        currentGameOwnedByClient = true
    }

    /** A Hosts card showing the pill, in a window, so its drawables have a clock to fade by. */
    private inner class Card {
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        private val holder: GenericGridAdapter.ViewHolder
        val row: View get() = holder.itemView
        val pill: View get() = row.findViewById(R.id.host_now_playing)
        val words: TextView get() = row.findViewById(R.id.host_now_playing_text)

        /** Somewhere else for focus to be, so the card has it only when a test gives it. */
        private val elsewhere = View(context).apply { isFocusableInTouchMode = true }

        init {
            adapter.setItems(listOf(PcViewModel.ComputerObject(details())))
            adapter.setNowPlaying(mapOf("host-1" to playing()))
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val parent = FrameLayout(context)
            activity.setContentView(parent)
            holder = adapter.onCreateViewHolder(parent, 0)
            parent.addView(elsewhere, 1, 1)
            parent.addView(row)
            row.isFocusableInTouchMode = true
            leave()
            bind()
        }

        fun focus() {
            assertTrue(row.requestFocus())
            settle()
            assertTrue(row.isFocused)
        }

        fun leave() {
            assertTrue(elsewhere.requestFocus())
            settle()
            assertTrue(!row.isFocused)
        }

        /** As a host poll rebinds the card, then long enough for any fade to finish. */
        fun bind() {
            adapter.onBindViewHolder(holder, 0)
            settle()
        }

        fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

        /** The fill the pill's background is showing, and how opaque: a fade that never ran leaves it at nothing. */
        fun shown(): Pair<Int, Int> {
            val current = (pill.background as StateListDrawable).current as GradientDrawable
            return requireNotNull(current.color).defaultColor to current.alpha
        }
    }

    private val accent get() = NovaThemeManager.getAccentColor(context)
    private val labels get() = NovaViewBridge.primaryButtonText(context)
    private val onAccent get() = labels.getColorForState(intArrayOf(android.R.attr.state_selected), 0)
    private val atRest get() = labels.defaultColor

    private fun assertFocusedPair(card: Card, whenWhat: String) {
        assertEquals("$whenWhat: the label is in its on-accent colour", onAccent, card.words.currentTextColor)
        assertEquals("$whenWhat: the accent is under it, whole", accent to 255, card.shown())
    }

    private fun assertRestingPair(card: Card, whenWhat: String) {
        assertEquals("$whenWhat: the label is in its resting colour", atRest, card.words.currentTextColor)
        val (fill, alpha) = card.shown()
        assertNotEquals("$whenWhat: the tile, not the accent", accent, fill)
        assertEquals("$whenWhat: the tile is whole", 255, alpha)
    }

    @Test fun theLabelsTwoColoursAreNotTheSame() {
        assertNotEquals("or the pair below would prove nothing", onAccent, atRest)
    }

    @Test fun atRestThePillIsItsTileUnderItsRestingLabel() {
        val card = Card()
        assertRestingPair(card, "bound without focus")
        card.bind()
        assertRestingPair(card, "bound again without focus")
    }

    @Test fun focusFillsThePillUnderItsOnAccentLabel() {
        val card = Card()
        card.focus()
        assertTrue(card.pill.isSelected)
        assertFocusedPair(card, "the card took focus")
    }

    /** The RP6 and the Shield: the card holds focus while a poll rebinds it. */
    @Test fun aRebindWhileTheCardHasFocusKeepsTheFillUnderTheLabel() {
        val card = Card()
        card.focus()
        card.bind()
        assertTrue(card.pill.isSelected)
        assertFocusedPair(card, "rebound with focus")
        card.bind()
        assertFocusedPair(card, "rebound with focus again")
    }

    @Test fun focusLeavingPutsTheTileBackUnderTheRestingLabel() {
        val card = Card()
        card.focus()
        card.bind()
        card.leave()
        assertRestingPair(card, "focus left")
        card.bind()
        assertRestingPair(card, "rebound after focus left")
    }
}

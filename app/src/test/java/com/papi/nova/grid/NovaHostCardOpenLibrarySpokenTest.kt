package com.papi.nova.grid

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.NovaThemeManager
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a host card's Open Library tells a screen reader while the card holds focus (#470). On the
 * Shield TalkBack said "Open Library" about every 1.6 s until focus moved: every host poll rebinds
 * the card, the bind dropped the button's selection and set it again, and a view that becomes
 * selected sends TYPE_VIEW_SELECTED with its text. The now-playing pill, never dropped, was not
 * repeated.
 *
 * What this reads is the events the button itself sends, through its accessibility delegate, on a
 * card in a real window with the main looper run. Whether TalkBack speaks a given event is
 * TalkBack's: the Shield walk shows that, not this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovaHostCardOpenLibrarySpokenTest {
    private val context: Context
        get() = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme)

    private val cert = org.mockito.Mockito.mock(java.security.cert.X509Certificate::class.java)

    /** Paired, online, nothing running, its library ready: the card offers Open Library. */
    private fun details() = ComputerDetails().apply {
        uuid = "host-1"
        name = "PC"
        state = ComputerDetails.State.ONLINE
        pairState = PairingManager.PairState.PAIRED
        serverCert = cert
        activeAddress = ComputerDetails.AddressTuple("127.0.0.1", 47989)
        httpsPort = 47984
        runningGameId = 0
        libraryState = ComputerDetails.LibraryState.AVAILABLE
    }

    /** Each selection the button announces, as TalkBack would be handed it. */
    private class Selections : View.AccessibilityDelegate() {
        var count = 0
        override fun sendAccessibilityEvent(host: View, eventType: Int) {
            if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) count++
            super.sendAccessibilityEvent(host, eventType)
        }
    }

    /** A Hosts card in a window, so its drawables have a callback and a clock to fade by. */
    private inner class Card {
        val adapter = PcGridAdapter(context, PreferenceConfiguration())
        private val holder: GenericGridAdapter.ViewHolder
        val row: View get() = holder.itemView
        val primary: TextView get() = row.findViewById(R.id.primary_action_text)
        val selections = Selections()

        /** Somewhere else for focus to be, so the card has it only when a test gives it. */
        private val elsewhere = View(context).apply { isFocusableInTouchMode = true }

        init {
            adapter.setItems(listOf(PcViewModel.ComputerObject(details())))
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val parent = FrameLayout(context)
            activity.setContentView(parent)
            holder = adapter.onCreateViewHolder(parent, 0)
            parent.addView(elsewhere, 1, 1)
            parent.addView(row)
            row.isFocusableInTouchMode = true
            leave()
            bind()
            assertEquals(context.getString(R.string.pcview_card_action_open_library), primary.text.toString())
            primary.accessibilityDelegate = selections
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

        /** The fill the button's background is showing, and how opaque. */
        fun shown(): Pair<Int, Int> {
            val current = (primary.background as StateListDrawable).current as GradientDrawable
            return requireNotNull(current.color).defaultColor to current.alpha
        }
    }

    private val accent get() = NovaThemeManager.getAccentColor(context)

    /** The Shield: the card holds focus through polls. */
    @Test fun pollsWhileTheCardHasFocusAnnounceNoNewSelection() {
        val card = Card()
        card.focus()
        assertTrue(card.primary.isSelected)
        val onArrival = card.selections.count
        repeat(3) { card.bind() }
        assertEquals("selections announced by three polls", 0, card.selections.count - onArrival)
        assertTrue("it stays selected, filled for A", card.primary.isSelected)
    }

    /** Not dropping the selection must not leave a new background stopped mid-fade, as the pill was (#469). */
    @Test fun pollsWhileTheCardHasFocusKeepTheAccentWholeUnderOpenLibrary() {
        val card = Card()
        card.focus()
        repeat(2) { card.bind() }
        assertEquals(accent to 255, card.shown())
    }

    @Test fun focusArrivingSelectsOpenLibraryOnceAndLeavingDeselectsIt() {
        val card = Card()
        card.focus()
        assertEquals("focus arriving", 1, card.selections.count)
        card.leave()
        assertTrue(!card.primary.isSelected)
        card.bind()
        assertTrue("a poll after focus left selects nothing", !card.primary.isSelected)
        assertEquals(1, card.selections.count)
    }
}

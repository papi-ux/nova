package com.papi.nova.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.grid.GenericGridAdapter
import com.papi.nova.grid.PcGridAdapter
import com.papi.nova.grid.novaHostBadges
import com.papi.nova.network.HostConnectionEvidence
import com.papi.nova.network.HostConnectionEvidence.Reason
import com.papi.nova.network.HostConnectionEvidence.Status
import com.papi.nova.network.HostConnectionEvidence.Transport
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.panel.setPanelContent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.nvstream.http.ComputerDetails.LibraryState

/**
 * How this device reaches a host, where people look (#415): a chip on the Hosts card and in Game
 * Detail, driven only by what the host confirmed. It was three levels down, in Play Setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w800dp-h440dp")
class NovaHostConnectionChipComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = rule.activity

    private fun confirmed(transport: Transport) = HostConnectionEvidence(Status.TAILSCALE, transport, Reason.OBSERVED)

    @Test fun onlyWhatTheHostConfirmedIsAChip() {
        assertEquals("Tailscale · direct", NovaNetworkProfileCopy.hostConnectionChip(context, confirmed(Transport.DIRECT)))
        assertEquals("Tailscale · DERP relay", NovaNetworkProfileCopy.hostConnectionChip(context, confirmed(Transport.DERP)))
        assertEquals("Tailscale · peer relay", NovaNetworkProfileCopy.hostConnectionChip(context, confirmed(Transport.PEER_RELAY)))
        assertEquals("Tailscale · route unknown", NovaNetworkProfileCopy.hostConnectionChip(context, confirmed(Transport.UNKNOWN)))

        assertNull("not asked yet", NovaNetworkProfileCopy.hostConnectionChip(context, null))
        listOf(Reason.PEER_NOT_CONFIRMED, Reason.STALE, Reason.HOST_UNSUPPORTED, Reason.REQUEST_FAILED).forEach {
            assertNull("unknown for $it", NovaNetworkProfileCopy.hostConnectionChip(context, HostConnectionEvidence.unknown(it)))
        }
        assertNull("the host saying what it is not doesn't say what it is",
            NovaNetworkProfileCopy.hostConnectionChip(context, HostConnectionEvidence(Status.NOT_TAILSCALE, Transport.UNKNOWN, Reason.PEER_NOT_CONFIRMED)))
    }

    @Test fun aChipNeverComesFromThisDevicesOwnNetwork() {
        val copy = File("src/main/java/com/papi/nova/ui/NovaNetworkProfileCopy.kt").readText()
        val chip = copy.substringAfter("fun hostConnectionChip(").substringBefore("/** Why it is unknown")
        listOf("vpn", "VPN", "NetworkSnapshot", "NetworkCapabilities", "address").forEach { assertFalse(it, chip.contains(it)) }
    }

    private fun overview(chip: String?) {
        val uiState = NovaGameDetailUiState.from(
            game = PolarisGame(id = "desktop", name = "Desktop", source = "manual"),
            defaultToVirtualDisplay = false,
            clientSettings = PolarisClientSettings(),
            profilePreference = "auto",
        )
        rule.setPanelContent {
            NovaGameDetailContentUnderTest(uiState = uiState, hostConnectionChip = chip)
        }
    }

    @Test fun gameDetailShowsAConfirmedConnectionWithoutTakingFocus() {
        overview("Tailscale · direct")
        val chip = rule.onNodeWithTag(NOVA_GAME_DETAIL_CONNECTION_CHIP_TAG)
        chip.assertIsDisplayed()
        // At a handheld's height the chip must not push what it sits above off the page.
        rule.onNodeWithTag("nova-game-detail-status").assertIsDisplayed()
        rule.onNodeWithTag("nova-game-detail-primary").assertIsDisplayed()
        assertFalse("not a stop on the D-pad", chip.fetchSemanticsNode().config.contains(SemanticsProperties.Focused))
        assertEquals(
            listOf(context.getString(R.string.nova_host_connection_chip_description, "Tailscale · direct")),
            chip.fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        assertFalse("read, never pressed", chip.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
    }

    @Test fun gameDetailShowsNothingWithoutAConfirmedConnection() {
        overview(null)
        rule.onNodeWithTag(NOVA_GAME_DETAIL_CONNECTION_CHIP_TAG).assertDoesNotExist()
    }

    private fun card(chips: Map<String, String>, state: ComputerDetails.State = ComputerDetails.State.ONLINE): TextView {
        val themed = ContextThemeWrapper(context, R.style.AppTheme)
        val adapter = PcGridAdapter(themed, PreferenceConfiguration())
        adapter.setHostConnections(chips)
        val computer = PcViewModel.ComputerObject(ComputerDetails().apply {
            uuid = "chip-host"; name = "pc-papi"; this.state = state
            pairState = PairingManager.PairState.PAIRED
            serverCert = org.mockito.Mockito.mock(java.security.cert.X509Certificate::class.java)
        })
        adapter.setItems(listOf(computer))
        val list = RecyclerView(themed).apply { layoutManager = LinearLayoutManager(themed) }
        val holder = adapter.onCreateViewHolder(list, adapter.getItemViewType(0))
        @Suppress("UNCHECKED_CAST")
        (adapter as RecyclerView.Adapter<GenericGridAdapter.ViewHolder>).onBindViewHolder(holder, 0)
        return holder.itemView.findViewById(R.id.host_badge_connection)
    }

    @Test fun aHostsCardWearsItsConfirmedConnection() {
        val chip = card(mapOf("chip-host" to "Tailscale · DERP relay"))
        assertEquals(android.view.View.VISIBLE, chip.visibility)
        assertEquals("Tailscale · DERP relay", chip.text.toString())
        assertEquals(context.getString(R.string.nova_host_connection_chip_description, "Tailscale · DERP relay"), chip.contentDescription)
    }

    @Test fun aHostsCardHidesTheChipWithoutAnAnswerForItOrOnceOffline() {
        assertEquals(android.view.View.GONE, card(emptyMap()).visibility)
        assertEquals("another host's answer", android.view.View.GONE, card(mapOf("other" to "Tailscale · direct")).visibility)
        assertEquals(android.view.View.GONE, card(mapOf("chip-host" to "Tailscale · direct"), ComputerDetails.State.OFFLINE).visibility)
    }

    @Test fun theBadgesCarryTheConnectionOnlyForAPairedHostThatAnswers() {
        val on = novaHostBadges(online = true, paired = true, library = LibraryState.UNKNOWN, spacesAvailable = false, connection = "Tailscale · direct")
        assertEquals("Tailscale · direct", on.connection)
        assertNull(novaHostBadges(online = false, paired = true, library = LibraryState.AVAILABLE, spacesAvailable = false, connection = "x").connection)
        assertNull(novaHostBadges(online = true, paired = false, library = LibraryState.AVAILABLE, spacesAvailable = false, connection = "x").connection)
    }

    @Test fun theConnectionTakesTheBadgeStyleOnItsOwnLine() {
        val layout = File("src/main/res/layout/pc_grid_item.xml").readText()
        val badge = layout.substringAfter("android:id=\"@+id/host_badge_connection\"").substringBefore("/>")
        assertTrue(badge, badge.contains("style=\"@style/NovaHostBadge\""))
        assertTrue("outside the other badges' row", layout.indexOf("host_badge_connection") > layout.indexOf("</LinearLayout>", layout.indexOf("host_badge_spaces")))
    }
}

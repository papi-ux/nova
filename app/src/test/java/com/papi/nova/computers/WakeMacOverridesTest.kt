package com.papi.nova.computers

import android.app.Application
import android.content.ComponentName
import android.content.Context
import com.papi.nova.discovery.DiscoveryService
import org.mockito.Mockito.mock
import org.robolectric.Shadows
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.wol.WakeOnLanSender
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeMacOverridesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manual = "02:11:22:33:44:55"
    private val discovered = "04:AA:BB:CC:DD:EE"

    @Before fun reset() {
        context.getSharedPreferences("wake_mac_overrides", Context.MODE_PRIVATE).edit().clear().commit()
        for (db in listOf("computers.db", "computers2.db", "computers3.db", "computers4.db")) context.deleteDatabase(db)
    }

    private fun host() = ComputerDetails().apply {
        uuid = "test-host"
        name = "Test host"
        macAddress = discovered
        activeAddress = ComputerDetails.AddressTuple("example.test", 47989)
    }

    @Test fun overrideSurvivesDatabaseRefreshAndRestartAndClearRestoresDiscoveredAddress() {
        val first = WakeMacOverrides(context)
        assertTrue(first.save("test-host", "0211.2233.4455"))
        val database = ComputerDatabaseManager(context)
        assertTrue(database.updateComputer(host()))
        val loaded = database.getComputerByUUID("test-host")!!
        assertEquals(discovered, loaded.macAddress)
        assertEquals(manual, loaded.wakeMacAddress)
        assertEquals(manual, ComputerDetails(loaded).wakeMacAddress)
        // A freshly polled host has no knowledge of this client-owned setting.
        val poll = host().apply { macAddress = "06:AA:BB:CC:DD:EE" }
        loaded.updateFromVerifiedPoll(poll)
        assertEquals(manual, loaded.wakeMacAddress)
        assertTrue(database.updateComputer(poll))
        database.close()
        val restarted = ComputerDatabaseManager(context)
        try {
            assertEquals(manual, restarted.getComputerByUUID("test-host")!!.wakeMacAddress)
            assertTrue(WakeMacOverrides(context).save("test-host", ""))
            assertEquals(poll.macAddress, restarted.getComputerByUUID("test-host")!!.wakeMacAddress)
        } finally { restarted.close() }
    }

    @Test fun allZeroPollFormatsPreserveLastDiscoveredAddressAndManualOverride() {
        val details = host().apply { manualWakeMacAddress = manual }
        for (zero in listOf("00:00:00:00:00:00", "00-00-00-00-00-00", "0000.0000.0000", "000000000000", "", "bad")) {
            details.updateFromVerifiedPoll(host().apply { macAddress = zero })
            assertEquals(discovered, details.macAddress)
            assertEquals(manual, details.wakeMacAddress)
        }
        details.manualWakeMacAddress = null
        assertEquals(discovered, details.wakeMacAddress)
    }

    @Test fun invalidInputNeverOverwritesSavedAddressAndHostsRemainIndependent() {
        val store = WakeMacOverrides(context)
        assertTrue(store.save("test-host", manual))
        for (invalid in listOf("no", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "01:00:5E:00:00:01", "02:11:22:33:44:GG")) {
            assertFalse(invalid, store.save("test-host", invalid))
            assertEquals(manual, store.get("test-host"))
        }
        assertNull(store.get("another-host"))
        assertFalse(store.save("", manual))
    }

    @Test fun deletingHostRemovesOverrideEvenIfOldDiscoveryMetadataIsWrittenLater() {
        val store = WakeMacOverrides(context)
        val db = ComputerDatabaseManager(context)
        try {
            val details = host()
            assertTrue(db.updateComputer(details))
            assertTrue(store.save(details.uuid, manual))
            db.deleteComputer(details)
            assertNull(store.get(details.uuid))
            // A stale database write cannot recreate a separately stored override.
            assertTrue(db.updateComputer(details))
            assertEquals(discovered, db.getComputerByUUID(details.uuid)!!.wakeMacAddress)
        } finally { db.close() }
    }

    @Test fun serviceOnlyEditsTheCurrentSavedHostAndRemovalClearsItsPreference() {
        val details = host()
        val database = ComputerDatabaseManager(context)
        try { assertTrue(database.updateComputer(details)) } finally { database.close() }
        Shadows.shadowOf(context as Application).setComponentNameAndServiceForBindService(
            ComponentName(context, DiscoveryService::class.java), mock(DiscoveryService.DiscoveryBinder::class.java),
        )
        val controller = Robolectric.buildService(ComputerManagerService::class.java).create()
        val service = controller.get()
        val binder = service.onBind(null) as ComputerManagerService.ComputerManagerBinder
        try {
            assertTrue(binder.setWakeMacAddress(details.uuid, manual))
            assertEquals(manual, binder.getComputer(details.uuid)!!.wakeMacAddress)
            assertTrue(binder.setWakeMacAddress(details.uuid, ""))
            assertEquals(discovered, binder.getComputer(details.uuid)!!.wakeMacAddress)
            assertTrue(binder.setWakeMacAddress(details.uuid, manual))
            assertTrue(binder.removeComputer(details))
            assertFalse(binder.setWakeMacAddress(details.uuid, manual))
            assertNull(WakeMacOverrides(context).get(details.uuid))
        } finally { controller.destroy() }
    }

    @Test fun magicPacketUsesOverrideAndClearingItReturnsToDiscoveredAddress() {
        val details = host().apply { manualWakeMacAddress = manual }
        val packet = WakeOnLanSender.createWolPayload(details)
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        val bytes = byteArrayOf(2, 17, 34, 51, 68, 85)
        repeat(16) { assertArrayEquals(bytes, packet.copyOfRange(6 + it * 6, 12 + it * 6)) }
        details.manualWakeMacAddress = null
        val copied = ComputerDetails().apply { update(details) }
        assertEquals(discovered, copied.wakeMacAddress)
        assertArrayEquals(byteArrayOf(4, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte()),
            WakeOnLanSender.createWolPayload(copied).copyOfRange(6, 12))
    }
}

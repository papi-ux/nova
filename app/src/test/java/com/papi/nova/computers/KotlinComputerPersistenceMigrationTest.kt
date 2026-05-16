package com.papi.nova.computers

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvHTTP
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class KotlinComputerPersistenceMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteFile("uniqueid")
        context.deleteDatabase("computers.db")
        context.deleteDatabase("computers2.db")
        context.deleteDatabase("computers3.db")
        context.deleteDatabase("computers4.db")
    }

    @Test
    fun computerPersistenceClassesAreKotlinSources() {
        val paths = arrayOf(
            "src/main/java/com/papi/nova/computers/IdentityManager",
            "src/main/java/com/papi/nova/computers/ComputerDatabaseManager",
            "src/main/java/com/papi/nova/computers/LegacyDatabaseReader",
            "src/main/java/com/papi/nova/computers/LegacyDatabaseReader2",
            "src/main/java/com/papi/nova/computers/LegacyDatabaseReader3"
        )

        for (path in paths) {
            assertFalse("$path should no longer be a Java source", File("$path.java").exists())
            assertTrue("$path should be migrated to Kotlin", File("$path.kt").exists())
        }
    }

    @Test
    fun computerPersistenceClassesKeepJavaCompatibleApis() {
        IdentityManager::class.java.getConstructor(Context::class.java)
        assertEquals(String::class.java, IdentityManager::class.java.getMethod("getUniqueId").returnType)

        ComputerDatabaseManager::class.java.getConstructor(Context::class.java)
        ComputerDatabaseManager::class.java.getMethod("close")
        ComputerDatabaseManager::class.java.getMethod("deleteComputer", ComputerDetails::class.java)
        ComputerDatabaseManager::class.java.getMethod("tupleToJson", ComputerDetails.AddressTuple::class.java)
        ComputerDatabaseManager::class.java.getMethod("tupleFromJson", JSONObject::class.java, String::class.java)
        ComputerDatabaseManager::class.java.getMethod("updateComputer", ComputerDetails::class.java)
        ComputerDatabaseManager::class.java.getMethod("getAllComputers")
        ComputerDatabaseManager::class.java.getMethod("getComputerByName", String::class.java)
        ComputerDatabaseManager::class.java.getMethod("getComputerByUUID", String::class.java)

        LegacyDatabaseReader::class.java.getMethod("migrateAllComputers", Context::class.java)
        LegacyDatabaseReader2::class.java.getMethod("getAllComputers", SQLiteDatabase::class.java)
        LegacyDatabaseReader2::class.java.getMethod("migrateAllComputers", Context::class.java)
        LegacyDatabaseReader3::class.java.getMethod("getAllComputers", SQLiteDatabase::class.java)
        LegacyDatabaseReader3::class.java.getMethod("migrateAllComputers", Context::class.java)
    }

    @Test
    fun identityManagerGeneratesAndPersistsHexUniqueId() {
        val first = IdentityManager(context)
        val second = IdentityManager(context)

        assertTrue(first.getUniqueId().matches(Regex("[0-9a-f]{16}")))
        assertEquals(first.getUniqueId(), second.getUniqueId())
    }

    @Test
    fun tupleJsonRoundTripsNullableAddressTuples() {
        val tuple = ComputerDetails.AddressTuple("192.168.1.9", 47989)
        val wrapper = JSONObject()
        wrapper.put("manual", ComputerDatabaseManager.tupleToJson(tuple))

        val restored = ComputerDatabaseManager.tupleFromJson(wrapper, "manual")

        assertNotNull(restored)
        assertEquals("192.168.1.9", restored!!.address)
        assertEquals(47989, restored.port)
        assertNull(ComputerDatabaseManager.tupleToJson(null))
        assertNull(ComputerDatabaseManager.tupleFromJson(wrapper, "missing"))
    }

    @Test
    fun computerDatabasePersistsUpdatesReadsAndDeletesComputers() {
        val manager = ComputerDatabaseManager(context)
        try {
            val details = ComputerDetails()
            details.uuid = "uuid-1"
            details.name = "Retroid Host"
            details.localAddress = ComputerDetails.AddressTuple("192.168.1.2", 47984)
            details.remoteAddress = ComputerDetails.AddressTuple("wan.example.test", 48010)
            details.manualAddress = ComputerDetails.AddressTuple("manual.example.test", 48011)
            details.ipv6Address = ComputerDetails.AddressTuple("2001:db8::4", 48012)
            details.macAddress = "AA:BB:CC:DD:EE:FF"

            assertTrue(manager.updateComputer(details))

            val byUuid = manager.getComputerByUUID("uuid-1")
            assertNotNull(byUuid)
            byUuid!!
            assertEquals("Retroid Host", byUuid.name)
            assertEquals("192.168.1.2", byUuid.localAddress!!.address)
            assertEquals("wan.example.test", byUuid.remoteAddress!!.address)
            assertEquals(48010, byUuid.externalPort)
            assertEquals("manual.example.test", byUuid.manualAddress!!.address)
            assertEquals("2001:db8::4", byUuid.ipv6Address!!.address)
            assertEquals("AA:BB:CC:DD:EE:FF", byUuid.macAddress)
            assertEquals(ComputerDetails.State.UNKNOWN, byUuid.state)

            val byName = manager.getComputerByName("Retroid Host")
            assertNotNull(byName)
            assertEquals("uuid-1", byName!!.uuid)
            assertEquals(1, manager.getAllComputers().size)

            manager.deleteComputer(details)
            assertNull(manager.getComputerByUUID("uuid-1"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun legacyDatabaseReader2MigratesRowsSkipsNullUuidAndDeletesOldDatabase() {
        val db = context.openOrCreateDatabase("computers2.db", 0, null)
        db.execSQL("CREATE TABLE Computers(UUID TEXT, ComputerName TEXT, LocalAddress TEXT, RemoteAddress TEXT, ManualAddress TEXT, MacAddress TEXT)")
        insertLegacy2Computer(db, "uuid-2", "Legacy Two", "10.0.0.2", "remote-two", "manual-two", "11:22:33:44:55:66")
        insertLegacy2Computer(db, null, "Broken", "10.0.0.3", "remote-broken", "manual-broken", "00:00:00:00:00:00")
        db.close()

        val migrated = LegacyDatabaseReader2.migrateAllComputers(context)

        assertEquals(1, migrated.size)
        val details = migrated[0]
        assertEquals("uuid-2", details.uuid)
        assertEquals("Legacy Two", details.name)
        assertEquals("10.0.0.2", details.localAddress!!.address)
        assertEquals(NvHTTP.DEFAULT_HTTP_PORT, details.localAddress!!.port)
        assertEquals("remote-two", details.remoteAddress!!.address)
        assertEquals("manual-two", details.manualAddress!!.address)
        assertEquals("11:22:33:44:55:66", details.macAddress)
        assertFalse(context.getDatabasePath("computers2.db").exists())
    }

    @Test
    fun legacyDatabaseReader3MigratesDelimitedAddressesAndDeletesOldDatabase() {
        val db = context.openOrCreateDatabase("computers3.db", 0, null)
        db.execSQL("CREATE TABLE Computers(UUID TEXT, ComputerName TEXT, Addresses TEXT, MacAddress TEXT, ServerCert BLOB)")
        val values = ContentValues()
        values.put("UUID", "uuid-3")
        values.put("ComputerName", "Legacy Three")
        values.put("Addresses", "local_47984;remote_48010;manual_48011;2001:db8::5_48012")
        values.put("MacAddress", "22:33:44:55:66:77")
        db.insert("Computers", null, values)
        db.close()

        val migrated = LegacyDatabaseReader3.migrateAllComputers(context)

        assertEquals(1, migrated.size)
        val details = migrated[0]
        assertEquals("uuid-3", details.uuid)
        assertEquals("Legacy Three", details.name)
        assertEquals("local", details.localAddress!!.address)
        assertEquals(47984, details.localAddress!!.port)
        assertEquals("remote", details.remoteAddress!!.address)
        assertEquals(48010, details.remoteAddress!!.port)
        assertEquals(48010, details.externalPort)
        assertEquals("manual", details.manualAddress!!.address)
        assertEquals("2001:db8::5", details.ipv6Address!!.address)
        assertEquals(48012, details.ipv6Address!!.port)
        assertEquals("22:33:44:55:66:77", details.macAddress)
        assertFalse(context.getDatabasePath("computers3.db").exists())
    }

    private fun insertLegacy2Computer(
        db: SQLiteDatabase,
        uuid: String?,
        name: String,
        local: String,
        remote: String,
        manual: String,
        macAddress: String
    ) {
        val values = ContentValues()
        values.put("UUID", uuid)
        values.put("ComputerName", name)
        values.put("LocalAddress", local)
        values.put("RemoteAddress", remote)
        values.put("ManualAddress", manual)
        values.put("MacAddress", macAddress)
        db.insert("Computers", null, values)
    }
}

package com.papi.nova.computers

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvHTTP
import java.io.File
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import org.json.JSONArray
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
            details.rememberAddress(ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 47989))
            details.rememberAddress(ComputerDetails.AddressTuple("100.100.20.30", 47989))
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
            assertTrue(byUuid.knownAddresses.contains(ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 47989)))
            assertTrue(byUuid.knownAddresses.contains(ComputerDetails.AddressTuple("100.100.20.30", 47989)))
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
    fun blankUuidCannotBePersisted() {
        val manager = ComputerDatabaseManager(context)
        try {
            val invalid = ComputerDetails().apply {
                name = "Missing Identity"
                rememberAddress(ComputerDetails.AddressTuple("route.example.test", 47989))
            }

            assertFalse(manager.updateComputer(invalid))
            assertEquals(emptyList<ComputerDetails>(), manager.getAllComputers())
        } finally {
            manager.close()
        }
    }

    @Test
    fun certificateEncodingFailurePreservesTheLastGoodRow() {
        val manager = ComputerDatabaseManager(context)
        try {
            val lastGood = ComputerDetails().apply {
                uuid = "uuid-cert-write"
                name = "Last Good"
                rememberAddress(ComputerDetails.AddressTuple("verified.example.test", 47989))
            }
            assertTrue(manager.updateComputer(lastGood))

            val invalidReplacement = ComputerDetails(lastGood).apply {
                name = "Must Not Replace"
                val certificate = org.mockito.Mockito.mock(X509Certificate::class.java)
                org.mockito.Mockito.`when`(certificate.encoded)
                    .thenThrow(CertificateEncodingException("synthetic encoding failure"))
                serverCert = certificate
            }

            assertFalse(manager.updateComputer(invalidReplacement))
            assertEquals("Last Good", manager.getComputerByUUID(lastGood.uuid)!!.name)
        } finally {
            manager.close()
        }
    }

    @Test
    fun currentSchemaPersistsOnlyExplicitRememberedHistory() {
        val manager = ComputerDatabaseManager(context)
        try {
            val details = ComputerDetails().apply {
                uuid = "uuid-verified-only"
                name = "Verified Only"
                localAddress = ComputerDetails.AddressTuple("advertised-lan.example.test", 47989)
                remoteAddress = ComputerDetails.AddressTuple("advertised-wan.example.test", 47989)
                rememberAddress(ComputerDetails.AddressTuple("verified.example.test", 47989))
            }

            assertTrue(manager.updateComputer(details))

            val restored = manager.getComputerByUUID(details.uuid)
            assertNotNull(restored)
            assertEquals(
                listOf(ComputerDetails.AddressTuple("verified.example.test", 47989)),
                restored!!.knownAddresses
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun currentDatabaseWithoutKnownAddressesSeedsLegacyRoutes() {
        ComputerDatabaseManager(context).close()
        val db = context.openOrCreateDatabase("computers4.db", 0, null)
        val addresses = JSONObject().apply {
            put("local", ComputerDatabaseManager.tupleToJson(ComputerDetails.AddressTuple("192.168.1.25", 47989)))
            put("manual", ComputerDatabaseManager.tupleToJson(ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 47989)))
        }
        val values = ContentValues().apply {
            put("UUID", "uuid-legacy-current")
            put("ComputerName", "Legacy Current")
            put("Addresses", addresses.toString())
        }
        db.insert("Computers", null, values)
        db.close()

        val manager = ComputerDatabaseManager(context)
        try {
            val restored = manager.getComputerByUUID("uuid-legacy-current")
            assertNotNull(restored)
            assertEquals(
                listOf(
                    ComputerDetails.AddressTuple("192.168.1.25", 47989),
                    ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 47989)
                ),
                restored!!.knownAddresses
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun malformedRememberedRoutesDoNotHideLegacyOrValidCandidates() {
        ComputerDatabaseManager(context).close()
        val db = context.openOrCreateDatabase("computers4.db", 0, null)
        val addresses = JSONObject().apply {
            put("local", ComputerDatabaseManager.tupleToJson(ComputerDetails.AddressTuple("192.168.1.25", 47989)))
            put(
                "known",
                JSONArray()
                    .put(JSONObject().put("address", "").put("port", 47989))
                    .put("not-an-endpoint")
                    .put(JSONObject().put("address", "invalid-port.example.test").put("port", 65536))
                    .put(JSONObject().put("address", "pc-papi.tailnet.ts.net").put("port", 47989))
            )
        }
        val values = ContentValues().apply {
            put("UUID", "uuid-damaged-known")
            put("ComputerName", "Damaged Known Routes")
            put("Addresses", addresses.toString())
        }
        db.insert("Computers", null, values)
        db.close()

        val manager = ComputerDatabaseManager(context)
        try {
            val restored = manager.getComputerByUUID("uuid-damaged-known")
            assertNotNull(restored)
            assertEquals(ComputerDetails.AddressTuple("192.168.1.25", 47989), restored!!.localAddress)
            assertEquals(
                listOf(ComputerDetails.AddressTuple("pc-papi.tailnet.ts.net", 47989)),
                restored.knownAddresses
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun malformedCertificateRowIsSkippedWithoutHidingValidRows() {
        val manager = ComputerDatabaseManager(context)
        try {
            val valid = ComputerDetails().apply {
                uuid = "uuid-valid-row"
                name = "Valid Row"
                rememberAddress(ComputerDetails.AddressTuple("valid.example.test", 47989))
            }
            assertTrue(manager.updateComputer(valid))
        } finally {
            manager.close()
        }

        val db = context.openOrCreateDatabase("computers4.db", 0, null)
        val addresses = JSONObject().apply {
            put("known", JSONArray().put(JSONObject().put("address", "damaged.example.test").put("port", 47989)))
        }
        val values = ContentValues().apply {
            put("UUID", "uuid-damaged-cert")
            put("ComputerName", "Damaged Certificate")
            put("Addresses", addresses.toString())
            put("ServerCert", byteArrayOf(1, 2, 3, 4))
        }
        db.insert("Computers", null, values)
        db.close()

        val restoredManager = ComputerDatabaseManager(context)
        try {
            assertNull(restoredManager.getComputerByUUID("uuid-damaged-cert"))
            assertEquals(listOf("uuid-valid-row"), restoredManager.getAllComputers().map { it.uuid })
        } finally {
            restoredManager.close()
        }
    }

    @Test
    fun blankUuidDatabaseRowIsSkippedWithoutHidingValidRows() {
        val manager = ComputerDatabaseManager(context)
        try {
            val valid = ComputerDetails().apply {
                uuid = "uuid-valid-identity"
                name = "Valid Identity"
                rememberAddress(ComputerDetails.AddressTuple("valid.example.test", 47989))
            }
            assertTrue(manager.updateComputer(valid))
        } finally {
            manager.close()
        }

        val db = context.openOrCreateDatabase("computers4.db", 0, null)
        val values = ContentValues().apply {
            put("UUID", "")
            put("ComputerName", "Missing Identity")
            put(
                "Addresses",
                JSONObject()
                    .put("known", JSONArray().put(JSONObject().put("address", "route.example.test").put("port", 47989)))
                    .toString()
            )
        }
        db.insert("Computers", null, values)
        db.close()

        val restoredManager = ComputerDatabaseManager(context)
        try {
            assertNull(restoredManager.getComputerByUUID(""))
            assertEquals(listOf("uuid-valid-identity"), restoredManager.getAllComputers().map { it.uuid })
        } finally {
            restoredManager.close()
        }
    }

    @Test
    fun oversizedRememberedHistoryKeepsOnlyTheNewestBoundedRoutes() {
        ComputerDatabaseManager(context).close()
        val db = context.openOrCreateDatabase("computers4.db", 0, null)
        val known = JSONArray()
        repeat(ComputerDetails.MAX_KNOWN_ADDRESSES + 2) { index ->
            known.put(
                JSONObject()
                    .put("address", "route-${index + 1}.example.test")
                    .put("port", 47989)
            )
        }
        val values = ContentValues().apply {
            put("UUID", "uuid-oversized-history")
            put("ComputerName", "Oversized History")
            put("Addresses", JSONObject().put("known", known).toString())
        }
        db.insert("Computers", null, values)
        db.close()

        val manager = ComputerDatabaseManager(context)
        try {
            val restored = manager.getComputerByUUID("uuid-oversized-history")!!
            assertEquals(ComputerDetails.MAX_KNOWN_ADDRESSES, restored.knownAddresses.size)
            assertEquals("route-3.example.test", restored.knownAddresses.first().address)
            assertEquals(
                "route-${ComputerDetails.MAX_KNOWN_ADDRESSES + 2}.example.test",
                restored.knownAddresses.last().address
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun malformedPrimaryAddressRowIsSkippedWithoutHidingValidRows() {
        val manager = ComputerDatabaseManager(context)
        try {
            val valid = ComputerDetails().apply {
                uuid = "uuid-valid-address-row"
                name = "Valid Address Row"
                rememberAddress(ComputerDetails.AddressTuple("valid.example.test", 47989))
            }
            assertTrue(manager.updateComputer(valid))
        } finally {
            manager.close()
        }

        val db = context.openOrCreateDatabase("computers4.db", 0, null)
        val malformedAddresses = JSONObject().apply {
            put("local", JSONObject().put("address", "broken.example.test").put("port", 65536))
        }
        val values = ContentValues().apply {
            put("UUID", "uuid-malformed-address")
            put("ComputerName", "Malformed Address")
            put("Addresses", malformedAddresses.toString())
        }
        db.insert("Computers", null, values)
        db.close()

        val restoredManager = ComputerDatabaseManager(context)
        try {
            assertNull(restoredManager.getComputerByUUID("uuid-malformed-address"))
            assertEquals(
                listOf("uuid-valid-address-row"),
                restoredManager.getAllComputers().map { it.uuid }
            )
        } finally {
            restoredManager.close()
        }
    }

    @Test
    fun oversizedAddressRowsAreFilteredBySqlBeforeCursorRestoration() {
        val source = File("src/main/java/com/papi/nova/computers/ComputerDatabaseManager.kt").readText()
        assertTrue(
            source.contains(
                "private const val BOUNDED_ADDRESSES_SELECTION = \"length(Addresses) <= ?\""
            )
        )

        fun queryBody(startMarker: String, endMarker: String): String {
            val start = source.indexOf(startMarker)
            val end = source.indexOf(endMarker, start + 1)
            assertTrue("Expected bounded database query $startMarker", start >= 0 && end > start)
            return source.substring(start, end)
        }

        val getAllBody = queryBody("fun getAllComputers()", "fun getComputerByName(")
        val getByNameBody = queryBody("fun getComputerByName(", "fun getComputerByUUID(")
        val getByUuidBody = queryBody("fun getComputerByUUID(", "private object AddressFields")

        for (body in listOf(getAllBody, getByNameBody, getByUuidBody)) {
            val sqlFilter = body.indexOf("BOUNDED_ADDRESSES_SELECTION")
            val sizeArgument = body.indexOf("MAX_ADDRESSES_JSON_LENGTH.toString()")
            val cursorRestoration = body.indexOf("getComputerFromCursor")
            assertTrue("Oversized address JSON must be filtered in SQL", sqlFilter >= 0)
            assertTrue("The SQL size filter must receive its bound", sizeArgument > sqlFilter)
            assertTrue("SQL filtering must precede cursor restoration", cursorRestoration > sizeArgument)
        }
    }

    @Test
    fun oversizedAddressJsonRowsAreExcludedBeforeRestoration() {
        val manager = ComputerDatabaseManager(context)
        try {
            val valid = ComputerDetails().apply {
                uuid = "uuid-bounded-addresses"
                name = "Bounded Addresses"
                rememberAddress(ComputerDetails.AddressTuple("valid.example.test", 47989))
            }
            assertTrue(manager.updateComputer(valid))
        } finally {
            manager.close()
        }

        val oversizedKnown = JSONArray()
        repeat(2_000) { index ->
            oversizedKnown.put(
                JSONObject()
                    .put("address", "oversized-route-$index.example.test")
                    .put("port", 47989)
            )
        }
        val db = context.openOrCreateDatabase("computers4.db", 0, null)
        val values = ContentValues().apply {
            put("UUID", "uuid-oversized-addresses")
            put("ComputerName", "Oversized Addresses")
            put("Addresses", JSONObject().put("known", oversizedKnown).toString())
        }
        db.insert("Computers", null, values)
        db.close()

        val restoredManager = ComputerDatabaseManager(context)
        try {
            assertNull(restoredManager.getComputerByUUID("uuid-oversized-addresses"))
            assertNull(restoredManager.getComputerByName("Oversized Addresses"))
            assertEquals(
                listOf("uuid-bounded-addresses"),
                restoredManager.getAllComputers().map { it.uuid }
            )
        } finally {
            restoredManager.close()
        }
    }

    @Test
    fun oversizedAddressStateCannotReplaceTheLastGoodRow() {
        val manager = ComputerDatabaseManager(context)
        try {
            val lastGood = ComputerDetails().apply {
                uuid = "uuid-oversized-write"
                name = "Last Good"
                rememberAddress(ComputerDetails.AddressTuple("valid.example.test", 47989))
            }
            assertTrue(manager.updateComputer(lastGood))

            val oversized = ComputerDetails(lastGood).apply {
                name = "Must Not Replace"
                manualAddress = ComputerDetails.AddressTuple("x".repeat(70_000), 47989)
            }
            assertFalse(manager.updateComputer(oversized))
            assertEquals("Last Good", manager.getComputerByUUID(lastGood.uuid)!!.name)
        } finally {
            manager.close()
        }
    }

    @Test
    fun failedLegacyDestinationWritePreservesSourceDatabase() {
        val legacyDb = context.openOrCreateDatabase("computers2.db", 0, null)
        legacyDb.execSQL(
            "CREATE TABLE Computers(UUID TEXT, ComputerName TEXT, LocalAddress TEXT, " +
                "RemoteAddress TEXT, ManualAddress TEXT, MacAddress TEXT)"
        )
        insertLegacy2Computer(
            legacyDb,
            "uuid-legacy-rolled-back",
            "Legacy Rolled Back",
            "local.example.test",
            "remote.example.test",
            "manual.example.test",
            "11:22:33:44:55:65"
        )
        insertLegacy2Computer(
            legacyDb,
            "uuid-legacy-write-failure",
            "Legacy Write Failure",
            "x".repeat(70_000),
            "remote.example.test",
            "manual.example.test",
            "11:22:33:44:55:66"
        )
        legacyDb.close()

        val manager = ComputerDatabaseManager(context)
        try {
            assertTrue(
                "The legacy database must survive until every destination write succeeds",
                context.getDatabasePath("computers2.db").exists()
            )
            assertNull(manager.getComputerByUUID("uuid-legacy-rolled-back"))
            assertNull(manager.getComputerByUUID("uuid-legacy-write-failure"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun unreadableLegacySchemasPreserveEverySourceDatabase() {
        val legacyDatabaseNames = listOf("computers.db", "computers2.db", "computers3.db")
        for (databaseName in legacyDatabaseNames) {
            val legacyDb = context.openOrCreateDatabase(databaseName, 0, null)
            legacyDb.execSQL("CREATE TABLE UnexpectedSchema(Value TEXT)")
            legacyDb.close()
        }

        val manager = ComputerDatabaseManager(context)
        try {
            for (databaseName in legacyDatabaseNames) {
                assertTrue(context.getDatabasePath(databaseName).exists())
            }
            assertEquals(emptyList<ComputerDetails>(), manager.getAllComputers())
        } finally {
            manager.close()
        }
    }

    @Test
    fun successfulLegacyMigrationCommitsThenDeletesSourceDatabase() {
        val legacyDb = context.openOrCreateDatabase("computers2.db", 0, null)
        legacyDb.execSQL(
            "CREATE TABLE Computers(UUID TEXT, ComputerName TEXT, LocalAddress TEXT, " +
                "RemoteAddress TEXT, ManualAddress TEXT, MacAddress TEXT)"
        )
        insertLegacy2Computer(
            legacyDb,
            "uuid-legacy-committed",
            "Legacy Committed",
            "local.example.test",
            "remote.example.test",
            "manual.example.test",
            "11:22:33:44:55:67"
        )
        legacyDb.close()

        val manager = ComputerDatabaseManager(context)
        try {
            assertFalse(context.getDatabasePath("computers2.db").exists())
            assertEquals("uuid-legacy-committed", manager.getComputerByUUID("uuid-legacy-committed")?.uuid)
        } finally {
            manager.close()
        }
    }

    @Test
    fun legacyDatabaseReader1SkipsMalformedRowsAndMigratesValidSibling() {
        val legacyDb = context.openOrCreateDatabase("computers.db", 0, null)
        legacyDb.execSQL(
            "CREATE TABLE Computers(ComputerName TEXT, UUID TEXT, LocalAddress BLOB, " +
                "RemoteAddress BLOB, MacAddress TEXT)"
        )
        fun insert(uuid: String, name: String, addressSuffix: Byte) {
            val values = ContentValues().apply {
                put("ComputerName", name)
                put("UUID", uuid)
                put("LocalAddress", byteArrayOf(10, 0, 0, addressSuffix))
                put("RemoteAddress", byteArrayOf(10, 0, 1, addressSuffix))
                put("MacAddress", "11:22:33:44:55:66")
            }
            legacyDb.insert("Computers", null, values)
        }
        insert("uuid-valid-legacy1", "Valid Legacy One", 2)
        insert("", "Blank Legacy One", 3)
        legacyDb.insert(
            "Computers",
            null,
            ContentValues().apply {
                put("ComputerName", "Malformed Legacy One")
                put("UUID", "uuid-malformed-legacy1")
                put("LocalAddress", "ADDRESS_PREFIX__ ".toByteArray())
                put("RemoteAddress", byteArrayOf(10, 0, 1, 4))
                put("MacAddress", "11:22:33:44:55:66")
            }
        )
        legacyDb.close()

        val manager = ComputerDatabaseManager(context)
        try {
            assertFalse(context.getDatabasePath("computers.db").exists())
            assertEquals("uuid-valid-legacy1", manager.getComputerByUUID("uuid-valid-legacy1")?.uuid)
            assertEquals(listOf("uuid-valid-legacy1"), manager.getAllComputers().map { it.uuid })
        } finally {
            manager.close()
        }
    }

    @Test
    fun legacyDatabaseReader1SkipsBlankFallbackAddressRows() {
        val cursor = org.mockito.Mockito.mock(android.database.Cursor::class.java)
        org.mockito.Mockito.`when`(cursor.getString(1)).thenReturn("uuid-blank-fallback")
        org.mockito.Mockito.`when`(cursor.getString(0)).thenReturn("Blank Fallback")
        org.mockito.Mockito.`when`(cursor.getBlob(2)).thenReturn(byteArrayOf(1))
        org.mockito.Mockito.`when`(cursor.getString(2)).thenReturn("ADDRESS_PREFIX__ ")

        val method = LegacyDatabaseReader.Companion::class.java.getDeclaredMethod(
            "getComputerFromCursor",
            android.database.Cursor::class.java
        )
        method.isAccessible = true

        assertNull(method.invoke(LegacyDatabaseReader.Companion, cursor))
    }

    @Test
    fun getComputerByNameSkipsMalformedSiblingRows() {
        ComputerDatabaseManager(context).close()
        val db = context.openOrCreateDatabase("computers4.db", 0, null)
        val malformed = ContentValues().apply {
            put("UUID", "uuid-malformed-same-name")
            put("ComputerName", "Shared Name")
            put(
                "Addresses",
                JSONObject()
                    .put("local", JSONObject().put("address", "broken.example.test").put("port", 65536))
                    .toString()
            )
        }
        db.insert("Computers", null, malformed)
        db.close()

        val manager = ComputerDatabaseManager(context)
        try {
            val valid = ComputerDetails().apply {
                uuid = "uuid-valid-same-name"
                name = "Shared Name"
                rememberAddress(ComputerDetails.AddressTuple("valid.example.test", 47989))
            }
            assertTrue(manager.updateComputer(valid))

            assertEquals("uuid-valid-same-name", manager.getComputerByName("Shared Name")?.uuid)
        } finally {
            manager.close()
        }
    }

    @Test
    fun legacyDatabaseReader2SkipsMalformedCertificateRowsWithoutDeletingSource() {
        val db = context.openOrCreateDatabase("computers2.db", 0, null)
        db.execSQL(
            "CREATE TABLE Computers(UUID TEXT, ComputerName TEXT, LocalAddress TEXT, " +
                "RemoteAddress TEXT, ManualAddress TEXT, MacAddress TEXT, ServerCert BLOB)"
        )
        fun insert(uuid: String, certificate: ByteArray?) {
            val values = ContentValues().apply {
                put("UUID", uuid)
                put("ComputerName", uuid)
                put("LocalAddress", "local.example.test")
                put("RemoteAddress", "remote.example.test")
                put("ManualAddress", "manual.example.test")
                put("MacAddress", "11:22:33:44:55:66")
                put("ServerCert", certificate)
            }
            db.insert("Computers", null, values)
        }
        insert("uuid-valid-legacy2", null)
        insert("uuid-invalid-cert-legacy2", byteArrayOf(1, 2, 3, 4))
        db.close()

        val migrated = LegacyDatabaseReader2.migrateAllComputers(context)

        assertEquals(listOf("uuid-valid-legacy2"), migrated.map { it.uuid })
        assertTrue(context.getDatabasePath("computers2.db").exists())
    }

    @Test
    fun legacyDatabaseReader3SkipsMalformedCertificateRowsWithoutDeletingSource() {
        val db = context.openOrCreateDatabase("computers3.db", 0, null)
        db.execSQL("CREATE TABLE Computers(UUID TEXT, ComputerName TEXT, Addresses TEXT, MacAddress TEXT, ServerCert BLOB)")
        fun insert(uuid: String, certificate: ByteArray?) {
            val values = ContentValues().apply {
                put("UUID", uuid)
                put("ComputerName", uuid)
                put("Addresses", "local_47984;remote_48010;manual_48011;2001:db8::5_48012")
                put("MacAddress", "22:33:44:55:66:77")
                put("ServerCert", certificate)
            }
            db.insert("Computers", null, values)
        }
        insert("uuid-valid-legacy3", null)
        insert("uuid-invalid-cert-legacy3", byteArrayOf(1, 2, 3, 4))
        db.close()

        val migrated = LegacyDatabaseReader3.migrateAllComputers(context)

        assertEquals(listOf("uuid-valid-legacy3"), migrated.map { it.uuid })
        assertTrue(context.getDatabasePath("computers3.db").exists())
    }

    @Test
    fun legacyDatabaseReader2ReadsRowsSkipsNullUuidAndRetainsOldDatabase() {
        val db = context.openOrCreateDatabase("computers2.db", 0, null)
        db.execSQL("CREATE TABLE Computers(UUID TEXT, ComputerName TEXT, LocalAddress TEXT, RemoteAddress TEXT, ManualAddress TEXT, MacAddress TEXT)")
        insertLegacy2Computer(db, "uuid-2", "Legacy Two", "10.0.0.2", "remote-two", "manual-two", "11:22:33:44:55:66")
        insertLegacy2Computer(db, null, "Broken", "10.0.0.3", "remote-broken", "manual-broken", "00:00:00:00:00:00")
        insertLegacy2Computer(db, "", "Blank UUID", "10.0.0.4", "remote-blank", "manual-blank", "00:00:00:00:00:01")
        insertLegacy2Computer(db, "uuid-malformed-legacy2", "Malformed", "", "remote-malformed", "manual-malformed", "00:00:00:00:00:02")
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
        assertTrue(context.getDatabasePath("computers2.db").exists())
    }

    @Test
    fun legacyDatabaseReader3ReadsDelimitedAddressesAndRetainsOldDatabase() {
        val db = context.openOrCreateDatabase("computers3.db", 0, null)
        db.execSQL("CREATE TABLE Computers(UUID TEXT, ComputerName TEXT, Addresses TEXT, MacAddress TEXT, ServerCert BLOB)")
        val values = ContentValues()
        values.put("UUID", "uuid-3")
        values.put("ComputerName", "Legacy Three")
        values.put("Addresses", "local_47984;remote_48010;manual_48011;2001:db8::5_48012")
        values.put("MacAddress", "22:33:44:55:66:77")
        db.insert("Computers", null, values)
        val blankIdentity = ContentValues().apply {
            put("UUID", "")
            put("ComputerName", "Blank Identity")
            put("Addresses", "local_47984;remote_48010;manual_48011;2001:db8::5_48012")
            put("MacAddress", "00:00:00:00:00:01")
        }
        db.insert("Computers", null, blankIdentity)
        val malformed = ContentValues().apply {
            put("UUID", "uuid-malformed-legacy3")
            put("ComputerName", "Malformed")
            put("Addresses", "only-one-address")
            put("MacAddress", "00:00:00:00:00:02")
        }
        db.insert("Computers", null, malformed)
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
        assertTrue(context.getDatabasePath("computers3.db").exists())
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

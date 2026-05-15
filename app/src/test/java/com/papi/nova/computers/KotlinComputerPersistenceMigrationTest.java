package com.papi.nova.computers;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.NvHTTP;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class KotlinComputerPersistenceMigrationTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteFile("uniqueid");
        context.deleteDatabase("computers.db");
        context.deleteDatabase("computers2.db");
        context.deleteDatabase("computers3.db");
        context.deleteDatabase("computers4.db");
    }

    @Test
    public void computerPersistenceClassesAreKotlinSources() {
        String[] paths = {
                "src/main/java/com/papi/nova/computers/IdentityManager",
                "src/main/java/com/papi/nova/computers/ComputerDatabaseManager",
                "src/main/java/com/papi/nova/computers/LegacyDatabaseReader",
                "src/main/java/com/papi/nova/computers/LegacyDatabaseReader2",
                "src/main/java/com/papi/nova/computers/LegacyDatabaseReader3"
        };

        for (String path : paths) {
            assertFalse(path + " should no longer be a Java source", new File(path + ".java").exists());
            assertTrue(path + " should be migrated to Kotlin", new File(path + ".kt").exists());
        }
    }

    @Test
    public void computerPersistenceClassesKeepJavaCompatibleApis() throws Exception {
        IdentityManager.class.getConstructor(Context.class);
        assertEquals(String.class, IdentityManager.class.getMethod("getUniqueId").getReturnType());

        ComputerDatabaseManager.class.getConstructor(Context.class);
        ComputerDatabaseManager.class.getMethod("close");
        ComputerDatabaseManager.class.getMethod("deleteComputer", ComputerDetails.class);
        ComputerDatabaseManager.class.getMethod("tupleToJson", ComputerDetails.AddressTuple.class);
        ComputerDatabaseManager.class.getMethod("tupleFromJson", JSONObject.class, String.class);
        ComputerDatabaseManager.class.getMethod("updateComputer", ComputerDetails.class);
        ComputerDatabaseManager.class.getMethod("getAllComputers");
        ComputerDatabaseManager.class.getMethod("getComputerByName", String.class);
        ComputerDatabaseManager.class.getMethod("getComputerByUUID", String.class);

        LegacyDatabaseReader.class.getMethod("migrateAllComputers", Context.class);
        LegacyDatabaseReader2.class.getMethod("getAllComputers", SQLiteDatabase.class);
        LegacyDatabaseReader2.class.getMethod("migrateAllComputers", Context.class);
        LegacyDatabaseReader3.class.getMethod("getAllComputers", SQLiteDatabase.class);
        LegacyDatabaseReader3.class.getMethod("migrateAllComputers", Context.class);
    }

    @Test
    public void identityManagerGeneratesAndPersistsHexUniqueId() {
        IdentityManager first = new IdentityManager(context);
        IdentityManager second = new IdentityManager(context);

        assertTrue(first.getUniqueId().matches("[0-9a-f]{16}"));
        assertEquals(first.getUniqueId(), second.getUniqueId());
    }

    @Test
    public void tupleJsonRoundTripsNullableAddressTuples() throws Exception {
        ComputerDetails.AddressTuple tuple = new ComputerDetails.AddressTuple("192.168.1.9", 47989);
        JSONObject wrapper = new JSONObject();
        wrapper.put("manual", ComputerDatabaseManager.tupleToJson(tuple));

        ComputerDetails.AddressTuple restored = ComputerDatabaseManager.tupleFromJson(wrapper, "manual");

        assertNotNull(restored);
        assertEquals("192.168.1.9", restored.address);
        assertEquals(47989, restored.port);
        assertNull(ComputerDatabaseManager.tupleToJson(null));
        assertNull(ComputerDatabaseManager.tupleFromJson(wrapper, "missing"));
    }

    @Test
    public void computerDatabasePersistsUpdatesReadsAndDeletesComputers() {
        ComputerDatabaseManager manager = new ComputerDatabaseManager(context);
        try {
            ComputerDetails details = new ComputerDetails();
            details.uuid = "uuid-1";
            details.name = "Retroid Host";
            details.localAddress = new ComputerDetails.AddressTuple("192.168.1.2", 47984);
            details.remoteAddress = new ComputerDetails.AddressTuple("wan.example.test", 48010);
            details.manualAddress = new ComputerDetails.AddressTuple("manual.example.test", 48011);
            details.ipv6Address = new ComputerDetails.AddressTuple("2001:db8::4", 48012);
            details.macAddress = "AA:BB:CC:DD:EE:FF";

            assertTrue(manager.updateComputer(details));

            ComputerDetails byUuid = manager.getComputerByUUID("uuid-1");
            assertNotNull(byUuid);
            assertEquals("Retroid Host", byUuid.name);
            assertEquals("192.168.1.2", byUuid.localAddress.address);
            assertEquals("wan.example.test", byUuid.remoteAddress.address);
            assertEquals(48010, byUuid.externalPort);
            assertEquals("manual.example.test", byUuid.manualAddress.address);
            assertEquals("2001:db8::4", byUuid.ipv6Address.address);
            assertEquals("AA:BB:CC:DD:EE:FF", byUuid.macAddress);
            assertEquals(ComputerDetails.State.UNKNOWN, byUuid.state);

            ComputerDetails byName = manager.getComputerByName("Retroid Host");
            assertNotNull(byName);
            assertEquals("uuid-1", byName.uuid);
            assertEquals(1, manager.getAllComputers().size());

            manager.deleteComputer(details);
            assertNull(manager.getComputerByUUID("uuid-1"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void legacyDatabaseReader2MigratesRowsSkipsNullUuidAndDeletesOldDatabase() {
        SQLiteDatabase db = context.openOrCreateDatabase("computers2.db", 0, null);
        db.execSQL("CREATE TABLE Computers(UUID TEXT, ComputerName TEXT, LocalAddress TEXT, RemoteAddress TEXT, ManualAddress TEXT, MacAddress TEXT)");
        insertLegacy2Computer(db, "uuid-2", "Legacy Two", "10.0.0.2", "remote-two", "manual-two", "11:22:33:44:55:66");
        insertLegacy2Computer(db, null, "Broken", "10.0.0.3", "remote-broken", "manual-broken", "00:00:00:00:00:00");
        db.close();

        List<ComputerDetails> migrated = LegacyDatabaseReader2.migrateAllComputers(context);

        assertEquals(1, migrated.size());
        ComputerDetails details = migrated.get(0);
        assertEquals("uuid-2", details.uuid);
        assertEquals("Legacy Two", details.name);
        assertEquals("10.0.0.2", details.localAddress.address);
        assertEquals(NvHTTP.DEFAULT_HTTP_PORT, details.localAddress.port);
        assertEquals("remote-two", details.remoteAddress.address);
        assertEquals("manual-two", details.manualAddress.address);
        assertEquals("11:22:33:44:55:66", details.macAddress);
        assertFalse(context.getDatabasePath("computers2.db").exists());
    }

    @Test
    public void legacyDatabaseReader3MigratesDelimitedAddressesAndDeletesOldDatabase() {
        SQLiteDatabase db = context.openOrCreateDatabase("computers3.db", 0, null);
        db.execSQL("CREATE TABLE Computers(UUID TEXT, ComputerName TEXT, Addresses TEXT, MacAddress TEXT, ServerCert BLOB)");
        ContentValues values = new ContentValues();
        values.put("UUID", "uuid-3");
        values.put("ComputerName", "Legacy Three");
        values.put("Addresses", "local_47984;remote_48010;manual_48011;2001:db8::5_48012");
        values.put("MacAddress", "22:33:44:55:66:77");
        db.insert("Computers", null, values);
        db.close();

        List<ComputerDetails> migrated = LegacyDatabaseReader3.migrateAllComputers(context);

        assertEquals(1, migrated.size());
        ComputerDetails details = migrated.get(0);
        assertEquals("uuid-3", details.uuid);
        assertEquals("Legacy Three", details.name);
        assertEquals("local", details.localAddress.address);
        assertEquals(47984, details.localAddress.port);
        assertEquals("remote", details.remoteAddress.address);
        assertEquals(48010, details.remoteAddress.port);
        assertEquals(48010, details.externalPort);
        assertEquals("manual", details.manualAddress.address);
        assertEquals("2001:db8::5", details.ipv6Address.address);
        assertEquals(48012, details.ipv6Address.port);
        assertEquals("22:33:44:55:66:77", details.macAddress);
        assertFalse(context.getDatabasePath("computers3.db").exists());
    }

    private static void insertLegacy2Computer(
            SQLiteDatabase db,
            String uuid,
            String name,
            String local,
            String remote,
            String manual,
            String macAddress) {
        ContentValues values = new ContentValues();
        values.put("UUID", uuid);
        values.put("ComputerName", name);
        values.put("LocalAddress", local);
        values.put("RemoteAddress", remote);
        values.put("ManualAddress", manual);
        values.put("MacAddress", macAddress);
        db.insert("Computers", null, values);
    }
}

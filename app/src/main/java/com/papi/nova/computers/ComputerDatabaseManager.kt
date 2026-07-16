package com.papi.nova.computers

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvHTTP
import java.io.ByteArrayInputStream
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.LinkedList
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ComputerDatabaseManager(c: Context) {
    private var computerDb: SQLiteDatabase

    init {
        computerDb = try {
            c.openOrCreateDatabase(COMPUTER_DB_NAME, 0, null)
        } catch (_: SQLiteException) {
            c.deleteDatabase(COMPUTER_DB_NAME)
            c.openOrCreateDatabase(COMPUTER_DB_NAME, 0, null)
        }
        initializeDb(c)
    }

    fun close() {
        computerDb.close()
    }

    private fun initializeDb(c: Context) {
        computerDb.execSQL(
            String.format(
                null as Locale?,
                "CREATE TABLE IF NOT EXISTS %s(%s TEXT PRIMARY KEY, %s TEXT NOT NULL, %s TEXT NOT NULL, %s TEXT, %s TEXT)",
                COMPUTER_TABLE_NAME,
                COMPUTER_UUID_COLUMN_NAME,
                COMPUTER_NAME_COLUMN_NAME,
                ADDRESSES_COLUMN_NAME,
                MAC_ADDRESS_COLUMN_NAME,
                SERVER_CERT_COLUMN_NAME
            )
        )

        migrateLegacyDatabase(c, LegacyDatabaseReader.COMPUTER_DB_NAME, LegacyDatabaseReader.readAllComputers(c))
        migrateLegacyDatabase(c, LegacyDatabaseReader2.COMPUTER_DB_NAME, LegacyDatabaseReader2.readAllComputers(c))
        migrateLegacyDatabase(c, LegacyDatabaseReader3.COMPUTER_DB_NAME, LegacyDatabaseReader3.readAllComputers(c))
    }

    private fun migrateLegacyDatabase(
        c: Context,
        databaseName: String,
        oldComputers: List<ComputerDetails>?,
    ) {
        if (oldComputers == null) {
            return
        }

        var migrationSucceeded = false
        try {
            computerDb.beginTransaction()
            migrationSucceeded = oldComputers.all { computer ->
                computer.seedLegacyAddresses()
                updateComputer(computer)
            }
            if (migrationSucceeded) {
                computerDb.setTransactionSuccessful()
            }
        } catch (_: RuntimeException) {
            migrationSucceeded = false
        } finally {
            if (computerDb.inTransaction()) {
                try {
                    computerDb.endTransaction()
                } catch (_: RuntimeException) {
                    migrationSucceeded = false
                }
            }
        }

        if (migrationSucceeded) {
            c.deleteDatabase(databaseName)
        }
    }

    fun deleteComputer(details: ComputerDetails) {
        computerDb.delete(COMPUTER_TABLE_NAME, "$COMPUTER_UUID_COLUMN_NAME=?", arrayOf(details.uuid))
    }

    fun updateComputer(details: ComputerDetails): Boolean {
        if (details.uuid.isBlank()) {
            return false
        }
        val values = ContentValues()
        values.put(COMPUTER_UUID_COLUMN_NAME, details.uuid)
        values.put(COMPUTER_NAME_COLUMN_NAME, details.name)

        try {
            val addresses = JSONObject()
            addresses.put(AddressFields.LOCAL, tupleToJson(details.localAddress))
            addresses.put(AddressFields.REMOTE, tupleToJson(details.remoteAddress))
            addresses.put(AddressFields.MANUAL, tupleToJson(details.manualAddress))
            addresses.put(AddressFields.IPV6, tupleToJson(details.ipv6Address))
            addresses.put(AddressFields.KNOWN, tuplesToJson(details.knownAddresses))
            val serializedAddresses = addresses.toString()
            if (serializedAddresses.length > MAX_ADDRESSES_JSON_LENGTH) {
                return false
            }
            values.put(ADDRESSES_COLUMN_NAME, serializedAddresses)
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }

        values.put(MAC_ADDRESS_COLUMN_NAME, details.macAddress)
        try {
            val serverCert = details.serverCert
            if (serverCert != null) {
                values.put(SERVER_CERT_COLUMN_NAME, serverCert.encoded)
            } else {
                values.put(SERVER_CERT_COLUMN_NAME, null as ByteArray?)
            }
        } catch (_: CertificateEncodingException) {
            return false
        }
        return -1L != computerDb.insertWithOnConflict(
            COMPUTER_TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun getComputerFromCursor(c: Cursor): ComputerDetails? {
        val details = ComputerDetails()

        details.uuid = c.getString(0) ?: ""
        if (details.uuid.isBlank()) {
            return null
        }
        details.name = c.getString(1) ?: ""
        val serializedAddresses = c.getString(2) ?: return null
        if (serializedAddresses.length > MAX_ADDRESSES_JSON_LENGTH) {
            return null
        }
        try {
            val addresses = JSONObject(serializedAddresses)
            details.localAddress = tupleFromJson(addresses, AddressFields.LOCAL)
            details.remoteAddress = tupleFromJson(addresses, AddressFields.REMOTE)
            details.manualAddress = tupleFromJson(addresses, AddressFields.MANUAL)
            details.ipv6Address = tupleFromJson(addresses, AddressFields.IPV6)
            if (addresses.has(AddressFields.KNOWN)) {
                for (knownAddress in tuplesFromJson(addresses, AddressFields.KNOWN)) {
                    details.rememberAddress(knownAddress)
                }
            } else {
                details.seedLegacyAddresses()
            }
        } catch (_: JSONException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }

        details.externalPort = details.remoteAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT
        details.macAddress = c.getString(3)

        val derCertData = c.getBlob(4)
        if (derCertData != null) {
            try {
                details.serverCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
            } catch (_: CertificateException) {
                return null
            }
        }

        details.state = ComputerDetails.State.UNKNOWN

        return details
    }

    fun getAllComputers(): List<ComputerDetails> {
        computerDb.query(
            COMPUTER_TABLE_NAME,
            null,
            BOUNDED_ADDRESSES_SELECTION,
            arrayOf(MAX_ADDRESSES_JSON_LENGTH.toString()),
            null,
            null,
            null
        ).use { c ->
            val computerList = LinkedList<ComputerDetails>()
            while (c.moveToNext()) {
                getComputerFromCursor(c)?.let(computerList::add)
            }
            return computerList
        }
    }

    fun getComputerByName(name: String): ComputerDetails? {
        computerDb.query(
            COMPUTER_TABLE_NAME,
            null,
            "$COMPUTER_NAME_COLUMN_NAME=? AND $BOUNDED_ADDRESSES_SELECTION",
            arrayOf(name, MAX_ADDRESSES_JSON_LENGTH.toString()),
            null,
            null,
            null
        ).use { c ->
            while (c.moveToNext()) {
                getComputerFromCursor(c)?.let { return it }
            }
            return null
        }
    }

    fun getComputerByUUID(uuid: String): ComputerDetails? {
        computerDb.query(
            COMPUTER_TABLE_NAME,
            null,
            "$COMPUTER_UUID_COLUMN_NAME=? AND $BOUNDED_ADDRESSES_SELECTION",
            arrayOf(uuid, MAX_ADDRESSES_JSON_LENGTH.toString()),
            null,
            null,
            null
        ).use { c ->
            if (!c.moveToFirst()) {
                return null
            }

            return getComputerFromCursor(c)
        }
    }

    private object AddressFields {
        const val LOCAL = "local"
        const val REMOTE = "remote"
        const val MANUAL = "manual"
        const val IPV6 = "ipv6"
        const val KNOWN = "known"
        const val ADDRESS = "address"
        const val PORT = "port"
    }

    companion object {
        private const val COMPUTER_DB_NAME = "computers4.db"
        private const val COMPUTER_TABLE_NAME = "Computers"
        private const val COMPUTER_UUID_COLUMN_NAME = "UUID"
        private const val COMPUTER_NAME_COLUMN_NAME = "ComputerName"
        private const val ADDRESSES_COLUMN_NAME = "Addresses"
        private const val MAC_ADDRESS_COLUMN_NAME = "MacAddress"
        private const val SERVER_CERT_COLUMN_NAME = "ServerCert"
        private const val MAX_ADDRESSES_JSON_LENGTH = 64 * 1024
        private const val BOUNDED_ADDRESSES_SELECTION = "length(Addresses) <= ?"

        @JvmStatic
        @Throws(JSONException::class)
        fun tupleToJson(tuple: ComputerDetails.AddressTuple?): JSONObject? {
            if (tuple == null) {
                return null
            }

            val json = JSONObject()
            json.put(AddressFields.ADDRESS, tuple.address)
            json.put(AddressFields.PORT, tuple.port)

            return json
        }

        private fun tuplesToJson(tuples: List<ComputerDetails.AddressTuple>): JSONArray {
            val json = JSONArray()
            for (tuple in tuples) {
                json.put(tupleToJson(tuple))
            }
            return json
        }

        private fun tuplesFromJson(json: JSONObject, name: String): List<ComputerDetails.AddressTuple> {
            val tupleArray = json.optJSONArray(name) ?: return emptyList()
            val firstIndex = maxOf(0, tupleArray.length() - ComputerDetails.MAX_KNOWN_ADDRESSES)
            val tuples = ArrayList<ComputerDetails.AddressTuple>(tupleArray.length() - firstIndex)
            for (index in firstIndex until tupleArray.length()) {
                val tuple = tupleArray.optJSONObject(index) ?: continue
                try {
                    tuples.add(
                        ComputerDetails.AddressTuple(
                            tuple.getString(AddressFields.ADDRESS),
                            tuple.getInt(AddressFields.PORT)
                        )
                    )
                } catch (_: JSONException) {
                    // Ignore a malformed remembered route without losing the computer record.
                } catch (_: IllegalArgumentException) {
                    // Ignore invalid addresses and ports from an older or damaged record.
                }
            }
            return tuples
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun tupleFromJson(json: JSONObject, name: String): ComputerDetails.AddressTuple? {
            if (!json.has(name)) {
                return null
            }

            val address = json.getJSONObject(name)
            return ComputerDetails.AddressTuple(
                address.getString(AddressFields.ADDRESS),
                address.getInt(AddressFields.PORT)
            )
        }
    }
}

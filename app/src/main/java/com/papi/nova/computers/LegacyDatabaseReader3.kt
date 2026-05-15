package com.papi.nova.computers

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvHTTP
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.LinkedList

class LegacyDatabaseReader3 private constructor() {
    companion object {
        private const val COMPUTER_DB_NAME = "computers3.db"
        private const val COMPUTER_TABLE_NAME = "Computers"
        private const val ADDRESS_DELIMITER = ';'
        private const val PORT_DELIMITER = '_'

        private fun readNonEmptyString(input: String): String? = input.ifEmpty { null }

        private fun splitAddressToTuple(input: String?): ComputerDetails.AddressTuple? {
            if (input == null) {
                return null
            }

            val parts = splitPreservingEmptyParts(input, PORT_DELIMITER)
            return if (parts.size == 1) {
                ComputerDetails.AddressTuple(parts[0], NvHTTP.DEFAULT_HTTP_PORT)
            } else {
                ComputerDetails.AddressTuple(parts[0], parts[1].toInt())
            }
        }

        @Suppress("unused")
        private fun splitTupleToAddress(tuple: ComputerDetails.AddressTuple): String =
            tuple.address + PORT_DELIMITER + tuple.port

        private fun getComputerFromCursor(c: Cursor): ComputerDetails? {
            val uuid = c.getString(0) ?: return null
            val details = ComputerDetails()

            details.uuid = uuid
            details.name = c.getString(1) ?: ""

            val addresses = splitPreservingEmptyParts(c.getString(2), ADDRESS_DELIMITER)
            details.localAddress = splitAddressToTuple(readNonEmptyString(addresses[0]))
            details.remoteAddress = splitAddressToTuple(readNonEmptyString(addresses[1]))
            details.manualAddress = splitAddressToTuple(readNonEmptyString(addresses[2]))
            details.ipv6Address = splitAddressToTuple(readNonEmptyString(addresses[3]))

            details.externalPort = details.remoteAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT
            details.macAddress = c.getString(3)

            try {
                val derCertData = c.getBlob(4)
                if (derCertData != null) {
                    details.serverCert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
                }
            } catch (e: CertificateException) {
                e.printStackTrace()
            }

            details.state = ComputerDetails.State.UNKNOWN

            return details
        }

        @JvmStatic
        fun getAllComputers(computerDb: SQLiteDatabase): List<ComputerDetails> {
            computerDb.rawQuery("SELECT * FROM $COMPUTER_TABLE_NAME", null).use { c ->
                val computerList = LinkedList<ComputerDetails>()
                while (c.moveToNext()) {
                    getComputerFromCursor(c)?.let { computerList.add(it) }
                }

                return computerList
            }
        }

        @JvmStatic
        fun migrateAllComputers(c: Context): List<ComputerDetails> {
            return try {
                SQLiteDatabase.openDatabase(
                    c.getDatabasePath(COMPUTER_DB_NAME).path,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                ).use { computerDb ->
                    getAllComputers(computerDb)
                }
            } catch (_: SQLiteException) {
                LinkedList()
            } finally {
                c.deleteDatabase(COMPUTER_DB_NAME)
            }
        }

        private fun splitPreservingEmptyParts(input: String, delimiter: Char): List<String> {
            val parts = ArrayList<String>()
            var start = 0
            for (i in input.indices) {
                if (input[i] == delimiter) {
                    parts.add(input.substring(start, i))
                    start = i + 1
                }
            }
            parts.add(input.substring(start))
            return parts
        }
    }
}

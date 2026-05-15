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

class LegacyDatabaseReader2 private constructor() {
    companion object {
        private const val COMPUTER_DB_NAME = "computers2.db"
        private const val COMPUTER_TABLE_NAME = "Computers"

        private fun getComputerFromCursor(c: Cursor): ComputerDetails? {
            val uuid = c.getString(0) ?: return null
            val details = ComputerDetails()

            details.uuid = uuid
            details.name = c.getString(1) ?: ""
            details.localAddress = ComputerDetails.AddressTuple(c.getString(2), NvHTTP.DEFAULT_HTTP_PORT)
            details.remoteAddress = ComputerDetails.AddressTuple(c.getString(3), NvHTTP.DEFAULT_HTTP_PORT)
            details.manualAddress = ComputerDetails.AddressTuple(c.getString(4), NvHTTP.DEFAULT_HTTP_PORT)
            details.macAddress = c.getString(5)

            if (c.columnCount >= 7) {
                try {
                    val derCertData = c.getBlob(6)
                    if (derCertData != null) {
                        details.serverCert = CertificateFactory.getInstance("X.509")
                            .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
                    }
                } catch (e: CertificateException) {
                    e.printStackTrace()
                }
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
    }
}

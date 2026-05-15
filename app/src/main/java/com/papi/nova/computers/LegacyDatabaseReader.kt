package com.papi.nova.computers

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.papi.nova.LimeLog
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvHTTP
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.LinkedList

class LegacyDatabaseReader private constructor() {
    companion object {
        private const val COMPUTER_DB_NAME = "computers.db"
        private const val COMPUTER_TABLE_NAME = "Computers"
        private const val ADDRESS_PREFIX = "ADDRESS_PREFIX__"

        private fun getComputerFromCursor(c: Cursor): ComputerDetails? {
            val uuid = c.getString(1) ?: return null
            val details = ComputerDetails()

            details.name = c.getString(0) ?: ""
            details.uuid = uuid

            try {
                details.localAddress = ComputerDetails.AddressTuple(
                    InetAddress.getByAddress(c.getBlob(2)).hostAddress,
                    NvHTTP.DEFAULT_HTTP_PORT
                )
                LimeLog.warning("DB: Legacy local address for " + details.name)
            } catch (_: UnknownHostException) {
                val stringData = c.getString(2)
                if (stringData != null && stringData.startsWith(ADDRESS_PREFIX)) {
                    details.localAddress = ComputerDetails.AddressTuple(
                        stringData.substring(ADDRESS_PREFIX.length),
                        NvHTTP.DEFAULT_HTTP_PORT
                    )
                } else {
                    LimeLog.severe("DB: Corrupted local address for " + details.name)
                }
            }

            try {
                details.remoteAddress = ComputerDetails.AddressTuple(
                    InetAddress.getByAddress(c.getBlob(3)).hostAddress,
                    NvHTTP.DEFAULT_HTTP_PORT
                )
                LimeLog.warning("DB: Legacy remote address for " + details.name)
            } catch (_: UnknownHostException) {
                val stringData = c.getString(3)
                if (stringData != null && stringData.startsWith(ADDRESS_PREFIX)) {
                    details.remoteAddress = ComputerDetails.AddressTuple(
                        stringData.substring(ADDRESS_PREFIX.length),
                        NvHTTP.DEFAULT_HTTP_PORT
                    )
                } else {
                    LimeLog.severe("DB: Corrupted remote address for " + details.name)
                }
            }

            details.manualAddress = details.remoteAddress
            details.macAddress = c.getString(4)
            details.state = ComputerDetails.State.UNKNOWN

            return details
        }

        private fun getAllComputers(db: SQLiteDatabase): List<ComputerDetails> {
            db.rawQuery("SELECT * FROM $COMPUTER_TABLE_NAME", null).use { c ->
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

package com.papi.nova.utils

import android.net.TrafficStats

object TrafficStatsHelper {
    @JvmStatic
    fun getAllRxBytes(): Long = TrafficStats.getTotalRxBytes()

    @JvmStatic
    fun getAllTxBytes(): Long = TrafficStats.getTotalTxBytes()

    @JvmStatic
    fun getAllRxBytesMobile(): Long = TrafficStats.getMobileRxBytes()

    @JvmStatic
    fun getAllTxBytesMobile(): Long = TrafficStats.getMobileTxBytes()

    @JvmStatic
    fun getAllRxBytesWifi(): Long = TrafficStats.getTotalRxBytes() - TrafficStats.getMobileRxBytes()

    @JvmStatic
    fun getAllTxBytesWifi(): Long = TrafficStats.getTotalTxBytes() - TrafficStats.getMobileTxBytes()

    @JvmStatic
    fun getPackageRxBytes(uid: Int): Long = TrafficStats.getUidRxBytes(uid)

    @JvmStatic
    fun getPackageTxBytes(uid: Int): Long = TrafficStats.getUidTxBytes(uid)
}

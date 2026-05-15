package com.papi.nova.discovery

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.papi.nova.nvstream.mdns.JmDNSDiscoveryAgent
import com.papi.nova.nvstream.mdns.LegacyNsdManagerDiscoveryAgent
import com.papi.nova.nvstream.mdns.MdnsComputer
import com.papi.nova.nvstream.mdns.MdnsDiscoveryAgent
import com.papi.nova.nvstream.mdns.MdnsDiscoveryListener
import com.papi.nova.nvstream.mdns.NsdManagerDiscoveryAgent
import com.papi.nova.utils.UiHelper

class DiscoveryService : Service() {
    private lateinit var discoveryAgent: MdnsDiscoveryAgent
    private var boundListener: MdnsDiscoveryListener? = null

    inner class DiscoveryBinder : Binder() {
        fun setListener(listener: MdnsDiscoveryListener?) {
            boundListener = listener
        }

        fun startDiscovery(queryIntervalMs: Int) {
            discoveryAgent.startDiscovery(queryIntervalMs)
        }

        fun stopDiscovery() {
            discoveryAgent.stopDiscovery()
        }

        fun getComputerSet(): List<MdnsComputer> = discoveryAgent.getComputerSet()
    }

    private val binder = DiscoveryBinder()

    override fun onCreate() {
        val listener = object : MdnsDiscoveryListener {
            override fun notifyComputerAdded(computer: MdnsComputer) {
                boundListener?.notifyComputerAdded(computer)
            }

            override fun notifyDiscoveryFailure(e: Exception) {
                boundListener?.notifyDiscoveryFailure(e)
            }
        }

        discoveryAgent = if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !UiHelper.isTvDevice(this)
        ) {
            JmDNSDiscoveryAgent(applicationContext, listener)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            LegacyNsdManagerDiscoveryAgent(applicationContext, listener)
        } else {
            NsdManagerDiscoveryAgent(applicationContext, listener)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        discoveryAgent.stopDiscovery()
        boundListener = null
        return false
    }
}

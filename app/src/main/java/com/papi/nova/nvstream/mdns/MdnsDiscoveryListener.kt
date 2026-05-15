package com.papi.nova.nvstream.mdns

interface MdnsDiscoveryListener {
    fun notifyComputerAdded(computer: MdnsComputer)

    fun notifyDiscoveryFailure(e: Exception)
}

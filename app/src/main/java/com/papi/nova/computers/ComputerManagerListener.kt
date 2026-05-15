package com.papi.nova.computers

import com.papi.nova.nvstream.http.ComputerDetails

interface ComputerManagerListener {
    fun notifyComputerUpdated(details: ComputerDetails)
}

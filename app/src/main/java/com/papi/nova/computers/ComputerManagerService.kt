package com.papi.nova.computers

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.papi.nova.LimeLog
import com.papi.nova.binding.PlatformBinding
import com.papi.nova.discovery.DiscoveryService
import com.papi.nova.nvstream.NvConnection
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.nvstream.http.NvHTTP
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.nvstream.mdns.MdnsComputer
import com.papi.nova.nvstream.mdns.MdnsDiscoveryListener
import com.papi.nova.utils.CacheHelper
import com.papi.nova.utils.NetHelper
import com.papi.nova.utils.ServerHelper
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import org.xmlpull.v1.XmlPullParserException

class ComputerManagerService : Service() {
    private val binder = ComputerManagerBinder()

    private lateinit var dbManager: ComputerDatabaseManager
    private val dbRefCount = AtomicInteger(0)

    private lateinit var idManager: IdentityManager
    private val pollingTuples = ConcurrentHashMap<String, PollingTuple>()
    private var listener: ComputerManagerListener? = null
    private val activePolls = AtomicInteger(0)
    private var pollingActive = false
    private var pollExecutor: ScheduledExecutorService? = null
    private val defaultNetworkLock: Lock = ReentrantLock()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var discoveryBinder: DiscoveryService.DiscoveryBinder? = null
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val discoveryServiceLock = Object()
    private val discoveryServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            synchronized(discoveryServiceLock) {
                val privateBinder = binder as DiscoveryService.DiscoveryBinder

                // Set us as the event listener
                privateBinder.setListener(createDiscoveryListener())

                // Signal a possible waiter that we're all setup
                discoveryBinder = privateBinder
                discoveryServiceLock.notifyAll()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            discoveryBinder = null
        }
    }

    // Returns true if the details object was modified
    @Throws(InterruptedException::class)
    private fun runPoll(details: ComputerDetails, newPc: Boolean, offlineCount: Int): Boolean {
        if (!getLocalDatabaseReference()) {
            return false
        }

        val pollTriesBeforeOffline =
            if (details.state == ComputerDetails.State.UNKNOWN) INITIAL_POLL_TRIES else OFFLINE_POLL_TRIES

        activePolls.incrementAndGet()

        // Poll the machine
        try {
            if (!pollComputer(details)) {
                if (!newPc && offlineCount < pollTriesBeforeOffline) {
                    // Return without calling the listener
                    releaseLocalDatabaseReference()
                    return false
                }

                details.state = ComputerDetails.State.OFFLINE
            }
        } catch (e: InterruptedException) {
            releaseLocalDatabaseReference()
            throw e
        } finally {
            activePolls.decrementAndGet()
        }

        // If it's online, update our persistent state
        if (details.state == ComputerDetails.State.ONLINE) {
            val existingComputer = dbManager.getComputerByUUID(details.uuid)

            // Check if it's in the database because it could have been
            // removed after this was issued
            if (!newPc && existingComputer == null) {
                // It's gone
                releaseLocalDatabaseReference()
                return false
            }

            // If we already have an entry for this computer in the DB, we must
            // combine the existing data with this new data (which may be partially available
            // due to detecting the PC via mDNS) without the saved external address. If we
            // write to the DB without doing this first, we can overwrite our existing data.
            if (existingComputer != null) {
                existingComputer.update(details)
                dbManager.updateComputer(existingComputer)
            } else {
                try {
                    // If the active address is a site-local address (RFC 1918),
                    // then use STUN to populate the external address field if
                    // it's not set already.
                    if (details.remoteAddress == null) {
                        val addr = InetAddress.getByName(details.activeAddress!!.address)
                        if (addr.isSiteLocalAddress) {
                            populateExternalAddress(details)
                        }
                    }
                } catch (_: UnknownHostException) {
                }

                dbManager.updateComputer(details)
            }
        }

        // Don't call the listener if this is a failed lookup of a new PC
        if ((!newPc || details.state == ComputerDetails.State.ONLINE) && listener != null) {
            listener!!.notifyComputerUpdated(details)
        }

        releaseLocalDatabaseReference()
        return true
    }

    private fun schedulePolling(tuple: PollingTuple): ScheduledFuture<*>? {
        val executor = pollExecutor
        if (executor == null || executor.isShutdown) {
            return null
        }
        return executor.scheduleWithFixedDelay(
            {
                if (!pollingActive) return@scheduleWithFixedDelay
                try {
                    // Only allow one request to the machine at a time
                    synchronized(tuple.networkLock) {
                        if (!runPoll(tuple.computer, false, tuple.offlineCount)) {
                            LimeLog.warning(tuple.computer.name + " is offline (try " + tuple.offlineCount + ")")
                            tuple.offlineCount++
                        } else {
                            tuple.lastSuccessfulPollMs = SystemClock.elapsedRealtime()
                            tuple.offlineCount = 0
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
            0,
            SERVERINFO_POLLING_PERIOD_MS.toLong(),
            TimeUnit.MILLISECONDS,
        )
    }

    inner class ComputerManagerBinder : Binder() {
        fun startPolling(listener: ComputerManagerListener) {
            // Polling is active
            pollingActive = true

            // Set the listener
            this@ComputerManagerService.listener = listener

            // Create shared executor if needed (4 threads handles typical home setups)
            val executor = pollExecutor
            if (executor == null || executor.isShutdown) {
                pollExecutor = Executors.newScheduledThreadPool(4) { r ->
                    Thread(r).apply {
                        name = "Nova-Poll-$id"
                        isDaemon = true
                    }
                }
            }

            // Start mDNS autodiscovery too
            discoveryBinder!!.startDiscovery(MDNS_QUERY_PERIOD_MS)

            for (tuple in pollingTuples.values) {
                // Enforce the poll data TTL
                if (SystemClock.elapsedRealtime() - tuple.lastSuccessfulPollMs > POLL_DATA_TTL_MS) {
                    LimeLog.info("Timing out polled state for " + tuple.computer.name)
                    tuple.computer.state = ComputerDetails.State.UNKNOWN
                }

                // Report this computer initially
                listener.notifyComputerUpdated(tuple.computer)

                // Schedule polling if not already running
                if (tuple.future == null || tuple.future!!.isDone) {
                    tuple.future = schedulePolling(tuple)
                }
            }
        }

        fun waitForReady() {
            synchronized(discoveryServiceLock) {
                try {
                    while (discoveryBinder == null) {
                        // Wait for the bind notification
                        discoveryServiceLock.wait(1000)
                    }
                } catch (e: InterruptedException) {
                    e.printStackTrace()

                    // InterruptedException clears the thread's interrupt status. Since we can't
                    // handle that here, we will re-interrupt the thread to set the interrupt
                    // status back to true.
                    Thread.currentThread().interrupt()
                }
            }
        }

        fun waitForPollingStopped() {
            val executor = pollExecutor
            if (executor != null && !executor.isShutdown) {
                try {
                    executor.awaitTermination(3, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            // Fallback: wait for any in-flight polls to finish
            var waitMs = 0
            while (activePolls.get() != 0 && waitMs < 3000) {
                try {
                    Thread.sleep(100)
                    waitMs += 100
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        @Throws(InterruptedException::class)
        fun addComputerBlocking(fakeDetails: ComputerDetails): Boolean {
            return this@ComputerManagerService.addComputerBlocking(fakeDetails)
        }

        fun removeComputer(computer: ComputerDetails) {
            this@ComputerManagerService.removeComputer(computer)
        }

        fun stopPolling() {
            // Just call the unbind handler to cleanup
            this@ComputerManagerService.onUnbind(null)
        }

        fun createAppListPoller(computer: ComputerDetails): ApplistPoller {
            return ApplistPoller(computer)
        }

        val uniqueId: String
            get() = idManager.getUniqueId()

        fun getComputer(uuid: String): ComputerDetails? {
            val tuple = pollingTuples[uuid]
            return tuple?.computer
        }

        fun pollComputerNow(uuid: String): ComputerDetails? {
            return this@ComputerManagerService.pollComputerNow(uuid)
        }

        fun persistComputer(computer: ComputerDetails?) {
            this@ComputerManagerService.persistComputer(computer)
        }

        fun persistComputerState(uuid: String) {
            this@ComputerManagerService.persistComputerState(uuid)
        }

        fun invalidateStateForComputer(uuid: String) {
            val tuple = pollingTuples[uuid]
            if (tuple != null) {
                // We need the network lock to prevent a concurrent poll
                // from wiping this change out
                synchronized(tuple.networkLock) {
                    tuple.computer.state = ComputerDetails.State.UNKNOWN
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (discoveryBinder != null) {
            // Stop mDNS autodiscovery
            discoveryBinder!!.stopDiscovery()
        }

        // Stop polling
        pollingActive = false
        if (pollExecutor != null) {
            pollExecutor!!.shutdownNow()
        }
        for (tuple in pollingTuples.values) {
            if (tuple.future != null) {
                tuple.future!!.cancel(true)
                tuple.future = null
            }
        }

        // Remove the listener
        listener = null

        return false
    }

    private fun populateExternalAddress(details: ComputerDetails) {
        var boundToNetwork = false
        val activeNetworkIsVpn = NetHelper.isActiveNetworkVpn(this)
        val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Check if we're currently connected to a VPN which may send our
        // STUN request from an unexpected interface
        if (activeNetworkIsVpn) {
            // Acquire the default network lock since we could be changing global process state
            defaultNetworkLock.lock()

            // On Lollipop or later, we can bind our process to the underlying interface
            // to ensure our STUN request goes out on that interface or not at all (which is
            // preferable to getting a VPN endpoint address back).
            val networks = connMgr.allNetworks
            for (net in networks) {
                val netCaps = connMgr.getNetworkCapabilities(net)
                if (netCaps != null) {
                    if (!netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        !netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ) {
                        // This network looks like an underlying multicast-capable transport,
                        // so let's guess that it's probably where our mDNS response came from.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (connMgr.bindProcessToNetwork(net)) {
                                boundToNetwork = true
                                break
                            }
                        } else if (ConnectivityManager.setProcessDefaultNetwork(net)) {
                            boundToNetwork = true
                            break
                        }
                    }
                }
            }

            // Perform the STUN request if we're not on a VPN or if we bound to a network
            if (!activeNetworkIsVpn || boundToNetwork) {
                val stunResolvedAddress = NvConnection.findExternalAddressForMdns("stun.moonlight-stream.org", 3478)
                if (stunResolvedAddress != null) {
                    // We don't know for sure what the external port is, so we will have to guess.
                    // When we contact the PC (if we haven't already), it will update the port.
                    details.remoteAddress = ComputerDetails.AddressTuple(
                        stunResolvedAddress,
                        details.guessExternalPort(),
                    )
                }
            }

            // Unbind from the network
            if (boundToNetwork) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    connMgr.bindProcessToNetwork(null)
                } else {
                    ConnectivityManager.setProcessDefaultNetwork(null)
                }
            }

            // Unlock the network state
            if (activeNetworkIsVpn) {
                defaultNetworkLock.unlock()
            }
        }
    }

    private fun createDiscoveryListener(): MdnsDiscoveryListener {
        return object : MdnsDiscoveryListener {
            override fun notifyComputerAdded(computer: MdnsComputer) {
                val details = ComputerDetails()

                // Populate the computer template with mDNS info
                if (computer.getLocalAddress() != null) {
                    details.localAddress = ComputerDetails.AddressTuple(
                        computer.getLocalAddress()!!.hostAddress,
                        computer.getPort(),
                    )

                    // Since we're on the same network, we can use STUN to find
                    // our WAN address, which is also very likely the WAN address
                    // of the PC. We can use this later to connect remotely.
                    if (computer.getLocalAddress() is Inet4Address) {
                        populateExternalAddress(details)
                    }
                }
                if (computer.getIpv6Address() != null) {
                    details.ipv6Address = ComputerDetails.AddressTuple(
                        computer.getIpv6Address()!!.hostAddress,
                        computer.getPort(),
                    )
                }

                try {
                    // Kick off a blocking serverinfo poll on this machine
                    if (!addComputerBlocking(details)) {
                        LimeLog.warning("Auto-discovered PC failed to respond: $details")
                    }
                } catch (e: InterruptedException) {
                    e.printStackTrace()

                    // InterruptedException clears the thread's interrupt status. Since we can't
                    // handle that here, we will re-interrupt the thread to set the interrupt
                    // status back to true.
                    Thread.currentThread().interrupt()
                }
            }

            override fun notifyDiscoveryFailure(e: Exception) {
                LimeLog.severe("mDNS discovery failed")
                e.printStackTrace()
            }
        }
    }

    private fun addTuple(details: ComputerDetails) {
        val existing = pollingTuples[details.uuid]
        if (existing != null) {
            // Update the saved computer with potentially new details
            existing.computer.update(details)

            // Schedule polling if active and not already running
            if (pollingActive && (existing.future == null || existing.future!!.isDone)) {
                existing.future = schedulePolling(existing)
            }
            return
        }

        // New entry
        val tuple = PollingTuple(details)
        if (pollingActive) {
            tuple.future = schedulePolling(tuple)
        }
        pollingTuples[details.uuid] = tuple
    }

    @Throws(InterruptedException::class)
    fun addComputerBlocking(fakeDetails: ComputerDetails): Boolean {
        // Block while we try to fill the details

        // We cannot use runPoll() here because it will attempt to persist the state of the machine
        // in the database, which would be bad because we don't have our pinned cert loaded yet.
        if (pollComputer(fakeDetails)) {
            // See if we have record of this PC to pull its pinned cert
            val existing = pollingTuples[fakeDetails.uuid]
            if (existing != null) {
                fakeDetails.serverCert = existing.computer.serverCert
            }

            // Poll again, possibly with the pinned cert, to get accurate pairing information.
            // This will insert the host into the database too.
            runPoll(fakeDetails, true, 0)
        }

        // If the machine is reachable, it was successful
        return if (fakeDetails.state == ComputerDetails.State.ONLINE) {
            LimeLog.info("New PC (" + fakeDetails.name + ") is UUID " + fakeDetails.uuid)

            // Start a polling thread for this machine
            addTuple(fakeDetails)
            true
        } else {
            false
        }
    }

    fun removeComputer(computer: ComputerDetails) {
        if (!getLocalDatabaseReference()) {
            return
        }

        // Remove it from the database
        dbManager.deleteComputer(computer)

        val removed = pollingTuples.remove(computer.uuid)
        if (removed != null && removed.future != null) {
            removed.future!!.cancel(true)
            removed.future = null
        }

        releaseLocalDatabaseReference()
    }

    private fun pollComputerNow(uuid: String): ComputerDetails? {
        val tuple = pollingTuples[uuid] ?: return null
        return synchronized(tuple.networkLock) {
            try {
                if (runPoll(tuple.computer, false, tuple.offlineCount)) {
                    tuple.lastSuccessfulPollMs = SystemClock.elapsedRealtime()
                    tuple.offlineCount = 0
                } else {
                    tuple.offlineCount++
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            ComputerDetails(tuple.computer)
        }
    }

    private fun persistComputerState(uuid: String) {
        if (!getLocalDatabaseReference()) {
            return
        }

        try {
            val tuple = pollingTuples[uuid] ?: return

            synchronized(tuple.networkLock) {
                dbManager.updateComputer(tuple.computer)
            }
        } finally {
            releaseLocalDatabaseReference()
        }
    }

    private fun persistComputer(computer: ComputerDetails?) {
        if (computer == null || !getLocalDatabaseReference()) {
            return
        }

        try {
            dbManager.updateComputer(computer)
        } finally {
            releaseLocalDatabaseReference()
        }
    }

    private fun getLocalDatabaseReference(): Boolean {
        if (dbRefCount.get() == 0) {
            return false
        }

        dbRefCount.incrementAndGet()
        return true
    }

    private fun releaseLocalDatabaseReference() {
        if (dbRefCount.decrementAndGet() == 0) {
            dbManager.close()
        }
    }

    private fun tryPollIp(details: ComputerDetails, address: ComputerDetails.AddressTuple): ComputerDetails? {
        return try {
            // If the current address's port number matches the active address's port number, we can also assume
            // the HTTPS port will also match. This assumption is currently safe because Sunshine sets all ports
            // as offsets from the base HTTP port and doesn't allow custom HttpsPort responses for WAN vs LAN.
            val portMatchesActiveAddress =
                details.state == ComputerDetails.State.ONLINE &&
                    details.activeAddress != null &&
                    address.port == details.activeAddress!!.port

            val http = NvHTTP(
                address,
                if (portMatchesActiveAddress) details.httpsPort else 0,
                idManager.getUniqueId(),
                details.serverCert,
                PlatformBinding.getCryptoProvider(this@ComputerManagerService),
            )

            // If this PC is currently online at this address, extend the timeouts to allow more time for the PC to respond.
            val isLikelyOnline =
                details.state == ComputerDetails.State.ONLINE && address == details.activeAddress

            val newDetails = http.getComputerDetails(isLikelyOnline)

            val returnedUuid: String? = newDetails.uuid
            val expectedUuid: String? = details.uuid

            // Check if this is the PC we expected
            if (returnedUuid == null) {
                LimeLog.severe("Polling returned no UUID!")
                null
            } else if (!isExpectedComputerUuid(expectedUuid, returnedUuid)) {
                // We got the wrong PC!
                LimeLog.info("Polling returned the wrong PC!")
                null
            } else {
                newDetails
            }
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            null
        } catch (e: IOException) {
            if (e is InterruptedIOException) {
                Thread.currentThread().interrupt()
            }
            null
        } catch (e: Exception) {
            if (e is InterruptedException || Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                null
            } else {
                LimeLog.warning("Parallel poll failed for $address: $e")
                null
            }
        }
    }

    private fun isExpectedComputerUuid(expectedUuid: String?, returnedUuid: String?): Boolean {
        return returnedUuid != null && (expectedUuid.isNullOrEmpty() || expectedUuid == returnedUuid)
    }

    private class ParallelPollTuple(
        val address: ComputerDetails.AddressTuple?,
        val existingDetails: ComputerDetails,
    ) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val completionLock = Object()
        var complete = false
        var pollingThread: Thread? = null
        var returnedDetails: ComputerDetails? = null

        fun interrupt() {
            pollingThread?.interrupt()
        }
    }

    private fun startParallelPollThread(
        tuple: ParallelPollTuple,
        uniqueAddresses: HashSet<ComputerDetails.AddressTuple>,
    ) {
        // Don't bother starting a polling thread for an address that doesn't exist
        // or if the address has already been polled with an earlier tuple
        val address = tuple.address
        if (address == null || !uniqueAddresses.add(address)) {
            tuple.complete = true
            tuple.returnedDetails = null
            return
        }

        tuple.pollingThread = object : Thread() {
            override fun run() {
                val details = tryPollIp(tuple.existingDetails, address)

                synchronized(tuple.completionLock) {
                    tuple.complete = true // Done
                    tuple.returnedDetails = details // Polling result

                    tuple.completionLock.notify()
                }
            }
        }
        tuple.pollingThread!!.name = "Parallel Poll - $address - " + tuple.existingDetails.name
        tuple.pollingThread!!.start()
    }

    @Throws(InterruptedException::class)
    private fun parallelPollPc(details: ComputerDetails): ComputerDetails? {
        val localInfo = ParallelPollTuple(details.localAddress, details)
        val manualInfo = ParallelPollTuple(details.manualAddress, details)
        val remoteInfo = ParallelPollTuple(details.remoteAddress, details)
        val ipv6Info = ParallelPollTuple(details.ipv6Address, details)

        // These must be started in order of precedence for the deduplication algorithm
        // to result in the correct behavior.
        val uniqueAddresses = HashSet<ComputerDetails.AddressTuple>()
        startParallelPollThread(localInfo, uniqueAddresses)
        startParallelPollThread(manualInfo, uniqueAddresses)
        startParallelPollThread(remoteInfo, uniqueAddresses)
        startParallelPollThread(ipv6Info, uniqueAddresses)

        try {
            // Check local first
            synchronized(localInfo.completionLock) {
                while (!localInfo.complete) {
                    localInfo.completionLock.wait()
                }

                if (localInfo.returnedDetails != null) {
                    localInfo.returnedDetails!!.activeAddress = localInfo.address
                    return localInfo.returnedDetails
                }
            }

            // Now manual
            synchronized(manualInfo.completionLock) {
                while (!manualInfo.complete) {
                    manualInfo.completionLock.wait()
                }

                if (manualInfo.returnedDetails != null) {
                    manualInfo.returnedDetails!!.activeAddress = manualInfo.address
                    return manualInfo.returnedDetails
                }
            }

            // Now remote IPv4
            synchronized(remoteInfo.completionLock) {
                while (!remoteInfo.complete) {
                    remoteInfo.completionLock.wait()
                }

                if (remoteInfo.returnedDetails != null) {
                    remoteInfo.returnedDetails!!.activeAddress = remoteInfo.address
                    return remoteInfo.returnedDetails
                }
            }

            // Now global IPv6
            synchronized(ipv6Info.completionLock) {
                while (!ipv6Info.complete) {
                    ipv6Info.completionLock.wait()
                }

                if (ipv6Info.returnedDetails != null) {
                    ipv6Info.returnedDetails!!.activeAddress = ipv6Info.address
                    return ipv6Info.returnedDetails
                }
            }
        } finally {
            // Stop any further polling if we've found a working address or we've been
            // interrupted by an attempt to stop polling.
            localInfo.interrupt()
            manualInfo.interrupt()
            remoteInfo.interrupt()
            ipv6Info.interrupt()
        }

        return null
    }

    @Throws(InterruptedException::class)
    private fun pollComputer(details: ComputerDetails): Boolean {
        // Poll all addresses in parallel to speed up the process
        LimeLog.info(
            "Starting parallel poll for " + details.name + " (" + details.localAddress + ", " +
                details.remoteAddress + ", " + details.manualAddress + ", " + details.ipv6Address + ")",
        )
        val polledDetails = parallelPollPc(details)
        LimeLog.info("Parallel poll for " + details.name + " returned address: " + details.activeAddress)

        return if (polledDetails != null) {
            details.update(polledDetails)
            true
        } else {
            false
        }
    }

    override fun onCreate() {
        // Bind to the discovery service
        bindService(
            Intent(this, DiscoveryService::class.java),
            discoveryServiceConnection,
            BIND_AUTO_CREATE,
        )

        // Lookup or generate this device's UID
        idManager = IdentityManager(this)

        // Initialize the DB
        dbManager = ComputerDatabaseManager(this)
        dbRefCount.set(1)

        // Grab known machines into our computer list
        if (!getLocalDatabaseReference()) {
            return
        }

        for (computer in dbManager.getAllComputers()) {
            // Add tuples for each computer
            addTuple(computer)
        }

        releaseLocalDatabaseReference()

        // Monitor for network changes to invalidate our PC state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    LimeLog.info("Resetting PC state for new available network")
                    for (tuple in pollingTuples.values) {
                        tuple.computer.state = ComputerDetails.State.UNKNOWN
                        if (listener != null) {
                            listener!!.notifyComputerUpdated(tuple.computer)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    LimeLog.info("Offlining PCs due to network loss")
                    for (tuple in pollingTuples.values) {
                        tuple.computer.state = ComputerDetails.State.OFFLINE
                        if (listener != null) {
                            listener!!.notifyComputerUpdated(tuple.computer)
                        }
                    }
                }
            }

            val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connMgr.registerDefaultNetworkCallback(networkCallback!!)
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connMgr.unregisterNetworkCallback(networkCallback!!)
        }

        if (discoveryBinder != null) {
            // Unbind from the discovery service
            unbindService(discoveryServiceConnection)
        }

        // FIXME: Should await termination here but we have timeout issues in HttpURLConnection

        // Remove the initial DB reference
        releaseLocalDatabaseReference()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    inner class ApplistPoller(private val computer: ComputerDetails) {
        private var thread: Thread? = null
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private val pollEvent = Object()
        private var receivedAppList = false

        fun pollNow() {
            synchronized(pollEvent) {
                pollEvent.notify()
            }
        }

        private fun waitPollingDelay(): Boolean {
            try {
                synchronized(pollEvent) {
                    if (receivedAppList) {
                        // If we've already reported an app list successfully,
                        // wait the full polling period
                        pollEvent.wait(APPLIST_POLLING_PERIOD_MS.toLong())
                    } else {
                        // If we've failed to get an app list so far, retry much earlier
                        pollEvent.wait(APPLIST_FAILED_POLLING_RETRY_MS.toLong())
                    }
                }
            } catch (_: InterruptedException) {
                return false
            }

            return thread != null && !thread!!.isInterrupted
        }

        private fun getPollingTuple(details: ComputerDetails): PollingTuple? {
            return pollingTuples[details.uuid]
        }

        fun start() {
            thread = object : Thread() {
                override fun run() {
                    var emptyAppListResponses = 0
                    do {
                        // Can't poll if it's not online or paired
                        if (computer.state != ComputerDetails.State.ONLINE ||
                            computer.pairState != PairingManager.PairState.PAIRED
                        ) {
                            if (listener != null) {
                                listener!!.notifyComputerUpdated(computer)
                            }
                            continue
                        }

                        // Can't poll if there's no UUID yet
                        val computerUuid: String? = computer.uuid
                        if (computerUuid == null) {
                            continue
                        }

                        val tuple = getPollingTuple(computer)

                        try {
                            val http = NvHTTP(
                                ServerHelper.getCurrentAddressFromComputer(computer),
                                computer.httpsPort,
                                idManager.getUniqueId(),
                                computer.serverCert,
                                PlatformBinding.getCryptoProvider(this@ComputerManagerService),
                            )

                            val appList: String = if (tuple != null) {
                                // If we're polling this machine too, grab the network lock
                                // while doing the app list request to prevent other requests
                                // from being issued in the meantime.
                                synchronized(tuple.networkLock) {
                                    http.getAppListRaw()
                                }
                            } else {
                                // No polling is happening now, so we just call it directly
                                http.getAppListRaw()
                            }

                            val list: List<NvApp> = NvHTTP.getAppListByReader(StringReader(appList))
                            if (list.isEmpty()) {
                                LimeLog.warning("Empty app list received from " + computer.uuid)

                                // The app list might actually be empty, so if we get an empty response a few times
                                // in a row, we'll go ahead and believe it.
                                emptyAppListResponses++
                            }
                            if (appList.isNotEmpty() &&
                                (list.isNotEmpty() || emptyAppListResponses >= EMPTY_LIST_THRESHOLD)
                            ) {
                                // Open the cache file
                                try {
                                    val cacheOut: OutputStream = CacheHelper.openCacheFileForOutput(
                                        cacheDir,
                                        "applist",
                                        computer.uuid,
                                    )
                                    cacheOut.use {
                                        CacheHelper.writeStringToOutputStream(it, appList)
                                    }
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                }

                                // Reset empty count if it wasn't empty this time
                                if (list.isNotEmpty()) {
                                    emptyAppListResponses = 0
                                }

                                // Update the computer
                                computer.rawAppList = appList
                                receivedAppList = true

                                // Notify that the app list has been updated
                                // and ensure that the thread is still active
                                if (listener != null && thread != null) {
                                    listener!!.notifyComputerUpdated(computer)
                                }
                            } else if (appList.isEmpty()) {
                                LimeLog.warning("Null app list received from " + computer.uuid)
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } catch (e: XmlPullParserException) {
                            e.printStackTrace()
                        }
                    } while (waitPollingDelay())
                }
            }
            thread!!.name = "App list polling thread for " + computer.name
            thread!!.start()
        }

        fun stop() {
            if (thread != null) {
                thread!!.interrupt()

                // Don't join here because we might be blocked on network I/O

                thread = null
            }
        }
    }

    companion object {
        private const val SERVERINFO_POLLING_PERIOD_MS = 1500
        private const val APPLIST_POLLING_PERIOD_MS = 30000
        private const val APPLIST_FAILED_POLLING_RETRY_MS = 2000
        private const val MDNS_QUERY_PERIOD_MS = 1000
        private const val OFFLINE_POLL_TRIES = 3
        private const val INITIAL_POLL_TRIES = 2
        private const val EMPTY_LIST_THRESHOLD = 3
        private const val POLL_DATA_TTL_MS = 30000
    }
}

class PollingTuple(@JvmField val computer: ComputerDetails) {
    @JvmField
    @Volatile
    var future: ScheduledFuture<*>? = null

    @JvmField
    val networkLock: Any = Any()

    @JvmField
    var lastSuccessfulPollMs: Long = 0

    @JvmField
    var offlineCount: Int = 0
}

class ReachabilityTuple(
    @JvmField val computer: ComputerDetails,
    @JvmField val reachableAddress: String,
)

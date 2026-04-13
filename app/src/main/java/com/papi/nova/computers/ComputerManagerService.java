package com.papi.nova.computers;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.papi.nova.LimeLog;
import com.papi.nova.binding.PlatformBinding;
import com.papi.nova.discovery.DiscoveryService;
import com.papi.nova.nvstream.NvConnection;
import com.papi.nova.nvstream.http.ComputerDetails;
import com.papi.nova.nvstream.http.NvApp;
import com.papi.nova.nvstream.http.NvHTTP;
import com.papi.nova.nvstream.http.PairingManager;
import com.papi.nova.nvstream.mdns.MdnsComputer;
import com.papi.nova.nvstream.mdns.MdnsDiscoveryListener;
import com.papi.nova.utils.CacheHelper;
import com.papi.nova.utils.NetHelper;
import com.papi.nova.utils.ServerHelper;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import org.xmlpull.v1.XmlPullParserException;

public class ComputerManagerService extends Service {
    private static final int SERVERINFO_POLLING_PERIOD_MS = 1500;
    private static final int APPLIST_POLLING_PERIOD_MS = 30000;
    private static final int APPLIST_FAILED_POLLING_RETRY_MS = 2000;
    private static final int MDNS_QUERY_PERIOD_MS = 1000;
    private static final int OFFLINE_POLL_TRIES = 3;
    private static final int INITIAL_POLL_TRIES = 2;
    private static final int EMPTY_LIST_THRESHOLD = 3;
    private static final int POLL_DATA_TTL_MS = 30000;

    private final ComputerManagerBinder binder = new ComputerManagerBinder();

    private ComputerDatabaseManager dbManager;
    private final AtomicInteger dbRefCount = new AtomicInteger(0);

    private IdentityManager idManager;
    private final ConcurrentHashMap<String, PollingTuple> pollingTuples = new ConcurrentHashMap<>();
    private ComputerManagerListener listener = null;
    private final AtomicInteger activePolls = new AtomicInteger(0);
    private boolean pollingActive = false;
    private ScheduledExecutorService pollExecutor;
    private final Lock defaultNetworkLock = new ReentrantLock();

    private ConnectivityManager.NetworkCallback networkCallback;

    private DiscoveryService.DiscoveryBinder discoveryBinder;
    private final ServiceConnection discoveryServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            synchronized (discoveryServiceConnection) {
                DiscoveryService.DiscoveryBinder privateBinder = ((DiscoveryService.DiscoveryBinder)binder);

                // Set us as the event listener
                privateBinder.setListener(createDiscoveryListener());

                // Signal a possible waiter that we're all setup
                discoveryBinder = privateBinder;
                discoveryServiceConnection.notifyAll();
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            discoveryBinder = null;
        }
    };

    // Returns true if the details object was modified
    private boolean runPoll(ComputerDetails details, boolean newPc, int offlineCount) throws InterruptedException {
        if (!getLocalDatabaseReference()) {
            return false;
        }

        final int pollTriesBeforeOffline = details.state == ComputerDetails.State.UNKNOWN ?
                INITIAL_POLL_TRIES : OFFLINE_POLL_TRIES;

        activePolls.incrementAndGet();

        // Poll the machine
        try {
            if (!pollComputer(details)) {
                if (!newPc && offlineCount < pollTriesBeforeOffline) {
                    // Return without calling the listener
                    releaseLocalDatabaseReference();
                    return false;
                }

                details.state = ComputerDetails.State.OFFLINE;
            }
        } catch (InterruptedException e) {
            releaseLocalDatabaseReference();
            throw e;
        } finally {
            activePolls.decrementAndGet();
        }

        // If it's online, update our persistent state
        if (details.state == ComputerDetails.State.ONLINE) {
            ComputerDetails existingComputer = dbManager.getComputerByUUID(details.uuid);

            // Check if it's in the database because it could have been
            // removed after this was issued
            if (!newPc && existingComputer == null) {
                // It's gone
                releaseLocalDatabaseReference();
                return false;
            }

            // If we already have an entry for this computer in the DB, we must
            // combine the existing data with this new data (which may be partially available
            // due to detecting the PC via mDNS) without the saved external address. If we
            // write to the DB without doing this first, we can overwrite our existing data.
            if (existingComputer != null) {
                existingComputer.update(details);
                dbManager.updateComputer(existingComputer);
            }
            else {
                try {
                    // If the active address is a site-local address (RFC 1918),
                    // then use STUN to populate the external address field if
                    // it's not set already.
                    if (details.remoteAddress == null) {
                        InetAddress addr = InetAddress.getByName(details.activeAddress.address);
                        if (addr.isSiteLocalAddress()) {
                            populateExternalAddress(details);
                        }
                    }
                } catch (UnknownHostException ignored) {}

                dbManager.updateComputer(details);
            }
        }

        // Don't call the listener if this is a failed lookup of a new PC
        if ((!newPc || details.state == ComputerDetails.State.ONLINE) && listener != null) {
            listener.notifyComputerUpdated(details);
        }

        releaseLocalDatabaseReference();
        return true;
    }

    private ScheduledFuture<?> schedulePolling(final PollingTuple tuple) {
        if (pollExecutor == null || pollExecutor.isShutdown()) {
            return null;
        }
        return pollExecutor.scheduleWithFixedDelay(() -> {
            if (!pollingActive) return;
            try {
                // Only allow one request to the machine at a time
                synchronized (tuple.networkLock) {
                    if (!runPoll(tuple.computer, false, tuple.offlineCount)) {
                        LimeLog.warning(tuple.computer.name + " is offline (try " + tuple.offlineCount + ")");
                        tuple.offlineCount++;
                    } else {
                        tuple.lastSuccessfulPollMs = SystemClock.elapsedRealtime();
                        tuple.offlineCount = 0;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 0, SERVERINFO_POLLING_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public class ComputerManagerBinder extends Binder {
        public void startPolling(ComputerManagerListener listener) {
            // Polling is active
            pollingActive = true;

            // Set the listener
            ComputerManagerService.this.listener = listener;

            // Create shared executor if needed (4 threads handles typical home setups)
            if (pollExecutor == null || pollExecutor.isShutdown()) {
                pollExecutor = Executors.newScheduledThreadPool(4, r -> {
                    Thread t = new Thread(r);
                    t.setName("Nova-Poll-" + t.getId());
                    t.setDaemon(true);
                    return t;
                });
            }

            // Start mDNS autodiscovery too
            discoveryBinder.startDiscovery(MDNS_QUERY_PERIOD_MS);

            for (PollingTuple tuple : pollingTuples.values()) {
                // Enforce the poll data TTL
                if (SystemClock.elapsedRealtime() - tuple.lastSuccessfulPollMs > POLL_DATA_TTL_MS) {
                    LimeLog.info("Timing out polled state for "+tuple.computer.name);
                    tuple.computer.state = ComputerDetails.State.UNKNOWN;
                }

                // Report this computer initially
                listener.notifyComputerUpdated(tuple.computer);

                // Schedule polling if not already running
                if (tuple.future == null || tuple.future.isDone()) {
                    tuple.future = schedulePolling(tuple);
                }
            }
        }

        public void waitForReady() {
            synchronized (discoveryServiceConnection) {
                try {
                    while (discoveryBinder == null) {
                        // Wait for the bind notification
                        discoveryServiceConnection.wait(1000);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();

                    // InterruptedException clears the thread's interrupt status. Since we can't
                    // handle that here, we will re-interrupt the thread to set the interrupt
                    // status back to true.
                    Thread.currentThread().interrupt();
                }
            }
        }

        public void waitForPollingStopped() {
            if (pollExecutor != null && !pollExecutor.isShutdown()) {
                try {
                    pollExecutor.awaitTermination(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Fallback: wait for any in-flight polls to finish
            int waitMs = 0;
            while (activePolls.get() != 0 && waitMs < 3000) {
                try {
                    Thread.sleep(100);
                    waitMs += 100;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        public boolean addComputerBlocking(ComputerDetails fakeDetails) throws InterruptedException {
            return ComputerManagerService.this.addComputerBlocking(fakeDetails);
        }

        public void removeComputer(ComputerDetails computer) {
            ComputerManagerService.this.removeComputer(computer);
        }

        public void stopPolling() {
            // Just call the unbind handler to cleanup
            ComputerManagerService.this.onUnbind(null);
        }

        public ApplistPoller createAppListPoller(ComputerDetails computer) {
            return new ApplistPoller(computer);
        }

        public String getUniqueId() {
            return idManager.getUniqueId();
        }

        public ComputerDetails getComputer(String uuid) {
            PollingTuple tuple = pollingTuples.get(uuid);
            return tuple != null ? tuple.computer : null;
        }

        public void persistComputer(ComputerDetails computer) {
            ComputerManagerService.this.persistComputer(computer);
        }

        public void persistComputerState(String uuid) {
            ComputerManagerService.this.persistComputerState(uuid);
        }

        public void invalidateStateForComputer(String uuid) {
            PollingTuple tuple = pollingTuples.get(uuid);
            if (tuple != null) {
                // We need the network lock to prevent a concurrent poll
                // from wiping this change out
                synchronized (tuple.networkLock) {
                    tuple.computer.state = ComputerDetails.State.UNKNOWN;
                }
            }
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (discoveryBinder != null) {
            // Stop mDNS autodiscovery
            discoveryBinder.stopDiscovery();
        }

        // Stop polling
        pollingActive = false;
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
        }
        for (PollingTuple tuple : pollingTuples.values()) {
            if (tuple.future != null) {
                tuple.future.cancel(true);
                tuple.future = null;
            }
        }

        // Remove the listener
        listener = null;

        return false;
    }

    private void populateExternalAddress(ComputerDetails details) {
        boolean boundToNetwork = false;
        boolean activeNetworkIsVpn = NetHelper.isActiveNetworkVpn(this);
        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        // Check if we're currently connected to a VPN which may send our
        // STUN request from an unexpected interface
        if (activeNetworkIsVpn) {
            // Acquire the default network lock since we could be changing global process state
            defaultNetworkLock.lock();

            // On Lollipop or later, we can bind our process to the underlying interface
            // to ensure our STUN request goes out on that interface or not at all (which is
            // preferable to getting a VPN endpoint address back).
            Network[] networks = connMgr.getAllNetworks();
            for (Network net : networks) {
                NetworkCapabilities netCaps = connMgr.getNetworkCapabilities(net);
                if (netCaps != null) {
                    if (!netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                            !netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        // This network looks like an underlying multicast-capable transport,
                        // so let's guess that it's probably where our mDNS response came from.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (connMgr.bindProcessToNetwork(net)) {
                                boundToNetwork = true;
                                break;
                            }
                        } else if (ConnectivityManager.setProcessDefaultNetwork(net)) {
                            boundToNetwork = true;
                            break;
                        }
                    }
                }
            }

            // Perform the STUN request if we're not on a VPN or if we bound to a network
            if (!activeNetworkIsVpn || boundToNetwork) {
                String stunResolvedAddress = NvConnection.findExternalAddressForMdns("stun.moonlight-stream.org", 3478);
                if (stunResolvedAddress != null) {
                    // We don't know for sure what the external port is, so we will have to guess.
                    // When we contact the PC (if we haven't already), it will update the port.
                    details.remoteAddress = new ComputerDetails.AddressTuple(stunResolvedAddress, details.guessExternalPort());
                }
            }

            // Unbind from the network
            if (boundToNetwork) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    connMgr.bindProcessToNetwork(null);
                } else {
                    ConnectivityManager.setProcessDefaultNetwork(null);
                }
            }

            // Unlock the network state
            if (activeNetworkIsVpn) {
                defaultNetworkLock.unlock();
            }
        }
    }

    private MdnsDiscoveryListener createDiscoveryListener() {
        return new MdnsDiscoveryListener() {
            @Override
            public void notifyComputerAdded(MdnsComputer computer) {
                ComputerDetails details = new ComputerDetails();

                // Populate the computer template with mDNS info
                if (computer.getLocalAddress() != null) {
                    details.localAddress = new ComputerDetails.AddressTuple(computer.getLocalAddress().getHostAddress(), computer.getPort());

                    // Since we're on the same network, we can use STUN to find
                    // our WAN address, which is also very likely the WAN address
                    // of the PC. We can use this later to connect remotely.
                    if (computer.getLocalAddress() instanceof Inet4Address) {
                        populateExternalAddress(details);
                    }
                }
                if (computer.getIpv6Address() != null) {
                    details.ipv6Address = new ComputerDetails.AddressTuple(computer.getIpv6Address().getHostAddress(), computer.getPort());
                }

                try {
                    // Kick off a blocking serverinfo poll on this machine
                    if (!addComputerBlocking(details)) {
                        LimeLog.warning("Auto-discovered PC failed to respond: "+details);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();

                    // InterruptedException clears the thread's interrupt status. Since we can't
                    // handle that here, we will re-interrupt the thread to set the interrupt
                    // status back to true.
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void notifyDiscoveryFailure(Exception e) {
                LimeLog.severe("mDNS discovery failed");
                e.printStackTrace();
            }
        };
    }

    private void addTuple(ComputerDetails details) {
        PollingTuple existing = pollingTuples.get(details.uuid);
        if (existing != null) {
            // Update the saved computer with potentially new details
            existing.computer.update(details);

            // Schedule polling if active and not already running
            if (pollingActive && (existing.future == null || existing.future.isDone())) {
                existing.future = schedulePolling(existing);
            }
            return;
        }

        // New entry
        PollingTuple tuple = new PollingTuple(details);
        if (pollingActive) {
            tuple.future = schedulePolling(tuple);
        }
        pollingTuples.put(details.uuid, tuple);
    }

    public boolean addComputerBlocking(ComputerDetails fakeDetails) throws InterruptedException {
        // Block while we try to fill the details

        // We cannot use runPoll() here because it will attempt to persist the state of the machine
        // in the database, which would be bad because we don't have our pinned cert loaded yet.
        if (pollComputer(fakeDetails)) {
            // See if we have record of this PC to pull its pinned cert
            PollingTuple existing = pollingTuples.get(fakeDetails.uuid);
            if (existing != null) {
                fakeDetails.serverCert = existing.computer.serverCert;
            }

            // Poll again, possibly with the pinned cert, to get accurate pairing information.
            // This will insert the host into the database too.
            runPoll(fakeDetails, true, 0);
        }

        // If the machine is reachable, it was successful
        if (fakeDetails.state == ComputerDetails.State.ONLINE) {
            LimeLog.info("New PC ("+fakeDetails.name+") is UUID "+fakeDetails.uuid);

            // Start a polling thread for this machine
            addTuple(fakeDetails);
            return true;
        }
        else {
            return false;
        }
    }

    public void removeComputer(ComputerDetails computer) {
        if (!getLocalDatabaseReference()) {
            return;
        }

        // Remove it from the database
        dbManager.deleteComputer(computer);

        PollingTuple removed = pollingTuples.remove(computer.uuid);
        if (removed != null && removed.future != null) {
            removed.future.cancel(true);
            removed.future = null;
        }

        releaseLocalDatabaseReference();
    }

    private void persistComputerState(String uuid) {
        if (!getLocalDatabaseReference()) {
            return;
        }

        try {
            PollingTuple tuple = pollingTuples.get(uuid);
            if (tuple == null) {
                return;
            }

            synchronized (tuple.networkLock) {
                dbManager.updateComputer(tuple.computer);
            }
        } finally {
            releaseLocalDatabaseReference();
        }
    }

    private void persistComputer(ComputerDetails computer) {
        if (computer == null || !getLocalDatabaseReference()) {
            return;
        }

        try {
            dbManager.updateComputer(computer);
        } finally {
            releaseLocalDatabaseReference();
        }
    }

    private boolean getLocalDatabaseReference() {
        if (dbRefCount.get() == 0) {
            return false;
        }

        dbRefCount.incrementAndGet();
        return true;
    }

    private void releaseLocalDatabaseReference() {
        if (dbRefCount.decrementAndGet() == 0) {
            dbManager.close();
        }
    }

    private ComputerDetails tryPollIp(ComputerDetails details, ComputerDetails.AddressTuple address) {
        try {
            // If the current address's port number matches the active address's port number, we can also assume
            // the HTTPS port will also match. This assumption is currently safe because Sunshine sets all ports
            // as offsets from the base HTTP port and doesn't allow custom HttpsPort responses for WAN vs LAN.
            boolean portMatchesActiveAddress = details.state == ComputerDetails.State.ONLINE &&
                    details.activeAddress != null && address.port == details.activeAddress.port;

            NvHTTP http = new NvHTTP(address, portMatchesActiveAddress ? details.httpsPort : 0, idManager.getUniqueId(), details.serverCert,
                    PlatformBinding.getCryptoProvider(ComputerManagerService.this));

            // If this PC is currently online at this address, extend the timeouts to allow more time for the PC to respond.
            boolean isLikelyOnline = details.state == ComputerDetails.State.ONLINE && address.equals(details.activeAddress);

            ComputerDetails newDetails = http.getComputerDetails(isLikelyOnline);

            // Check if this is the PC we expected
            if (newDetails.uuid == null) {
                LimeLog.severe("Polling returned no UUID!");
                return null;
            }
            // details.uuid can be null on initial PC add
            else if (details.uuid != null && !details.uuid.equals(newDetails.uuid)) {
                // We got the wrong PC!
                LimeLog.info("Polling returned the wrong PC!");
                return null;
            }

            return newDetails;
        } catch (XmlPullParserException e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            if (e instanceof InterruptedIOException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } catch (Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return null;
            }

            LimeLog.warning("Parallel poll failed for " + address + ": " + e);
            return null;
        }
    }

    private static class ParallelPollTuple {
        public ComputerDetails.AddressTuple address;
        public ComputerDetails existingDetails;

        public boolean complete;
        public Thread pollingThread;
        public ComputerDetails returnedDetails;

        public ParallelPollTuple(ComputerDetails.AddressTuple address, ComputerDetails existingDetails) {
            this.address = address;
            this.existingDetails = existingDetails;
        }

        public void interrupt() {
            if (pollingThread != null) {
                pollingThread.interrupt();
            }
        }
    }

    private void startParallelPollThread(ParallelPollTuple tuple, HashSet<ComputerDetails.AddressTuple> uniqueAddresses) {
        // Don't bother starting a polling thread for an address that doesn't exist
        // or if the address has already been polled with an earlier tuple
        if (tuple.address == null || !uniqueAddresses.add(tuple.address)) {
            tuple.complete = true;
            tuple.returnedDetails = null;
            return;
        }

        tuple.pollingThread = new Thread() {
            @Override
            public void run() {
                ComputerDetails details = tryPollIp(tuple.existingDetails, tuple.address);

                synchronized (tuple) {
                    tuple.complete = true; // Done
                    tuple.returnedDetails = details; // Polling result

                    tuple.notify();
                }
            }
        };
        tuple.pollingThread.setName("Parallel Poll - "+tuple.address+" - "+tuple.existingDetails.name);
        tuple.pollingThread.start();
    }

    private ComputerDetails parallelPollPc(ComputerDetails details) throws InterruptedException {
        ParallelPollTuple localInfo = new ParallelPollTuple(details.localAddress, details);
        ParallelPollTuple manualInfo = new ParallelPollTuple(details.manualAddress, details);
        ParallelPollTuple remoteInfo = new ParallelPollTuple(details.remoteAddress, details);
        ParallelPollTuple ipv6Info = new ParallelPollTuple(details.ipv6Address, details);

        // These must be started in order of precedence for the deduplication algorithm
        // to result in the correct behavior.
        HashSet<ComputerDetails.AddressTuple> uniqueAddresses = new HashSet<>();
        startParallelPollThread(localInfo, uniqueAddresses);
        startParallelPollThread(manualInfo, uniqueAddresses);
        startParallelPollThread(remoteInfo, uniqueAddresses);
        startParallelPollThread(ipv6Info, uniqueAddresses);

        try {
            // Check local first
            synchronized (localInfo) {
                while (!localInfo.complete) {
                    localInfo.wait();
                }

                if (localInfo.returnedDetails != null) {
                    localInfo.returnedDetails.activeAddress = localInfo.address;
                    return localInfo.returnedDetails;
                }
            }

            // Now manual
            synchronized (manualInfo) {
                while (!manualInfo.complete) {
                    manualInfo.wait();
                }

                if (manualInfo.returnedDetails != null) {
                    manualInfo.returnedDetails.activeAddress = manualInfo.address;
                    return manualInfo.returnedDetails;
                }
            }

            // Now remote IPv4
            synchronized (remoteInfo) {
                while (!remoteInfo.complete) {
                    remoteInfo.wait();
                }

                if (remoteInfo.returnedDetails != null) {
                    remoteInfo.returnedDetails.activeAddress = remoteInfo.address;
                    return remoteInfo.returnedDetails;
                }
            }

            // Now global IPv6
            synchronized (ipv6Info) {
                while (!ipv6Info.complete) {
                    ipv6Info.wait();
                }

                if (ipv6Info.returnedDetails != null) {
                    ipv6Info.returnedDetails.activeAddress = ipv6Info.address;
                    return ipv6Info.returnedDetails;
                }
            }
        } finally {
            // Stop any further polling if we've found a working address or we've been
            // interrupted by an attempt to stop polling.
            localInfo.interrupt();
            manualInfo.interrupt();
            remoteInfo.interrupt();
            ipv6Info.interrupt();
        }

        return null;
    }

    private boolean pollComputer(ComputerDetails details) throws InterruptedException {
        // Poll all addresses in parallel to speed up the process
        LimeLog.info("Starting parallel poll for "+details.name+" ("+details.localAddress +", "+details.remoteAddress +", "+details.manualAddress+", "+details.ipv6Address+")");
        ComputerDetails polledDetails = parallelPollPc(details);
        LimeLog.info("Parallel poll for "+details.name+" returned address: "+details.activeAddress);

        if (polledDetails != null) {
            details.update(polledDetails);
            return true;
        }
        else {
            return false;
        }
    }

    @Override
    public void onCreate() {
        // Bind to the discovery service
        bindService(new Intent(this, DiscoveryService.class),
                discoveryServiceConnection, Service.BIND_AUTO_CREATE);

        // Lookup or generate this device's UID
        idManager = new IdentityManager(this);

        // Initialize the DB
        dbManager = new ComputerDatabaseManager(this);
        dbRefCount.set(1);

        // Grab known machines into our computer list
        if (!getLocalDatabaseReference()) {
            return;
        }

        for (ComputerDetails computer : dbManager.getAllComputers()) {
            // Add tuples for each computer
            addTuple(computer);
        }

        releaseLocalDatabaseReference();

        // Monitor for network changes to invalidate our PC state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    LimeLog.info("Resetting PC state for new available network");
                    for (PollingTuple tuple : pollingTuples.values()) {
                        tuple.computer.state = ComputerDetails.State.UNKNOWN;
                        if (listener != null) {
                            listener.notifyComputerUpdated(tuple.computer);
                        }
                    }
                }

                @Override
                public void onLost(Network network) {
                    LimeLog.info("Offlining PCs due to network loss");
                    for (PollingTuple tuple : pollingTuples.values()) {
                        tuple.computer.state = ComputerDetails.State.OFFLINE;
                        if (listener != null) {
                            listener.notifyComputerUpdated(tuple.computer);
                        }
                    }
                }
            };

            ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            connMgr.registerDefaultNetworkCallback(networkCallback);
        }
    }

    @Override
    public void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            connMgr.unregisterNetworkCallback(networkCallback);
        }

        if (discoveryBinder != null) {
            // Unbind from the discovery service
            unbindService(discoveryServiceConnection);
        }

        // FIXME: Should await termination here but we have timeout issues in HttpURLConnection

        // Remove the initial DB reference
        releaseLocalDatabaseReference();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public class ApplistPoller {
        private Thread thread;
        private final ComputerDetails computer;
        private final Object pollEvent = new Object();
        private boolean receivedAppList = false;

        public ApplistPoller(ComputerDetails computer) {
            this.computer = computer;
        }

        public void pollNow() {
            synchronized (pollEvent) {
                pollEvent.notify();
            }
        }

        private boolean waitPollingDelay() {
            try {
                synchronized (pollEvent) {
                    if (receivedAppList) {
                        // If we've already reported an app list successfully,
                        // wait the full polling period
                        pollEvent.wait(APPLIST_POLLING_PERIOD_MS);
                    }
                    else {
                        // If we've failed to get an app list so far, retry much earlier
                        pollEvent.wait(APPLIST_FAILED_POLLING_RETRY_MS);
                    }
                }
            } catch (InterruptedException e) {
                return false;
            }

            return thread != null && !thread.isInterrupted();
        }

        private PollingTuple getPollingTuple(ComputerDetails details) {
            return pollingTuples.get(details.uuid);
        }

        public void start() {
            thread = new Thread() {
                @Override
                public void run() {
                    int emptyAppListResponses = 0;
                    do {
                        // Can't poll if it's not online or paired
                        if (computer.state != ComputerDetails.State.ONLINE ||
                                computer.pairState != PairingManager.PairState.PAIRED) {
                            if (listener != null) {
                                listener.notifyComputerUpdated(computer);
                            }
                            continue;
                        }

                        // Can't poll if there's no UUID yet
                        if (computer.uuid == null) {
                            continue;
                        }

                        PollingTuple tuple = getPollingTuple(computer);

                        try {
                            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort, idManager.getUniqueId(),
                                    computer.serverCert, PlatformBinding.getCryptoProvider(ComputerManagerService.this));

                            String appList;
                            if (tuple != null) {
                                // If we're polling this machine too, grab the network lock
                                // while doing the app list request to prevent other requests
                                // from being issued in the meantime.
                                synchronized (tuple.networkLock) {
                                    appList = http.getAppListRaw();
                                }
                            }
                            else {
                                // No polling is happening now, so we just call it directly
                                appList = http.getAppListRaw();
                            }

                            List<NvApp> list = NvHTTP.getAppListByReader(new StringReader(appList));
                            if (list.isEmpty()) {
                                LimeLog.warning("Empty app list received from "+computer.uuid);

                                // The app list might actually be empty, so if we get an empty response a few times
                                // in a row, we'll go ahead and believe it.
                                emptyAppListResponses++;
                            }
                            if (!appList.isEmpty() &&
                                    (!list.isEmpty() || emptyAppListResponses >= EMPTY_LIST_THRESHOLD)) {
                                // Open the cache file
                                try (final OutputStream cacheOut = CacheHelper.openCacheFileForOutput(
                                        getCacheDir(), "applist", computer.uuid)
                                ) {
                                    CacheHelper.writeStringToOutputStream(cacheOut, appList);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }

                                // Reset empty count if it wasn't empty this time
                                if (!list.isEmpty()) {
                                    emptyAppListResponses = 0;
                                }

                                // Update the computer
                                computer.rawAppList = appList;
                                receivedAppList = true;

                                // Notify that the app list has been updated
                                // and ensure that the thread is still active
                                if (listener != null && thread != null) {
                                    listener.notifyComputerUpdated(computer);
                                }
                            }
                            else if (appList.isEmpty()) {
                                LimeLog.warning("Null app list received from "+computer.uuid);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (XmlPullParserException e) {
                            e.printStackTrace();
                        }
                    } while (waitPollingDelay());
                }
            };
            thread.setName("App list polling thread for " + computer.name);
            thread.start();
        }

        public void stop() {
            if (thread != null) {
                thread.interrupt();

                // Don't join here because we might be blocked on network I/O

                thread = null;
            }
        }
    }
}

class PollingTuple {
    public volatile ScheduledFuture<?> future;
    public final ComputerDetails computer;
    public final Object networkLock;
    public long lastSuccessfulPollMs;
    public int offlineCount;

    public PollingTuple(ComputerDetails computer) {
        this.computer = computer;
        this.networkLock = new Object();
    }
}

class ReachabilityTuple {
    public final String reachableAddress;
    public final ComputerDetails computer;

    public ReachabilityTuple(ComputerDetails computer, String reachableAddress) {
        this.computer = computer;
        this.reachableAddress = reachableAddress;
    }
}

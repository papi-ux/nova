package com.papi.nova.nvstream.mdns

import android.content.Context
import android.net.wifi.WifiManager
import com.papi.nova.LimeLog
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.ArrayList
import java.util.HashSet
import javax.jmdns.JmmDNS
import javax.jmdns.NetworkTopologyDiscovery
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import javax.jmdns.impl.NetworkTopologyDiscoveryImpl

open class JmDNSDiscoveryAgent(
    context: Context,
    listener: MdnsDiscoveryListener,
) : MdnsDiscoveryAgent(listener), ServiceListener {
    private val multicastLock: WifiManager.MulticastLock
    private var discoveryThread: Thread? = null
    private val pendingResolution = HashSet<String>()

    init {
        // Create the multicast lock required to receive mDNS traffic
        val wifiMgr = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiMgr.createMulticastLock("Limelight mDNS")
        multicastLock.setReferenceCounted(false)
    }

    open class MyNetworkTopologyDiscovery : NetworkTopologyDiscoveryImpl() {
        override fun useInetAddress(
            networkInterface: NetworkInterface,
            interfaceAddress: InetAddress,
        ): Boolean {
            // This is an copy of jmDNS's implementation, except we omit the multicast check, since
            // it seems at least some devices lie about interfaces not supporting multicast when they really do.
            return try {
                if (!networkInterface.isUp) {
                    return false
                }

                /*
                if (!networkInterface.supportsMulticast()) {
                    return false;
                }
                */
                if (networkInterface.isLoopback) {
                    return false
                }

                true
            } catch (exception: Exception) {
                false
            }
        }
    }

    private fun handleResolvedServiceInfo(info: ServiceInfo) {
        synchronized(pendingResolution) {
            pendingResolution.remove(info.name)
        }

        try {
            handleServiceInfo(info)
        } catch (e: UnsupportedEncodingException) {
            // Invalid DNS response
            LimeLog.info("mDNS: Invalid response for machine: " + info.name)
            return
        }
    }

    @Throws(UnsupportedEncodingException::class)
    private fun handleServiceInfo(info: ServiceInfo) {
        reportNewComputer(info.name, info.port, info.inet4Addresses, info.inet6Addresses)
    }

    override fun startDiscovery(discoveryIntervalMs: Int) {
        // Kill any existing discovery before starting a new one
        stopDiscovery()

        // Acquire the multicast lock to start receiving mDNS traffic
        multicastLock.acquire()

        // Add our listener to the set
        synchronized(listeners) {
            listeners.add(this@JmDNSDiscoveryAgent)
        }

        discoveryThread = object : Thread() {
            override fun run() {
                // This may result in listener callbacks so we must register
                // our listener first.
                val resolver = referenceResolver()

                try {
                    while (!interrupted()) {
                        // Start an mDNS request
                        resolver.requestServiceInfo(SERVICE_TYPE, null, discoveryIntervalMs.toLong())

                        // Run service resolution again for pending machines
                        val pendingNames: ArrayList<String>
                        synchronized(pendingResolution) {
                            pendingNames = ArrayList(pendingResolution)
                        }
                        for (name in pendingNames) {
                            LimeLog.info("mDNS: Retrying service resolution for machine: $name")
                            val infos = resolver.getServiceInfos(SERVICE_TYPE, name, 500L)
                            if (infos != null && infos.isNotEmpty()) {
                                LimeLog.info("mDNS: Resolved (retry) with " + infos.size + " service entries")
                                for (svcinfo in infos) {
                                    handleResolvedServiceInfo(svcinfo)
                                }
                            }
                        }

                        // Wait for the next polling interval
                        try {
                            sleep(discoveryIntervalMs.toLong())
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                } finally {
                    // Dereference the resolver
                    dereferenceResolver()
                }
            }
        }
        discoveryThread?.name = "mDNS Discovery Thread"
        discoveryThread?.start()
    }

    override fun stopDiscovery() {
        // Release the multicast lock to stop receiving mDNS traffic
        multicastLock.release()

        // Remove our listener from the set
        synchronized(listeners) {
            listeners.remove(this@JmDNSDiscoveryAgent)
        }

        // If there's already a running thread, interrupt it
        if (discoveryThread != null) {
            discoveryThread?.interrupt()
            discoveryThread = null
        }
    }

    override fun serviceAdded(event: ServiceEvent) {
        LimeLog.info("mDNS: Machine appeared: " + event.info.name)

        val info = event.dns.getServiceInfo(SERVICE_TYPE, event.info.name, 500L)
        if (info == null) {
            // This machine is pending resolution
            synchronized(pendingResolution) {
                pendingResolution.add(event.info.name)
            }
            return
        }

        LimeLog.info("mDNS: Resolved (blocking)")
        handleResolvedServiceInfo(info)
    }

    override fun serviceRemoved(event: ServiceEvent) {
        LimeLog.info("mDNS: Machine disappeared: " + event.info.name)
    }

    override fun serviceResolved(event: ServiceEvent) {
        // We handle this synchronously
    }

    private companion object {
        private const val SERVICE_TYPE = "_nvstream._tcp.local."

        // The resolver factory's instance member has a static lifetime which
        // means our ref count and listener must be static also.
        private var resolverRefCount = 0
        private val listeners = HashSet<ServiceListener>()
        private val nvstreamListener: ServiceListener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                val localListeners: HashSet<ServiceListener>

                // Copy the listener set into a new set so we can invoke
                // the callbacks without holding the listeners monitor the
                // whole time.
                synchronized(listeners) {
                    localListeners = HashSet(listeners)
                }

                for (listener in localListeners) {
                    listener.serviceAdded(event)
                }
            }

            override fun serviceRemoved(event: ServiceEvent) {
                val localListeners: HashSet<ServiceListener>

                // Copy the listener set into a new set so we can invoke
                // the callbacks without holding the listeners monitor the
                // whole time.
                synchronized(listeners) {
                    localListeners = HashSet(listeners)
                }

                for (listener in localListeners) {
                    listener.serviceRemoved(event)
                }
            }

            override fun serviceResolved(event: ServiceEvent) {
                val localListeners: HashSet<ServiceListener>

                // Copy the listener set into a new set so we can invoke
                // the callbacks without holding the listeners monitor the
                // whole time.
                synchronized(listeners) {
                    localListeners = HashSet(listeners)
                }

                for (listener in localListeners) {
                    listener.serviceResolved(event)
                }
            }
        }

        init {
            // Override jmDNS's default topology discovery class with ours
            NetworkTopologyDiscovery.Factory.setClassDelegate(
                object : NetworkTopologyDiscovery.Factory.ClassDelegate {
                    override fun newNetworkTopologyDiscovery(): NetworkTopologyDiscovery =
                        MyNetworkTopologyDiscovery()
                },
            )
        }

        private fun referenceResolver(): JmmDNS {
            synchronized(JmDNSDiscoveryAgent::class.java) {
                val instance = JmmDNS.Factory.getInstance()
                if (++resolverRefCount == 1) {
                    // This will cause the listener to be invoked for known hosts immediately.
                    // JmDNS only supports one listener per service, so we have to do this here
                    // with a static listener.
                    instance.addServiceListener(SERVICE_TYPE, nvstreamListener)
                }
                return instance
            }
        }

        private fun dereferenceResolver() {
            synchronized(JmDNSDiscoveryAgent::class.java) {
                if (--resolverRefCount == 0) {
                    try {
                        JmmDNS.Factory.close()
                    } catch (e: IOException) {
                    }
                }
            }
        }
    }
}

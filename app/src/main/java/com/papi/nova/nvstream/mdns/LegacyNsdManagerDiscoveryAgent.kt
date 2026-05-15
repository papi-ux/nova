package com.papi.nova.nvstream.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.papi.nova.LimeLog
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.LinkedList
import java.util.Queue

@Suppress("DEPRECATION")
open class LegacyNsdManagerDiscoveryAgent(
    context: Context,
    listener: MdnsDiscoveryListener,
) : MdnsDiscoveryAgent(listener) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager?
    private val lock = Any()
    private val pendingResolutions: Queue<NsdServiceInfo> = LinkedList()
    private val queuedServiceNames = HashSet<String>()

    private var pendingListener: NsdManager.DiscoveryListener? = null
    private var activeListener: NsdManager.DiscoveryListener? = null
    private var resolving = false

    override fun startDiscovery(discoveryIntervalMs: Int) {
        synchronized(lock) {
            if (pendingListener != null || activeListener != null || nsdManager == null) {
                return
            }

            pendingListener = createDiscoveryListener()
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, pendingListener)
        }
    }

    override fun stopDiscovery() {
        synchronized(lock) {
            pendingListener = null

            if (activeListener != null && nsdManager != null) {
                try {
                    nsdManager.stopServiceDiscovery(activeListener)
                } catch (ignored: IllegalArgumentException) {
                    // Android can throw if the listener has already been stopped asynchronously.
                }
                activeListener = null
            }

            pendingResolutions.clear()
            queuedServiceNames.clear()
            resolving = false
        }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                LimeLog.severe("NSD legacy: Service discovery start failed: $errorCode")
                synchronized(lock) {
                    if (pendingListener === this) {
                        pendingListener = null
                    }
                }
                listener.notifyDiscoveryFailure(RuntimeException("onStartDiscoveryFailed(): $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                LimeLog.severe("NSD legacy: Service discovery stop failed: $errorCode")
                synchronized(lock) {
                    if (activeListener === this) {
                        activeListener = null
                    }
                }
            }

            override fun onDiscoveryStarted(serviceType: String) {
                LimeLog.info("NSD legacy: Service discovery started")
                synchronized(lock) {
                    if (pendingListener !== this) {
                        nsdManager?.stopServiceDiscovery(this)
                        return
                    }

                    pendingListener = null
                    activeListener = this
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                LimeLog.info("NSD legacy: Service discovery stopped")
                synchronized(lock) {
                    if (activeListener === this) {
                        activeListener = null
                    }
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    if (activeListener !== this) {
                        return
                    }
                }

                LimeLog.info("NSD legacy: Machine appeared: " + serviceInfo.serviceName)
                queueResolution(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                LimeLog.info("NSD legacy: Machine lost: " + serviceInfo.serviceName)
            }
        }
    }

    private fun queueResolution(serviceInfo: NsdServiceInfo) {
        synchronized(lock) {
            val serviceName = serviceInfo.serviceName
            if (!queuedServiceNames.add(serviceName)) {
                return
            }

            pendingResolutions.add(serviceInfo)
            if (resolving) {
                return
            }

            resolving = true
        }

        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(lock) {
            val queued = pendingResolutions.poll()
            if (queued == null || activeListener == null) {
                resolving = false
                null
            } else {
                queued
            }
        } ?: return

        val manager = nsdManager ?: return
        manager.resolveService(
            next,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    LimeLog.warning(
                        "NSD legacy: Service resolve failed for " +
                            serviceInfo.serviceName +
                            ": " +
                            errorCode,
                    )
                    finishResolution(serviceInfo)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    LimeLog.info("NSD legacy: Machine resolved: " + serviceInfo.serviceName)
                    val host = serviceInfo.host
                    if (host is Inet4Address) {
                        reportNewComputer(
                            serviceInfo.serviceName,
                            serviceInfo.port,
                            arrayOf(host),
                            emptyArray(),
                        )
                    } else if (host is Inet6Address) {
                        reportNewComputer(
                            serviceInfo.serviceName,
                            serviceInfo.port,
                            emptyArray(),
                            arrayOf(host),
                        )
                    }

                    finishResolution(serviceInfo)
                }
            },
        )
    }

    private fun finishResolution(serviceInfo: NsdServiceInfo) {
        synchronized(lock) {
            queuedServiceNames.remove(serviceInfo.serviceName)
        }
        resolveNext()
    }

    private companion object {
        private const val SERVICE_TYPE = "_nvstream._tcp"
    }
}

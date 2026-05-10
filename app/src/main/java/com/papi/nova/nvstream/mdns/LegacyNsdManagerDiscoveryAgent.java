package com.papi.nova.nvstream.mdns;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import com.papi.nova.LimeLog;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;

public class LegacyNsdManagerDiscoveryAgent extends MdnsDiscoveryAgent {
    private static final String SERVICE_TYPE = "_nvstream._tcp";

    private final NsdManager nsdManager;
    private final Object lock = new Object();
    private final Queue<NsdServiceInfo> pendingResolutions = new LinkedList<>();
    private final HashSet<String> queuedServiceNames = new HashSet<>();

    private NsdManager.DiscoveryListener pendingListener;
    private NsdManager.DiscoveryListener activeListener;
    private boolean resolving;

    public LegacyNsdManagerDiscoveryAgent(Context context, MdnsDiscoveryListener listener) {
        super(listener);
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    @Override
    public void startDiscovery(int discoveryIntervalMs) {
        synchronized (lock) {
            if (pendingListener != null || activeListener != null || nsdManager == null) {
                return;
            }

            pendingListener = createDiscoveryListener();
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, pendingListener);
        }
    }

    @Override
    public void stopDiscovery() {
        synchronized (lock) {
            pendingListener = null;

            if (activeListener != null && nsdManager != null) {
                try {
                    nsdManager.stopServiceDiscovery(activeListener);
                } catch (IllegalArgumentException ignored) {
                    // Android can throw if the listener has already been stopped asynchronously.
                }
                activeListener = null;
            }

            pendingResolutions.clear();
            queuedServiceNames.clear();
            resolving = false;
        }
    }

    private NsdManager.DiscoveryListener createDiscoveryListener() {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                LimeLog.severe("NSD legacy: Service discovery start failed: " + errorCode);
                synchronized (lock) {
                    if (pendingListener == this) {
                        pendingListener = null;
                    }
                }
                listener.notifyDiscoveryFailure(new RuntimeException("onStartDiscoveryFailed(): " + errorCode));
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                LimeLog.severe("NSD legacy: Service discovery stop failed: " + errorCode);
                synchronized (lock) {
                    if (activeListener == this) {
                        activeListener = null;
                    }
                }
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                LimeLog.info("NSD legacy: Service discovery started");
                synchronized (lock) {
                    if (pendingListener != this) {
                        nsdManager.stopServiceDiscovery(this);
                        return;
                    }

                    pendingListener = null;
                    activeListener = this;
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                LimeLog.info("NSD legacy: Service discovery stopped");
                synchronized (lock) {
                    if (activeListener == this) {
                        activeListener = null;
                    }
                }
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                synchronized (lock) {
                    if (activeListener != this) {
                        return;
                    }
                }

                LimeLog.info("NSD legacy: Machine appeared: " + serviceInfo.getServiceName());
                queueResolution(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                LimeLog.info("NSD legacy: Machine lost: " + serviceInfo.getServiceName());
            }
        };
    }

    private void queueResolution(NsdServiceInfo serviceInfo) {
        synchronized (lock) {
            String serviceName = serviceInfo.getServiceName();
            if (!queuedServiceNames.add(serviceName)) {
                return;
            }

            pendingResolutions.add(serviceInfo);
            if (resolving) {
                return;
            }

            resolving = true;
        }

        resolveNext();
    }

    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (lock) {
            next = pendingResolutions.poll();
            if (next == null || activeListener == null) {
                resolving = false;
                return;
            }
        }

        nsdManager.resolveService(next, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                LimeLog.warning("NSD legacy: Service resolve failed for " +
                        serviceInfo.getServiceName() + ": " + errorCode);
                finishResolution(serviceInfo);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                LimeLog.info("NSD legacy: Machine resolved: " + serviceInfo.getServiceName());
                InetAddress host = serviceInfo.getHost();
                if (host instanceof Inet4Address) {
                    reportNewComputer(serviceInfo.getServiceName(), serviceInfo.getPort(),
                            new Inet4Address[]{(Inet4Address) host}, new Inet6Address[0]);
                } else if (host instanceof Inet6Address) {
                    reportNewComputer(serviceInfo.getServiceName(), serviceInfo.getPort(),
                            new Inet4Address[0], new Inet6Address[]{(Inet6Address) host});
                }

                finishResolution(serviceInfo);
            }
        });
    }

    private void finishResolution(NsdServiceInfo serviceInfo) {
        synchronized (lock) {
            queuedServiceNames.remove(serviceInfo.getServiceName());
        }
        resolveNext();
    }
}

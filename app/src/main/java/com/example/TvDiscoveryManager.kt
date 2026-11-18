package com.example

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

data class TvDevice(val name: String, val ip: String)

class TvDiscoveryManager(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<TvDevice>> = _discoveredDevices.asStateFlow()

    private val serviceType = "_androidtvremote2._tcp."
    private var isDiscovering = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {}

        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType.contains("_androidtvremote2._tcp")) {
                try {
                    nsdManager.resolveService(service, createResolveListener())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            val name = service.serviceName
            _discoveredDevices.value = _discoveredDevices.value.filterNot { it.name == name }
        }

        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            isDiscovering = false
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            isDiscovering = false
        }
    }

    private fun createResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                @Suppress("DEPRECATION")
                val host = serviceInfo.host ?: return
                val ip = host.hostAddress ?: return
                // Prefer IPv4 addresses when available so local socket connection succeeds cleanly
                if (host !is Inet4Address && _discoveredDevices.value.any { it.name == name }) {
                    return
                }

                val device = TvDevice(name, ip)
                val currentList = _discoveredDevices.value.toMutableList()
                val existingIdx = currentList.indexOfFirst { it.name == name }
                if (existingIdx >= 0) {
                    if (host is Inet4Address) {
                        currentList[existingIdx] = device
                    }
                } else if (currentList.none { it.ip == ip }) {
                    currentList.add(device)
                }
                _discoveredDevices.value = currentList
            }
        }
    }

    fun startDiscovery() {
        if (isDiscovering) return
        try {
            _discoveredDevices.value = emptyList()
            isDiscovering = true
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            isDiscovering = false
            e.printStackTrace()
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isDiscovering = false
        }
    }
}

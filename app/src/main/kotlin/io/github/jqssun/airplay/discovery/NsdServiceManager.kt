package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

class NsdServiceManager(private val ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var raopRegistration: NsdManager.RegistrationListener? = null
    private var airplayRegistration: NsdManager.RegistrationListener? = null

    fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun registerRaop(serviceName: String, port: Int, txtRecords: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_raop._tcp"
            this.port = port
            txtRecords.forEach { (k, v) -> setAttribute(k, v) }
        }

        raopRegistration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "RAOP registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "RAOP registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "RAOP unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "RAOP unregister failed: $code")
            }
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, raopRegistration)
    }

    fun registerAirplay(serviceName: String, port: Int, txtRecords: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_airplay._tcp"
            this.port = port
            txtRecords.forEach { (k, v) -> setAttribute(k, v) }
        }

        airplayRegistration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "AirPlay registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "AirPlay registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "AirPlay unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "AirPlay unregister failed: $code")
            }
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, airplayRegistration)
    }

    fun unregisterAll() {
        raopRegistration?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            raopRegistration = null
        }
        airplayRegistration?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            airplayRegistration = null
        }
    }

    fun release() {
        unregisterAll()
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdServiceManager"
    }
}

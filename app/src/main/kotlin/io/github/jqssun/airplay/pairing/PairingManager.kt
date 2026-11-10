package io.github.jqssun.airplay.pairing

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages paired AirPlay devices
 * Tracks device info and allows removal of individual pairings
 * Singleton to ensure single source of truth across Service and UI
 */
class PairingManager private constructor(context: Context) {
    
    companion object {
        @Volatile
        private var INSTANCE: PairingManager? = null
        
        fun getInstance(context: Context): PairingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PairingManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "airplay_pairings",
        Context.MODE_PRIVATE
    )
    
    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()
    
    data class PairedDevice(
        val id: String,          // Unique device ID (MAC address or client ID)
        val name: String,        // Device name (e.g., "iPhone di Simone")
        val pairedAt: Long,      // Timestamp when paired
        val lastSeen: Long       // Last connection timestamp
    ) {
        fun pairedAtFormatted(): String {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            return sdf.format(Date(pairedAt))
        }
        
        fun lastSeenFormatted(): String {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            return sdf.format(Date(lastSeen))
        }
    }
    
    init {
        loadDevices()
    }
    
    /**
     * Register a new paired device
     */
    fun registerDevice(deviceId: String, deviceName: String) {
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        
        // Store device info
        editor.putString("device_${deviceId}_name", deviceName)
        editor.putLong("device_${deviceId}_paired_at", 
            prefs.getLong("device_${deviceId}_paired_at", now)) // Keep original pairing time
        editor.putLong("device_${deviceId}_last_seen", now)
        
        // Add to device list if not already there
        val deviceIds = getDeviceIds().toMutableSet()
        deviceIds.add(deviceId)
        editor.putStringSet("device_ids", deviceIds)
        
        editor.apply()
        loadDevices()
    }
    
    /**
     * Update last seen timestamp for a device
     */
    fun updateLastSeen(deviceId: String) {
        prefs.edit()
            .putLong("device_${deviceId}_last_seen", System.currentTimeMillis())
            .apply()
        loadDevices()
    }
    
    /**
     * Remove a paired device
     */
    fun removeDevice(deviceId: String) {
        val editor = prefs.edit()
        
        // Remove device data
        editor.remove("device_${deviceId}_name")
        editor.remove("device_${deviceId}_paired_at")
        editor.remove("device_${deviceId}_last_seen")
        
        // Remove from device list
        val deviceIds = getDeviceIds().toMutableSet()
        deviceIds.remove(deviceId)
        editor.putStringSet("device_ids", deviceIds)
        
        editor.apply()
        loadDevices()
    }
    
    /**
     * Remove all paired devices
     */
    fun removeAllDevices() {
        val editor = prefs.edit()
        val deviceIds = getDeviceIds()
        
        // Remove all device data
        deviceIds.forEach { deviceId ->
            editor.remove("device_${deviceId}_name")
            editor.remove("device_${deviceId}_paired_at")
            editor.remove("device_${deviceId}_last_seen")
        }
        
        // Clear device list
        editor.putStringSet("device_ids", emptySet())
        editor.apply()
        
        loadDevices()
    }
    
    /**
     * Get list of device IDs
     */
    private fun getDeviceIds(): Set<String> {
        return prefs.getStringSet("device_ids", emptySet()) ?: emptySet()
    }
    
    /**
     * Load devices from preferences
     */
    private fun loadDevices() {
        val devices = getDeviceIds().mapNotNull { deviceId ->
            val name = prefs.getString("device_${deviceId}_name", null) ?: return@mapNotNull null
            val pairedAt = prefs.getLong("device_${deviceId}_paired_at", 0)
            val lastSeen = prefs.getLong("device_${deviceId}_last_seen", 0)
            
            PairedDevice(
                id = deviceId,
                name = name,
                pairedAt = pairedAt,
                lastSeen = lastSeen
            )
        }.sortedByDescending { it.lastSeen } // Most recent first
        
        _pairedDevices.value = devices
    }
    
    /**
     * Get device count
     */
    fun getDeviceCount(): Int = _pairedDevices.value.size
}

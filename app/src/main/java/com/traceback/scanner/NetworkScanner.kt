package com.traceback.scanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Scans for nearby WiFi and Bluetooth networks.
 */
class NetworkScanner(private val context: Context) {
    
    companion object {
        private const val TAG = "NetworkScanner"
    }
    
    data class NetworkInfo(
        val identifier: String,  // SSID or BT address
        val name: String,        // Display name
        val isBluetooth: Boolean,
        val signalStrength: Int  // RSSI
    )
    
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    
    /**
     * Get currently connected WiFi network.
     */
    fun getConnectedWifi(): NetworkInfo? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        
        val connectionInfo = wifiManager.connectionInfo ?: return null
        val ssid = connectionInfo.ssid?.replace("\"", "") ?: return null
        
        if (ssid == "<unknown ssid>" || ssid.isBlank()) return null
        
        return NetworkInfo(
            identifier = ssid,
            name = ssid,
            isBluetooth = false,
            signalStrength = connectionInfo.rssi
        )
    }
    
    /**
     * Get all visible WiFi networks.
     */
    fun getVisibleWifiNetworks(): List<NetworkInfo> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        
        return wifiManager.scanResults
            .filter { it.SSID.isNotBlank() }
            .distinctBy { it.SSID }
            .map { result ->
                NetworkInfo(
                    identifier = result.SSID,
                    name = result.SSID,
                    isBluetooth = false,
                    signalStrength = result.level
                )
            }
    }
    
    /**
     * Get currently connected Bluetooth devices.
     */
    fun getConnectedBluetoothDevices(): List<NetworkInfo> {
        if (bluetoothAdapter == null) return emptyList()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                return emptyList()
            }
        }
        
        return try {
            bluetoothAdapter.bondedDevices
                ?.filter { device ->
                    // Check if device is connected (approximate check via profile)
                    isBluetoothDeviceConnected(device)
                }
                ?.map { device ->
                    NetworkInfo(
                        identifier = device.address,
                        name = device.name ?: device.address,
                        isBluetooth = true,
                        signalStrength = 0
                    )
                } ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied", e)
            emptyList()
        }
    }
    
    /**
     * Get all bonded (paired) Bluetooth devices.
     */
    fun getBondedBluetoothDevices(): List<NetworkInfo> {
        if (bluetoothAdapter == null) return emptyList()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                return emptyList()
            }
        }
        
        return try {
            bluetoothAdapter.bondedDevices?.map { device ->
                NetworkInfo(
                    identifier = device.address,
                    name = device.name ?: device.address,
                    isBluetooth = true,
                    signalStrength = 0
                )
            } ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied", e)
            emptyList()
        }
    }
    
    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Flow that emits when Bluetooth device connects/disconnects.
     */
    fun bluetoothConnectionChanges(): Flow<NetworkInfo?> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let {
                            try {
                                trySend(NetworkInfo(
                                    identifier = it.address,
                                    name = it.name ?: it.address,
                                    isBluetooth = true,
                                    signalStrength = 0
                                ))
                            } catch (e: SecurityException) {
                                Log.e(TAG, "BT permission denied", e)
                            }
                        }
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        trySend(null)
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)
        
        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }
    
    /**
     * Check if device name suggests it's a vehicle (car, ship, etc.)
     */
    fun isLikelyVehicle(name: String): Boolean {
        val vehicleKeywords = listOf(
            "car", "auto", "bmw", "mercedes", "audi", "vw", "volkswagen", "tesla",
            "ford", "toyota", "honda", "porsche", "carplay", "android auto",
            "handsfree", "freisprechanlage", "boat", "ship", "schiff", "yacht",
            "marine", "navico", "garmin", "raymarine"
        )
        val lowerName = name.lowercase()
        return vehicleKeywords.any { lowerName.contains(it) }
    }
}

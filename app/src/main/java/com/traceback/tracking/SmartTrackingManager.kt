package com.traceback.tracking

import android.content.Context
import android.location.Location
import android.util.Log
import com.traceback.TraceBackApp
import com.traceback.activity.ActivityRecognitionManager
import com.traceback.data.NetworkFingerprint
import com.traceback.data.NetworkType
import com.traceback.data.TraceBackDatabase
import com.traceback.scanner.NetworkScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Smart tracking manager that decides when to track based on:
 * - Master switch state
 * - Known network fingerprints (static vs dynamic)
 * - Activity recognition
 * - Self-learning from GPS data
 */
class SmartTrackingManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SmartTrackingManager"
        
        // Distance threshold for considering a network as "moving"
        private const val MOVEMENT_THRESHOLD_METERS = 100.0
        
        // Time threshold for learning (30 minutes)
        private const val LEARNING_TIME_MS = 30 * 60 * 1000L
        
        // Confidence threshold to classify network
        private const val CONFIDENCE_THRESHOLD = 0.7f
        
        // Minimum samples for confident classification
        private const val MIN_SAMPLES_FOR_CONFIDENCE = 5
    }
    
    enum class TrackingMode {
        OFF,            // Master switch off - no tracking at all
        ACTIVE,         // GPS tracking active
        STATIONARY,     // Connected to known static network - minimal tracking
        LEARNING        // Learning new network
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = TraceBackDatabase.getDatabase(context)
    private val networkDao = database.networkFingerprintDao()
    
    val networkScanner = NetworkScanner(context)
    val activityManager = ActivityRecognitionManager(context)
    
    private val _trackingMode = MutableStateFlow(TrackingMode.OFF)
    val trackingMode: StateFlow<TrackingMode> = _trackingMode
    
    private val _currentNetworks = MutableStateFlow<List<NetworkFingerprint>>(emptyList())
    val currentNetworks: StateFlow<List<NetworkFingerprint>> = _currentNetworks
    
    // For learning
    private var learningStartTime: Long = 0
    private var learningStartLocation: Location? = null
    private var learningNetwork: NetworkScanner.NetworkInfo? = null
    
    /**
     * Evaluate current situation and decide tracking mode.
     */
    suspend fun evaluateTrackingMode(): TrackingMode {
        val prefs = TraceBackApp.instance.securePrefs
        
        // Master switch check
        if (!prefs.trackingEnabled) {
            _trackingMode.value = TrackingMode.OFF
            return TrackingMode.OFF
        }
        
        // Get current networks
        val connectedWifi = networkScanner.getConnectedWifi()
        val connectedBluetooth = networkScanner.getConnectedBluetoothDevices()
        
        val allConnected = mutableListOf<NetworkScanner.NetworkInfo>()
        connectedWifi?.let { allConnected.add(it) }
        allConnected.addAll(connectedBluetooth)
        
        if (allConnected.isEmpty()) {
            // No known networks - use activity recognition
            val mode = if (activityManager.isMoving()) {
                TrackingMode.ACTIVE
            } else {
                // Still but no network - keep tracking for safety
                TrackingMode.ACTIVE
            }
            _trackingMode.value = mode
            return mode
        }
        
        // Look up networks in database
        val knownNetworks = networkDao.getByIds(allConnected.map { it.identifier })
        _currentNetworks.value = knownNetworks
        
        // Check for dynamic networks first (they take priority)
        val dynamicNetwork = knownNetworks.find { it.type == NetworkType.DYNAMIC }
        if (dynamicNetwork != null) {
            Log.d(TAG, "Connected to dynamic network: ${dynamicNetwork.name}")
            _trackingMode.value = TrackingMode.ACTIVE
            return TrackingMode.ACTIVE
        }
        
        // Check for static networks
        val staticNetwork = knownNetworks.find { it.type == NetworkType.STATIC }
        if (staticNetwork != null) {
            Log.d(TAG, "Connected to static network: ${staticNetwork.name}")
            _trackingMode.value = TrackingMode.STATIONARY
            return TrackingMode.STATIONARY
        }
        
        // Unknown networks - check activity and possibly learn
        val unknownNetworks = allConnected.filter { connected ->
            knownNetworks.none { it.identifier == connected.identifier }
        }
        
        if (unknownNetworks.isNotEmpty()) {
            // New network detected - start learning or use activity
            val firstUnknown = unknownNetworks.first()
            
            // Auto-suggest type based on name
            if (networkScanner.isLikelyVehicle(firstUnknown.name)) {
                // Auto-classify as dynamic
                scope.launch {
                    classifyNetwork(firstUnknown, NetworkType.DYNAMIC)
                }
                _trackingMode.value = TrackingMode.ACTIVE
                return TrackingMode.ACTIVE
            }
            
            // Start learning mode
            startLearning(firstUnknown)
            _trackingMode.value = TrackingMode.LEARNING
            return TrackingMode.LEARNING
        }
        
        // Fallback to activity-based
        val mode = if (activityManager.isMoving()) TrackingMode.ACTIVE else TrackingMode.ACTIVE
        _trackingMode.value = mode
        return mode
    }
    
    /**
     * Update learning with new GPS location.
     */
    suspend fun updateLearning(location: Location) {
        val network = learningNetwork ?: return
        val startLocation = learningStartLocation ?: return
        
        val distance = location.distanceTo(startLocation)
        val elapsed = System.currentTimeMillis() - learningStartTime
        
        // Get or create fingerprint
        var fingerprint = networkDao.getById(network.identifier)
        if (fingerprint == null) {
            fingerprint = NetworkFingerprint(
                identifier = network.identifier,
                name = network.name,
                isBluetooth = network.isBluetooth
            )
        }
        
        fingerprint.sampleCount++
        fingerprint.lastSeen = System.currentTimeMillis()
        
        // Analyze movement
        if (distance > MOVEMENT_THRESHOLD_METERS) {
            // Significant movement while connected = DYNAMIC
            fingerprint.confidenceScore = minOf(1f, fingerprint.confidenceScore + 0.2f)
            
            if (fingerprint.confidenceScore >= CONFIDENCE_THRESHOLD && 
                fingerprint.sampleCount >= MIN_SAMPLES_FOR_CONFIDENCE) {
                fingerprint.type = NetworkType.DYNAMIC
                Log.i(TAG, "Learned: ${network.name} is DYNAMIC (moved ${distance}m)")
            }
        } else if (elapsed > LEARNING_TIME_MS) {
            // Long time without movement = STATIC
            fingerprint.confidenceScore = minOf(1f, fingerprint.confidenceScore + 0.3f)
            fingerprint.learnedLatitude = startLocation.latitude
            fingerprint.learnedLongitude = startLocation.longitude
            
            if (fingerprint.confidenceScore >= CONFIDENCE_THRESHOLD &&
                fingerprint.sampleCount >= MIN_SAMPLES_FOR_CONFIDENCE) {
                fingerprint.type = NetworkType.STATIC
                Log.i(TAG, "Learned: ${network.name} is STATIC at ${startLocation.latitude}, ${startLocation.longitude}")
            }
        }
        
        networkDao.insert(fingerprint)
    }
    
    /**
     * Manually classify a network.
     */
    suspend fun classifyNetwork(network: NetworkScanner.NetworkInfo, type: NetworkType, location: Location? = null) {
        val fingerprint = NetworkFingerprint(
            identifier = network.identifier,
            name = network.name,
            isBluetooth = network.isBluetooth,
            type = type,
            confidenceScore = 1f,  // Manual classification = full confidence
            sampleCount = 1,
            learnedLatitude = location?.latitude,
            learnedLongitude = location?.longitude
        )
        networkDao.insert(fingerprint)
        Log.i(TAG, "Manually classified ${network.name} as $type")
    }
    
    /**
     * Get pending (unknown) network for classification popup.
     */
    suspend fun getPendingNetwork(): NetworkScanner.NetworkInfo? {
        val connectedWifi = networkScanner.getConnectedWifi()
        val connectedBluetooth = networkScanner.getConnectedBluetoothDevices()
        
        val allConnected = mutableListOf<NetworkScanner.NetworkInfo>()
        connectedWifi?.let { allConnected.add(it) }
        allConnected.addAll(connectedBluetooth)
        
        for (network in allConnected) {
            val known = networkDao.getById(network.identifier)
            if (known == null || known.type == NetworkType.UNKNOWN) {
                return network
            }
        }
        return null
    }
    
    /**
     * Check for GPS drift on static network (indicates possible theft/movement).
     */
    suspend fun checkDrift(currentLocation: Location): Float? {
        val staticNetworks = _currentNetworks.value.filter { it.type == NetworkType.STATIC }
        
        for (network in staticNetworks) {
            val lat = network.learnedLatitude ?: continue
            val lon = network.learnedLongitude ?: continue
            
            val results = FloatArray(1)
            Location.distanceBetween(lat, lon, currentLocation.latitude, currentLocation.longitude, results)
            val drift = results[0]
            
            if (drift > 500) {  // 500m threshold
                Log.w(TAG, "Drift detected: ${drift}m from learned location of ${network.name}")
                return drift
            }
        }
        return null
    }
    
    /**
     * Get all learned networks.
     */
    suspend fun getAllNetworks(): List<NetworkFingerprint> {
        return networkDao.getAll()
    }
    
    /**
     * Delete a learned network.
     */
    suspend fun deleteNetwork(identifier: String) {
        networkDao.deleteById(identifier)
    }
    
    private fun startLearning(network: NetworkScanner.NetworkInfo) {
        learningNetwork = network
        learningStartTime = System.currentTimeMillis()
        learningStartLocation = null  // Will be set on first GPS update
        Log.d(TAG, "Started learning network: ${network.name}")
    }
    
    fun setLearningStartLocation(location: Location) {
        if (learningStartLocation == null) {
            learningStartLocation = location
        }
    }
}

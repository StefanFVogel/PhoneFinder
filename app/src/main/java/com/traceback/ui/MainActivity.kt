package com.traceback.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.traceback.R
import com.traceback.TraceBackApp
import com.traceback.databinding.ActivityMainBinding
import com.traceback.drive.DriveManager
import com.traceback.kml.KmlGenerator
import com.traceback.service.TrackingService
import com.traceback.telegram.TelegramNotifier
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var driveManager: DriveManager
    private lateinit var telegramNotifier: TelegramNotifier
    private lateinit var kmlGenerator: KmlGenerator
    
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true -> {
                requestBackgroundLocation()
            }
            else -> {
                Toast.makeText(this, "Standortberechtigung erforderlich", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private val backgroundLocationRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            updateStatusIndicators()
        } else {
            Toast.makeText(this, "Hintergrund-Standort erforderlich für Tracking", Toast.LENGTH_LONG).show()
        }
    }
    
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        task.addOnSuccessListener { account ->
            driveManager.initialize(account)
            updateStatusIndicators()
        }.addOnFailureListener {
            Toast.makeText(this, "Google Sign-In fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        driveManager = DriveManager(this)
        telegramNotifier = TelegramNotifier(TraceBackApp.instance.securePrefs)
        kmlGenerator = KmlGenerator(this)
        
        setupUI()
        checkPermissions()
        updateStatusIndicators()
    }
    
    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
    }
    
    private fun setupUI() {
        val prefs = TraceBackApp.instance.securePrefs
        
        // Tracking toggle
        binding.switchTracking.isChecked = prefs.trackingEnabled
        binding.switchTracking.setOnCheckedChangeListener { _, isChecked ->
            prefs.trackingEnabled = isChecked
            if (isChecked) {
                TrackingService.start(this)
            } else {
                TrackingService.stop(this)
            }
            updateStatusIndicators()
        }
        
        // Distance slider (50m - 2000m)
        binding.seekbarDistance.progress = (prefs.trackingDistanceMeters - 50) / 50
        binding.textDistance.text = "${prefs.trackingDistanceMeters}m"
        binding.seekbarDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val meters = 50 + (progress * 50)
                binding.textDistance.text = "${meters}m"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val meters = 50 + (seekBar.progress * 50)
                prefs.trackingDistanceMeters = meters
            }
        })
        
        // Telegram setup button
        binding.buttonTelegramSetup.setOnClickListener {
            showTelegramSetupDialog()
        }
        
        // Google Drive sign-in button
        binding.buttonDriveSignin.setOnClickListener {
            signInToGoogle()
        }
        
        // Sync now button
        binding.buttonSyncNow.setOnClickListener {
            syncToDriveNow()
        }
        
        // Battery optimization button
        binding.buttonBatteryOptimization.setOnClickListener {
            requestBatteryExemption()
        }
        
        // Test Last Breath button
        binding.buttonTestLastBreath.setOnClickListener {
            testLastBreath()
        }
    }
    
    private fun checkPermissions() {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        
        if (fineLocation != PackageManager.PERMISSION_GRANTED) {
            locationPermissionRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            requestBackgroundLocation()
        }
        
        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
    
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                
                AlertDialog.Builder(this)
                    .setTitle("Hintergrund-Standort")
                    .setMessage("TraceBack benötigt 'Immer erlauben' für die Standortberechtigung, um auch bei geschlossener App zu tracken.")
                    .setPositiveButton("Einstellungen öffnen") { _, _ ->
                        backgroundLocationRequest.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
    }
    
    private fun updateStatusIndicators() {
        val prefs = TraceBackApp.instance.securePrefs
        
        // GPS Status
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        
        binding.indicatorGps.setImageResource(
            if (hasLocation && hasBackground) R.drawable.indicator_green
            else if (hasLocation) R.drawable.indicator_yellow
            else R.drawable.indicator_red
        )
        
        // Drive Status
        val driveReady = driveManager.isReady()
        binding.indicatorDrive.setImageResource(
            if (driveReady) R.drawable.indicator_green else R.drawable.indicator_red
        )
        
        // Telegram Status
        lifecycleScope.launch {
            val telegramReady = prefs.isConfiguredForEmergency() && telegramNotifier.testConnection()
            binding.indicatorTelegram.setImageResource(
                if (telegramReady) R.drawable.indicator_green
                else if (prefs.isConfiguredForEmergency()) R.drawable.indicator_yellow
                else R.drawable.indicator_red
            )
        }
        
        // Last sync time
        val lastSync = prefs.lastSyncTimestamp
        if (lastSync > 0) {
            val ago = (System.currentTimeMillis() - lastSync) / 60000
            binding.textLastSync.text = "Letzter Sync: vor ${ago} Min."
        } else {
            binding.textLastSync.text = "Noch nicht synchronisiert"
        }
    }
    
    private fun signInToGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        
        val client = GoogleSignIn.getClient(this, gso)
        googleSignInLauncher.launch(client.signInIntent)
    }
    
    private fun showTelegramSetupDialog() {
        val prefs = TraceBackApp.instance.securePrefs
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_telegram_setup, null)
        val editBotToken = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_bot_token)
        val editChatId = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_chat_id)
        
        // Pre-fill with existing values
        editBotToken.setText(prefs.telegramBotToken ?: "")
        editChatId.setText(prefs.telegramChatId ?: "")
        
        AlertDialog.Builder(this)
            .setTitle("Telegram Bot Konfiguration")
            .setView(dialogView)
            .setPositiveButton("Speichern") { _, _ ->
                val botToken = editBotToken.text?.toString()?.trim()
                val chatId = editChatId.text?.toString()?.trim()
                
                prefs.telegramBotToken = if (botToken.isNullOrBlank()) null else botToken
                prefs.telegramChatId = if (chatId.isNullOrBlank()) null else chatId
                
                updateStatusIndicators()
                Toast.makeText(this, "Telegram-Konfiguration gespeichert", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Akku-Optimierung bereits deaktiviert", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun testLastBreath() {
        AlertDialog.Builder(this)
            .setTitle("Last Breath Test")
            .setMessage("Dies sendet eine Test-Nachricht mit aktuellem Standort an Telegram und speichert lastbreath.kml in Google Drive. Fortfahren?")
            .setPositiveButton("Test senden") { _, _ ->
                Toast.makeText(this, "Hole Standort...", Toast.LENGTH_SHORT).show()
                
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "GPS-Berechtigung fehlt", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
                fusedLocationClient.getCurrentLocation(
                    com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                    null
                ).addOnSuccessListener { location ->
                    lifecycleScope.launch {
                        sendLastBreathWithLocation(location)
                    }
                }.addOnFailureListener {
                    lifecycleScope.launch {
                        sendLastBreathWithLocation(null)
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
    
    private suspend fun sendLastBreathWithLocation(location: Location?) {
        val message = buildString {
            appendLine("🧪 TraceBack Test - Last Breath")
            appendLine()
            if (location != null) {
                appendLine("📍 Aktueller Standort:")
                appendLine("Lat: ${location.latitude}")
                appendLine("Lon: ${location.longitude}")
                appendLine("Genauigkeit: ${location.accuracy}m")
                appendLine()
                appendLine("https://maps.google.com/?q=${location.latitude},${location.longitude}")
            } else {
                appendLine("⚠️ Kein Standort verfügbar")
            }
            appendLine()
            appendLine("Zeit: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        }
        
        // Send to Telegram
        val telegramSuccess = telegramNotifier.sendEmergency(message)
        
        // Save to Drive as lastbreath.kml
        var driveSuccess = false
        if (location != null && driveManager.isReady()) {
            val kmlContent = generateLastBreathKml(location)
            driveSuccess = driveManager.uploadLastBreathKml(kmlContent)
        }
        
        runOnUiThread {
            val status = buildString {
                append(if (telegramSuccess) "✓ Telegram" else "✗ Telegram")
                append(" | ")
                append(if (driveSuccess) "✓ Drive" else "✗ Drive")
            }
            Toast.makeText(this@MainActivity, status, Toast.LENGTH_LONG).show()
        }
    }
    
    private fun generateLastBreathKml(location: Location): String {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>TraceBack Last Breath</name>
<description>Letzter bekannter Standort - ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}</description>
<Style id="lastBreathStyle">
    <IconStyle>
        <color>ff0000ff</color>
        <scale>1.5</scale>
        <Icon><href>http://maps.google.com/mapfiles/kml/paddle/red-stars.png</href></Icon>
    </IconStyle>
</Style>
<Placemark>
<name>🚨 Last Breath</name>
<description>Genauigkeit: ${location.accuracy}m</description>
<styleUrl>#lastBreathStyle</styleUrl>
<TimeStamp><when>$timestamp</when></TimeStamp>
<Point>
<coordinates>${location.longitude},${location.latitude},${location.altitude}</coordinates>
</Point>
</Placemark>
</Document>
</kml>"""
    }
    
    private fun syncToDriveNow() {
        if (!driveManager.isReady()) {
            Toast.makeText(this, "Bitte zuerst mit Google Drive verbinden", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "Synchronisiere...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                // Load today's points
                var points = kmlGenerator.loadTodayPoints()
                
                // If no points, get current location first
                if (points.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Hole aktuellen Standort...", Toast.LENGTH_SHORT).show()
                    }
                    
                    // Request current location
                    getCurrentLocationAndLog()
                    
                    // Wait a moment for location
                    kotlinx.coroutines.delay(3000)
                    
                    // Reload points
                    points = kmlGenerator.loadTodayPoints()
                }
                
                if (points.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Keine Standortdaten verfügbar. Ist GPS aktiv?", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                val kmlContent = kmlGenerator.generateDailyKml()
                val success = driveManager.uploadKml(kmlContent)
                
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this@MainActivity, "✓ Synchronisiert (${points.size} Punkte)", Toast.LENGTH_SHORT).show()
                        TraceBackApp.instance.securePrefs.lastSyncTimestamp = System.currentTimeMillis()
                        updateStatusIndicators()
                    } else {
                        Toast.makeText(this@MainActivity, "Synchronisierung fehlgeschlagen", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun getCurrentLocationAndLog() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.getCurrentLocation(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 
            null
        ).addOnSuccessListener { location ->
            location?.let {
                kmlGenerator.addPoint(it, isStopPoint = true)
            }
        }
    }
}

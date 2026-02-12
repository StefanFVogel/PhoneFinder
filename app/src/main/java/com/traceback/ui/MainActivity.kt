package com.traceback.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
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
import com.traceback.telegram.TelegramNotifier
import com.traceback.worker.PingWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var driveManager: DriveManager
    private lateinit var telegramNotifier: TelegramNotifier
    
    // Track if prominent disclosure was shown
    private var disclosureShown = false
    
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true -> {
                requestBackgroundLocationWithDisclosure()
            }
            else -> {
                Toast.makeText(this, "Standortberechtigung erforderlich", Toast.LENGTH_LONG).show()
            }
        }
        updateStatusIndicators()
    }
    
    private val backgroundLocationRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateStatusIndicators()
        if (!granted) {
            Toast.makeText(this, "Hintergrund-Standort erforderlich für Last Breath", Toast.LENGTH_LONG).show()
        }
    }
    
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        task.addOnSuccessListener { account ->
            driveManager.initialize(account)
            updateStatusIndicators()
            Toast.makeText(this, "Mit Google Drive verbunden", Toast.LENGTH_SHORT).show()
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
        
        setupUI()
        updateStatusIndicators()
    }
    
    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
    }
    
    private fun setupUI() {
        val prefs = TraceBackApp.instance.securePrefs
        
        // === STATUS ROW CLICK HANDLERS ===
        
        // GPS row - request with prominent disclosure
        binding.rowGps.setOnClickListener {
            val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasLocation) {
                showProminentDisclosureAndRequestPermission()
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
        
        // Drive row
        binding.rowDrive.setOnClickListener {
            if (driveManager.isReady()) {
                Toast.makeText(this, "✓ Mit Google Drive verbunden", Toast.LENGTH_SHORT).show()
            } else {
                signInToGoogle()
            }
        }
        
        // Telegram row
        binding.rowTelegram.setOnClickListener {
            showTelegramSetupDialog()
        }
        
        // SMS row
        binding.rowSms.setOnClickListener {
            showSmsSetupDialog()
        }
        
        // Battery row
        binding.rowBattery.setOnClickListener {
            requestBatteryExemption()
        }
        
        // === PING CONTROLS ===
        
        binding.switchTracking.isChecked = prefs.trackingEnabled
        binding.switchTracking.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasAllPermissions()) {
                binding.switchTracking.isChecked = false
                showProminentDisclosureAndRequestPermission()
                return@setOnCheckedChangeListener
            }
            
            prefs.trackingEnabled = isChecked
            if (isChecked) {
                PingWorker.schedule(this)
                Toast.makeText(this, "Ping-Überwachung aktiviert", Toast.LENGTH_SHORT).show()
            } else {
                PingWorker.cancel(this)
                Toast.makeText(this, "Ping-Überwachung deaktiviert", Toast.LENGTH_SHORT).show()
            }
            updateStatusIndicators()
        }
        
        // === LAST BREATH THRESHOLD CHECKBOXES ===
        
        val savedThresholds = prefs.lastBreathThresholds
        binding.checkbox15.isChecked = savedThresholds.contains(15)
        binding.checkbox8.isChecked = savedThresholds.contains(8)
        binding.checkbox4.isChecked = savedThresholds.contains(4)
        binding.checkbox2.isChecked = savedThresholds.contains(2)
        
        val checkboxListener = { _: android.widget.CompoundButton, _: Boolean ->
            saveThresholds()
            updateLastBreathIndicator()
        }
        
        binding.checkbox15.setOnCheckedChangeListener(checkboxListener)
        binding.checkbox8.setOnCheckedChangeListener(checkboxListener)
        binding.checkbox4.setOnCheckedChangeListener(checkboxListener)
        binding.checkbox2.setOnCheckedChangeListener(checkboxListener)
        
        updateLastBreathIndicator()
        
        // === ACTION BUTTONS ===
        
        binding.buttonSyncNow.setOnClickListener {
            sendPingNow()
        }
        
        binding.buttonTestLastBreath.setOnClickListener {
            testLastBreath()
        }
        
        binding.buttonHelp.setOnClickListener {
            showHelpDialog()
        }
    }
    
    private fun saveThresholds() {
        val thresholds = mutableSetOf<Int>()
        if (binding.checkbox15.isChecked) thresholds.add(15)
        if (binding.checkbox8.isChecked) thresholds.add(8)
        if (binding.checkbox4.isChecked) thresholds.add(4)
        if (binding.checkbox2.isChecked) thresholds.add(2)
        TraceBackApp.instance.securePrefs.lastBreathThresholds = thresholds
    }
    
    private fun updateLastBreathIndicator() {
        val count = listOf(
            binding.checkbox15.isChecked,
            binding.checkbox8.isChecked,
            binding.checkbox4.isChecked,
            binding.checkbox2.isChecked
        ).count { it }
        
        binding.indicatorLastBreath.setImageResource(
            when {
                count >= 2 -> R.drawable.indicator_green
                count == 1 -> R.drawable.indicator_yellow
                else -> R.drawable.indicator_red
            }
        )
    }
    
    private fun hasAllPermissions(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        
        return hasFine && hasBackground && batteryOk
    }
    
    /**
     * PROMINENT DISCLOSURE - Required by Google Play
     * Must be shown BEFORE requesting location permissions
     */
    private fun showProminentDisclosureAndRequestPermission() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_prominent_disclosure, null)
        
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Zustimmen") { _, _ ->
                disclosureShown = true
                requestLocationPermissions()
            }
            .setNegativeButton("Ablehnen") { _, _ ->
                Toast.makeText(this, "Ohne Standortberechtigung kann TraceBack nicht funktionieren", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun requestLocationPermissions() {
        locationPermissionRequest.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }
    
    private fun requestBackgroundLocationWithDisclosure() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                
                AlertDialog.Builder(this)
                    .setTitle("Hintergrund-Standort")
                    .setMessage("Für die Last Breath Funktion benötigt TraceBack die Berechtigung 'Immer erlauben'.\n\nDies ermöglicht es der App, Ihren Standort auch bei geschlossener App zu sichern, wenn der Akku kritisch wird.")
                    .setPositiveButton("Einstellungen öffnen") { _, _ ->
                        backgroundLocationRequest.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
        
        // Also request notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
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
        
        // Save channels
        val driveReady = driveManager.isReady()
        val telegramConfigured = !prefs.telegramBotToken.isNullOrBlank() && !prefs.telegramChatId.isNullOrBlank()
        val smsConfigured = !prefs.emergencySmsNumber.isNullOrBlank()
        val configuredChannels = listOf(driveReady, telegramConfigured, smsConfigured).count { it }
        
        fun getChannelIndicator(isConfigured: Boolean): Int {
            return when {
                isConfigured -> R.drawable.indicator_green
                configuredChannels == 0 -> R.drawable.indicator_red
                else -> R.drawable.indicator_yellow
            }
        }
        
        binding.indicatorDrive.setImageResource(getChannelIndicator(driveReady))
        binding.textDriveStatus.text = if (driveReady) "Google Drive ✓" else "Google Drive"
        
        binding.indicatorTelegram.setImageResource(getChannelIndicator(telegramConfigured))
        binding.textTelegramStatus.text = if (telegramConfigured) "Telegram Bot ✓" else "Telegram Bot"
        
        binding.indicatorSms.setImageResource(getChannelIndicator(smsConfigured))
        binding.textSmsStatus.text = if (smsConfigured) "Notfall-SMS ✓" else "Notfall-SMS"
        
        // Battery Status
        val pm = getSystemService(PowerManager::class.java)
        val batteryOptDisabled = pm.isIgnoringBatteryOptimizations(packageName)
        binding.indicatorBattery.setImageResource(
            if (batteryOptDisabled) R.drawable.indicator_green else R.drawable.indicator_red
        )
        binding.textBatteryStatus.text = if (batteryOptDisabled) "Akku-Optimierung aus ✓" else "Akku-Optimierung"
        
        // Last ping time
        val lastSync = prefs.lastSyncTimestamp
        if (lastSync > 0) {
            val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            binding.textLastSync.text = "Letzter Ping: ${dateFormat.format(Date(lastSync))}"
        } else {
            binding.textLastSync.text = "Letzter Ping: -"
        }
        
        // Last Breath indicator
        updateLastBreathIndicator()
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
        
        editBotToken.setText(prefs.telegramBotToken ?: "")
        editChatId.setText(prefs.telegramChatId ?: "")
        
        dialogView.findViewById<android.widget.TextView>(R.id.link_botfather).setOnClickListener {
            openUrl("https://t.me/BotFather")
        }
        dialogView.findViewById<android.widget.TextView>(R.id.link_userinfobot).setOnClickListener {
            openUrl("https://t.me/userinfobot")
        }
        
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
    
    private fun showSmsSetupDialog() {
        val prefs = TraceBackApp.instance.securePrefs
        
        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "+49 123 456789"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(prefs.emergencySmsNumber ?: "")
            setPadding(48, 32, 48, 32)
        }
        
        AlertDialog.Builder(this)
            .setTitle("Notfall-SMS Nummer")
            .setMessage("Diese Nummer erhält eine SMS mit deinem Standort bei Last Breath.\n\nFormat: +49 123 456789")
            .setView(editText)
            .setPositiveButton("Speichern") { _, _ ->
                val number = editText.text?.toString()?.trim()
                prefs.emergencySmsNumber = if (number.isNullOrBlank()) null else number
                updateStatusIndicators()
                
                if (!number.isNullOrBlank() && 
                    ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 200)
                    Toast.makeText(this, "SMS-Nummer gespeichert - bitte SMS-Berechtigung erteilen", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, 
                        if (number.isNullOrBlank()) "SMS-Nummer entfernt" else "SMS-Nummer gespeichert ✓",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .setNeutralButton("Entfernen") { _, _ ->
                prefs.emergencySmsNumber = null
                updateStatusIndicators()
                Toast.makeText(this, "SMS-Nummer entfernt", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
    
    private fun showHelpDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_help, null)
        
        val versionText = dialogView.findViewById<android.widget.TextView>(R.id.text_version)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            versionText.text = "Version ${pInfo.versionName} (${pInfo.longVersionCode})"
        } catch (e: Exception) {
            versionText.text = "Version 1.0"
        }
        
        dialogView.findViewById<android.widget.TextView>(R.id.link_botfather).setOnClickListener {
            openUrl("https://t.me/BotFather")
        }
        dialogView.findViewById<android.widget.TextView>(R.id.link_userinfobot).setOnClickListener {
            openUrl("https://t.me/userinfobot")
        }
        
        AlertDialog.Builder(this)
            .setTitle("ℹ️ Hilfe")
            .setView(dialogView)
            .setPositiveButton("Schließen", null)
            .show()
    }
    
    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
    
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Bitte Akku-Optimierung manuell in den Einstellungen deaktivieren", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(this, "✓ Akku-Optimierung bereits deaktiviert", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun sendPingNow() {
        if (!driveManager.isReady()) {
            Toast.makeText(this, "Bitte zuerst mit Google Drive verbinden", Toast.LENGTH_SHORT).show()
            signInToGoogle()
            return
        }
        
        Toast.makeText(this, "Sende Ping...", Toast.LENGTH_SHORT).show()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "GPS-Berechtigung fehlt", Toast.LENGTH_SHORT).show()
            return
        }
        
        val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.getCurrentLocation(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            null
        ).addOnSuccessListener { location ->
            lifecycleScope.launch {
                val success = uploadPing(location)
                runOnUiThread {
                    if (success) {
                        showLocationSentNotification("Ping", location)
                        TraceBackApp.instance.securePrefs.lastSyncTimestamp = System.currentTimeMillis()
                        updateStatusIndicators()
                        Toast.makeText(this@MainActivity, "✓ Ping gesendet", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Ping fehlgeschlagen", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Standort nicht verfügbar", Toast.LENGTH_SHORT).show()
        }
    }
    
    private suspend fun uploadPing(location: Location?): Boolean {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val kmlContent = if (location != null) {
            """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>TraceBack Ping</name>
<description>Letzter Ping - $dateStr</description>
<Style id="pingStyle">
    <IconStyle>
        <color>ff00ff00</color>
        <scale>1.0</scale>
        <Icon><href>http://maps.google.com/mapfiles/kml/paddle/grn-circle.png</href></Icon>
    </IconStyle>
</Style>
<Placemark>
<name>📍 Ping</name>
<description>$dateStr</description>
<styleUrl>#pingStyle</styleUrl>
<TimeStamp><when>$timestamp</when></TimeStamp>
<Point>
<coordinates>${location.longitude},${location.latitude},${location.altitude}</coordinates>
</Point>
</Placemark>
</Document>
</kml>"""
        } else {
            """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>TraceBack Ping</name>
<description>Ping ohne Standort - $dateStr</description>
</Document>
</kml>"""
        }
        
        return driveManager.uploadPingKml(kmlContent)
    }
    
    private fun testLastBreath() {
        AlertDialog.Builder(this)
            .setTitle("Last Breath Test")
            .setMessage("Dies testet alle konfigurierten Kanäle:\n\n• Google Drive (last_breath_*.kml)\n• Telegram (wenn eingerichtet)\n• SMS (wenn eingerichtet)\n\nFortfahren?")
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
        val prefs = TraceBackApp.instance.securePrefs
        
        val message = buildString {
            appendLine("🧪 TraceBack Test - Last Breath")
            appendLine()
            if (location != null) {
                appendLine("📍 Aktueller Standort:")
                appendLine("Lat: ${location.latitude}")
                appendLine("Lon: ${location.longitude}")
                appendLine()
                appendLine("https://maps.google.com/?q=${location.latitude},${location.longitude}")
            } else {
                appendLine("⚠️ Kein Standort verfügbar")
            }
            appendLine()
            appendLine("Zeit: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        }
        
        var driveSuccess = false
        var telegramSuccess = false
        var smsSuccess = false
        
        // 1. Drive
        if (location != null && driveManager.isReady()) {
            val kmlContent = generateLastBreathKml(location)
            driveSuccess = driveManager.uploadLastBreathKml(kmlContent)
        }
        
        // 2. Telegram
        if (!prefs.telegramBotToken.isNullOrBlank() && !prefs.telegramChatId.isNullOrBlank()) {
            telegramSuccess = telegramNotifier.sendEmergency(message)
        }
        
        // 3. SMS
        if (!prefs.emergencySmsNumber.isNullOrBlank()) {
            smsSuccess = sendTestSms(message)
        }
        
        // Show notification that location was sent
        if (location != null) {
            showLocationSentNotification("Last Breath Test", location)
        }
        
        runOnUiThread {
            val status = buildString {
                append(if (driveSuccess) "✓ Drive" else "✗ Drive")
                append(" | ")
                append(if (telegramSuccess) "✓ Telegram" else if (prefs.telegramBotToken.isNullOrBlank()) "- Telegram" else "✗ Telegram")
                append(" | ")
                append(if (smsSuccess) "✓ SMS" else if (prefs.emergencySmsNumber.isNullOrBlank()) "- SMS" else "✗ SMS")
            }
            Toast.makeText(this@MainActivity, status, Toast.LENGTH_LONG).show()
        }
    }
    
    private fun generateLastBreathKml(location: Location): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>TraceBack Last Breath</name>
<description>Letzter bekannter Standort - $dateStr</description>
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
    
    private fun sendTestSms(message: String): Boolean {
        val number = TraceBackApp.instance.securePrefs.emergencySmsNumber ?: return false
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) 
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 200)
            return false
        }
        
        return try {
            val smsManager = android.telephony.SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Show notification when location is sent - required by Google Play policy
     */
    private fun showLocationSentNotification(type: String, location: Location?) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val message = if (location != null) {
            "$type gesendet: ${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}"
        } else {
            "$type gesendet (ohne Standort)"
        }
        
        val notification = NotificationCompat.Builder(this, TraceBackApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle("📍 TraceBack")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}

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
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
            showFeatureNotification(
                "☁️ Google Drive verbunden",
                "📍 Gesammelte Daten:\n" +
                "• GPS-Koordinaten\n" +
                "• Zeitstempel\n\n" +
                "📁 Speicherort: TraceBack-Ordner in deinem Google Drive\n" +
                "• ping.kml - Letzter Ping\n" +
                "• last_breath_*.kml - Notfall-Standorte\n\n" +
                "🔒 Sicherheit: Nur du hast Zugriff auf deinen Drive-Ordner."
            )
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Google Sign-In fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
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
        
        // Drive row - show disclosure before sign-in
        binding.rowDrive.setOnClickListener {
            if (driveManager.isReady()) {
                Toast.makeText(this, "✓ Mit Google Drive verbunden", Toast.LENGTH_SHORT).show()
            } else {
                showDriveDisclosure()
            }
        }
        
        // Telegram row - show disclosure before setup
        binding.rowTelegram.setOnClickListener {
            val prefs = TraceBackApp.instance.securePrefs
            if (prefs.telegramBotToken.isNullOrBlank()) {
                showTelegramDisclosure()
            } else {
                showTelegramSetupDialog()
            }
        }
        
        // SMS row - show disclosure before setup
        binding.rowSms.setOnClickListener {
            val prefs = TraceBackApp.instance.securePrefs
            if (prefs.emergencySmsNumber.isNullOrBlank()) {
                showSmsDisclosure()
            } else {
                showSmsSetupDialog()
            }
        }
        
        // Battery row - show disclosure before requesting exemption
        binding.rowBattery.setOnClickListener {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryDisclosure()
            } else {
                Toast.makeText(this, "✓ Akku-Optimierung bereits deaktiviert", Toast.LENGTH_SHORT).show()
            }
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
                val interval = prefs.pingIntervalMinutes
                PingWorker.schedule(this, interval)
                Toast.makeText(this, "Ping-Überwachung aktiviert (${PingWorker.getIntervalLabel(interval)})", Toast.LENGTH_SHORT).show()
                showPingDisclosure(interval)
            } else {
                PingWorker.cancel(this)
                Toast.makeText(this, "Ping-Überwachung deaktiviert", Toast.LENGTH_SHORT).show()
            }
            updateStatusIndicators()
        }
        
        // === PING INTERVAL SPINNER ===
        setupPingIntervalSpinner(prefs)
        
        // === LAST BREATH THRESHOLD CHECKBOXES ===
        
        val savedThresholds = prefs.lastBreathThresholds
        binding.checkbox15.isChecked = savedThresholds.contains(15)
        binding.checkbox8.isChecked = savedThresholds.contains(8)
        binding.checkbox4.isChecked = savedThresholds.contains(4)
        binding.checkbox2.isChecked = savedThresholds.contains(2)
        
        val checkboxListener = { _: android.widget.CompoundButton, _: Boolean ->
            saveThresholds()
            updateLastBreathIndicator()
            showThresholdNotification()
        }
        
        binding.checkbox15.setOnCheckedChangeListener(checkboxListener)
        binding.checkbox8.setOnCheckedChangeListener(checkboxListener)
        binding.checkbox4.setOnCheckedChangeListener(checkboxListener)
        binding.checkbox2.setOnCheckedChangeListener(checkboxListener)
        
        updateLastBreathIndicator()
        
        // === ACTION BUTTONS ===
        
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
    
    private fun setupPingIntervalSpinner(prefs: com.traceback.util.SecurePrefs) {
        val intervals = PingWorker.INTERVALS
        val labels = intervals.map { PingWorker.getIntervalLabel(it) }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerPingInterval.adapter = adapter
        
        // Set current selection
        val currentInterval = prefs.pingIntervalMinutes
        val index = intervals.indexOf(currentInterval).takeIf { it >= 0 } ?: 1 // Default to 1 hour
        binding.spinnerPingInterval.setSelection(index)
        
        binding.spinnerPingInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val newInterval = intervals[position]
                if (newInterval != prefs.pingIntervalMinutes) {
                    prefs.pingIntervalMinutes = newInterval
                    if (prefs.trackingEnabled) {
                        PingWorker.schedule(this@MainActivity, newInterval)
                        Toast.makeText(this@MainActivity, "Ping-Intervall: ${PingWorker.getIntervalLabel(newInterval)}", Toast.LENGTH_SHORT).show()
                    }
                    updatePingIndicator(newInterval)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Initial indicator update
        updatePingIndicator(prefs.pingIntervalMinutes)
    }
    
    /**
     * Update ping interval indicator color
     * Yellow = high message frequency (15min, 1h)
     * Green = low message frequency (5h, 24h)
     * No red - ping helps detect permission revocation
     */
    private fun updatePingIndicator(intervalMinutes: Int) {
        val colorRes = when (intervalMinutes) {
            15, 60 -> R.drawable.indicator_yellow  // High frequency
            else -> R.drawable.indicator_green      // 5h, 24h = low frequency
        }
        binding.indicatorPing.setImageResource(colorRes)
    }
    
    /**
     * Show disclosure when Ping is enabled
     */
    private fun showPingDisclosure(intervalMinutes: Int) {
        showFeatureNotification(
            "📡 Ping-Überwachung aktiviert",
            "TraceBack sendet alle ${PingWorker.getIntervalLabel(intervalMinutes)} deinen Standort an Google Drive.\n\n" +
            "📍 Gesammelte Daten:\n" +
            "• GPS-Koordinaten\n" +
            "• Zeitstempel\n\n" +
            "☁️ Speicherort: Google Drive (TraceBack-Ordner)"
        )
    }
    
    private fun showThresholdNotification() {
        val thresholds = TraceBackApp.instance.securePrefs.lastBreathThresholds
        if (thresholds.isEmpty()) {
            showFeatureNotification(
                "🚨 Last Breath deaktiviert",
                "Keine Schwellen ausgewählt. Bei kritischem Akku wird KEIN Standort gesendet!"
            )
        } else {
            val sorted = thresholds.sortedDescending()
            showFeatureNotification(
                "🚨 Last Breath Schwellen: ${sorted.joinToString(", ")}%",
                "Bei Akkustand unter ${sorted.joinToString("%, ")}% wird dein Standort automatisch gesichert."
            )
        }
    }
    
    private fun updateLastBreathIndicator() {
        val has15 = binding.checkbox15.isChecked
        val has8 = binding.checkbox8.isChecked
        val has4 = binding.checkbox4.isChecked
        val has2 = binding.checkbox2.isChecked
        val count = listOf(has15, has8, has4, has2).count { it }
        
        // Logic: 0 = Red, only 2% = Yellow, otherwise Green
        binding.indicatorLastBreath.setImageResource(
            when {
                count == 0 -> R.drawable.indicator_red
                count == 1 && has2 && !has15 && !has8 && !has4 -> R.drawable.indicator_yellow
                else -> R.drawable.indicator_green
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
    
    // ==================== FEATURE DISCLOSURE DIALOGS ====================
    // Each feature gets its own disclosure explaining WHY the permission is needed
    
    /**
     * Google Drive disclosure - explains data storage
     */
    private fun showDriveDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("☁️ Google Drive Berechtigung")
            .setMessage(
                "TraceBack möchte Zugriff auf Google Drive um:\n\n" +
                "📍 Gesammelte Daten speichern:\n" +
                "• GPS-Koordinaten (Breiten-/Längengrad)\n" +
                "• Zeitstempel\n" +
                "• Bei Last Breath: Sichtbare WLAN-Netzwerke\n\n" +
                "📁 Speicherort:\n" +
                "Ein eigener \"TraceBack\"-Ordner in deinem Drive\n\n" +
                "🔒 Sicherheit:\n" +
                "Nur du hast Zugriff auf diesen Ordner. " +
                "TraceBack kann nur Dateien lesen/schreiben, die es selbst erstellt hat."
            )
            .setPositiveButton("Zustimmen & Verbinden") { _, _ ->
                signInToGoogle()
            }
            .setNegativeButton("Ablehnen", null)
            .setCancelable(true)
            .show()
    }
    
    /**
     * Telegram disclosure - explains notification channel
     */
    private fun showTelegramDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("📱 Telegram Bot Berechtigung")
            .setMessage(
                "TraceBack möchte einen Telegram Bot konfigurieren um:\n\n" +
                "📍 Bei Last Breath senden:\n" +
                "• GPS-Koordinaten (Breiten-/Längengrad)\n" +
                "• Sichtbare WLAN-Netzwerke\n" +
                "• Akkustand und Zeitstempel\n\n" +
                "📡 Bei aktiviertem Ping:\n" +
                "• Regelmäßige Standort-Updates\n" +
                "• Status-Benachrichtigungen\n\n" +
                "🔒 Sicherheit:\n" +
                "Du erstellst deinen eigenen Bot bei @BotFather. " +
                "Nur du kennst den Token und die Chat-ID."
            )
            .setPositiveButton("Verstanden") { _, _ ->
                showTelegramSetupDialog()
            }
            .setNegativeButton("Ablehnen", null)
            .setCancelable(true)
            .show()
    }
    
    /**
     * SMS disclosure - explains fallback channel and privacy concerns
     */
    private fun showSmsDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("📱 SMS Berechtigung")
            .setMessage(
                "TraceBack möchte SMS-Zugriff als Notfall-Fallback:\n\n" +
                "📍 Bei Last Breath senden:\n" +
                "• GPS-Koordinaten (Breiten-/Längengrad)\n" +
                "• Sichtbare WLAN-Netzwerke\n" +
                "• Akkustand und Zeitstempel\n\n" +
                "✅ Vorteil:\n" +
                "Funktioniert auch OHNE Internet!\n\n" +
                "⚠️ WICHTIG - Datenschutz:\n" +
                "• SMS sind NICHT verschlüsselt\n" +
                "• Dein Mobilfunkanbieter kann Inhalte lesen\n" +
                "• Behörden können auf Anfrage zugreifen\n\n" +
                "Nutze SMS nur als letzten Fallback wenn kein Internet verfügbar."
            )
            .setPositiveButton("Verstanden") { _, _ ->
                showSmsSetupDialog()
            }
            .setNegativeButton("Ablehnen", null)
            .setCancelable(true)
            .show()
    }
    
    /**
     * SMS permission disclosure - shown right before Android permission request
     */
    private fun showSmsPermissionDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("📱 SMS senden erlauben?")
            .setMessage(
                "TraceBack benötigt die SMS-Berechtigung um bei Last Breath " +
                "eine Notfall-SMS mit deinem Standort zu senden.\n\n" +
                "Dies ist ein Fallback für den Fall, dass kein Internet verfügbar ist.\n\n" +
                "⚠️ Erinnerung: SMS sind nicht verschlüsselt!"
            )
            .setPositiveButton("Erlauben") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 200)
            }
            .setNegativeButton("Ablehnen") { _, _ ->
                Toast.makeText(this, "SMS-Nummer gespeichert (ohne Berechtigung)", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Battery optimization disclosure - explains why it's needed
     */
    private fun showBatteryDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("🔋 Akku-Optimierung deaktivieren")
            .setMessage(
                "TraceBack benötigt diese Ausnahme weil:\n\n" +
                "⚡ Android beendet Hintergrund-Apps:\n" +
                "Um Akku zu sparen, stoppt Android Apps die im Hintergrund laufen. " +
                "Das würde TraceBack daran hindern, bei kritischem Akkustand zu reagieren.\n\n" +
                "🚨 Last Breath funktioniert nur wenn:\n" +
                "Die App auch bei geschlossenem Bildschirm auf niedrigen Akkustand reagieren kann.\n\n" +
                "📊 Auswirkung:\n" +
                "Minimaler zusätzlicher Akkuverbrauch. " +
                "TraceBack läuft nur bei bestimmten System-Events (Akkustand, Ping-Intervall)."
            )
            .setPositiveButton("Einstellung öffnen") { _, _ ->
                requestBatteryExemption()
            }
            .setNegativeButton("Ablehnen", null)
            .setCancelable(true)
            .show()
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
                
                if (!botToken.isNullOrBlank() && !chatId.isNullOrBlank()) {
                    Toast.makeText(this, "Telegram-Konfiguration gespeichert ✓", Toast.LENGTH_SHORT).show()
                    showFeatureNotification(
                        "📱 Telegram Bot konfiguriert",
                        "📍 Gesammelte Daten bei Last Breath:\n" +
                        "• GPS-Koordinaten\n" +
                        "• Sichtbare WLAN-Netzwerke\n" +
                        "• Zeitstempel\n\n" +
                        "📤 Übertragung: Telegram Bot API (HTTPS)\n" +
                        "🔒 Hinweis: Telegram-Nachrichten sind Ende-zu-Ende verschlüsselt wenn du einen privaten Chat nutzt.\n\n" +
                        "Tipp: Teste mit 'Last Breath testen' ob alles funktioniert."
                    )
                } else {
                    Toast.makeText(this, "Telegram-Konfiguration entfernt", Toast.LENGTH_SHORT).show()
                }
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
                
                if (!number.isNullOrBlank()) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                        // Show disclosure before Android permission request
                        showSmsPermissionDisclosure()
                    } else {
                        Toast.makeText(this, "SMS-Nummer gespeichert ✓", Toast.LENGTH_SHORT).show()
                    }
                    showFeatureNotification(
                        "📱 Notfall-SMS konfiguriert",
                        "📍 Gesammelte Daten bei Last Breath:\n" +
                        "• GPS-Koordinaten\n" +
                        "• Sichtbare WLAN-Netzwerke\n" +
                        "• Zeitstempel\n\n" +
                        "📤 Übertragung: SMS an $number\n\n" +
                        "⚠️ WICHTIG: SMS sind NICHT verschlüsselt!\n" +
                        "• Mobilfunkanbieter können Inhalt lesen\n" +
                        "• Behörden können auf Anfrage zugreifen\n\n" +
                        "✅ Vorteil: Funktioniert auch ohne Internet!"
                    )
                } else {
                    Toast.makeText(this, "SMS-Nummer entfernt", Toast.LENGTH_SHORT).show()
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
    }
    
    private fun testLastBreath() {
        AlertDialog.Builder(this)
            .setTitle("Last Breath Test")
            .setMessage("Dies testet alle konfigurierten Kanäle:\n\n• Google Drive (last_breath_*.kml)\n• Telegram (wenn eingerichtet)\n• SMS (wenn eingerichtet)\n• WiFi-Netzwerke werden gescannt\n\nFortfahren?")
            .setPositiveButton("Test senden") { _, _ ->
                Toast.makeText(this, "Sende Last Breath Test...", Toast.LENGTH_SHORT).show()
                
                lifecycleScope.launch {
                    val result = com.traceback.util.LastBreathSender.send(
                        context = this@MainActivity,
                        reason = "Manueller Test",
                        isTest = true
                    )
                    
                    // Show notification
                    if (result.location != null) {
                        showLocationSentNotification("Last Breath Test", result.location)
                    }
                    
                    // Show result
                    runOnUiThread {
                        val prefs = TraceBackApp.instance.securePrefs
                        val status = buildString {
                            append(if (result.driveSuccess) "✓ Drive" else "✗ Drive")
                            append(" | ")
                            append(if (result.telegramSuccess) "✓ Telegram" else if (prefs.telegramBotToken.isNullOrBlank()) "- Telegram" else "✗ Telegram")
                            append(" | ")
                            append(if (result.smsSuccess) "✓ SMS" else if (prefs.emergencySmsNumber.isNullOrBlank()) "- SMS" else "✗ SMS")
                            append("\n📶 ${result.wifiNetworks.size} WLANs gefunden")
                        }
                        Toast.makeText(this@MainActivity, status, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
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
    
    /**
     * Show notification when a feature is enabled/configured
     */
    private fun showFeatureNotification(title: String, message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val notification = NotificationCompat.Builder(this, TraceBackApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}

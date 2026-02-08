package com.traceback.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import com.traceback.service.TrackingService
import com.traceback.telegram.TelegramNotifier
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var driveManager: DriveManager
    private lateinit var telegramNotifier: TelegramNotifier
    
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
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        
        val client = GoogleSignIn.getClient(this, gso)
        googleSignInLauncher.launch(client.signInIntent)
    }
    
    private fun showTelegramSetupDialog() {
        val prefs = TraceBackApp.instance.securePrefs
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_telegram_setup, null)
        // Note: Would need to implement dialog layout with EditTexts for token/chatId
        
        AlertDialog.Builder(this)
            .setTitle("Telegram Bot Konfiguration")
            .setMessage("Bot-Token und Chat-ID eingeben.\n\nBot erstellen: @BotFather\nChat-ID: @userinfobot")
            .setView(dialogView)
            .setPositiveButton("Speichern") { _, _ ->
                // Save values from dialog
                updateStatusIndicators()
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
            .setMessage("Dies sendet eine Test-Nachricht an Telegram. Fortfahren?")
            .setPositiveButton("Test senden") { _, _ ->
                lifecycleScope.launch {
                    val success = telegramNotifier.sendEmergency(
                        "🧪 TraceBack Test\n\nDies ist ein Test der Last Breath Funktion."
                    )
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            if (success) "Test erfolgreich!" else "Test fehlgeschlagen",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}

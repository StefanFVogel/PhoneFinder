# TraceBack

Autonomes Standort-Sicherungssystem für Android — sichert deinen Standort bei kritischem Akkustand in Google Drive, Telegram und SMS.

## Features

- **Ping-Überwachung:** Regelmäßige Standort-Checks (konfigurierbar: 15min, 1h, 5h, 1 Tag)
- **Google Drive Sync:** Sichtbarer "TraceBack"-Ordner mit KML + HTML Dateien
- **Last Breath:** Notfall-Standort via Google Drive, Telegram und SMS bei konfigurierbaren Akku-Schwellen (20%, 10%, 5%, 3%)
- **Lade-Warnung:** Benachrichtigung bei Akku über 80% zum Schutz der Akku-Lebensdauer
- **WiFi-Scanning:** Sichtbare WLAN-Netzwerke bei Ping und Last Breath
- **HTML Export:** Standort-Berichte mit eingebetteter OpenStreetMap-Karte
- **Disclosure-Dialoge:** Jedes Feature erklärt vor der Berechtigung, warum es diese braucht

## Architektur

```
com.traceback/
├── service/
│   ├── TrackingService.kt      # Battery-Monitor + Last Breath Trigger
│   ├── PingService.kt          # Foreground Service für Ping-Intervalle
│   ├── PingAlarmReceiver.kt    # AlarmManager Receiver
│   └── ShutdownReceiver.kt     # Last Breath bei Shutdown
├── receiver/
│   └── BootReceiver.kt         # Neustart nach Boot
├── drive/
│   └── DriveManager.kt         # Google Drive REST API
├── telegram/
│   └── TelegramNotifier.kt     # Bot API für Notfall-Nachrichten
├── kml/
│   └── KmlGenerator.kt         # Koordinaten → KML/XML
├── activity/
│   └── ActivityRecognitionManager.kt
├── ui/
│   └── MainActivity.kt         # Dashboard mit Ampelsystem
└── util/
    ├── SecurePrefs.kt           # EncryptedSharedPreferences
    ├── LastBreathSender.kt      # Unified Last Breath Logik
    └── BatteryThresholdChecker.kt # Reine Schwellen-Logik (testbar)
```

## Berechtigungen

- `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION`
- `SEND_SMS` + `RECEIVE_BOOT_COMPLETED`
- `FOREGROUND_SERVICE_LOCATION` (Android 14+)
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

## Build

```bash
./gradlew assembleDebug
```

## Tests

```bash
./gradlew :jvm-tests:test    # Unit-Tests (reine Logik)
./gradlew :app:test           # App-Unit-Tests
```

## Test-Konzept

1. **Ping-Test:** Intervall auf 15min setzen → Standort kommt in Drive + Telegram?
2. **Shutdown-Test:** Shutdown bei niedrigem Akku → Telegram/SMS in <5s?
3. **Schwellen-Test:** Mehrere Schwellen aktivieren → Akku entladen → jede Schwelle löst genau einmal aus?

## Security

Bot-Token und Chat-ID werden verschlüsselt in EncryptedSharedPreferences gespeichert.
Niemals im Klartext in Logs oder KML-Files!

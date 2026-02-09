# TraceBack

Autonomes Standort-Logbuch für Android — Sicherheit durch Bewegungsprofile im eigenen Google Drive.

## Features

- **Intelligentes Tracking:** GPS alle 300m bei Bewegung, 1x täglich bei stationär
- **Google Drive Sync:** Stündlich in appDataFolder, KML-Format
- **Data Aging:** Tages- → Wochen- → Monats-Logs mit automatischer Archivierung
- **Last Breath:** Notfall-Standort via Telegram/SMS bei Shutdown oder Akku <2%

## Architektur

```
com.traceback/
├── service/
│   └── TrackingService.kt      # Foreground Service, Hauptlogik
├── drive/
│   └── DriveManager.kt         # Google Drive REST API
├── telegram/
│   └── TelegramNotifier.kt     # Bot API für Notfall-Nachrichten
├── kml/
│   └── KmlGenerator.kt         # Koordinaten → XML
├── ui/
│   ├── MainActivity.kt         # Dashboard mit Ampelsystem
│   └── DeviceLearningDialog.kt # BT/WLAN Klassifizierung
└── util/
    └── SecurePrefs.kt          # EncryptedSharedPreferences
```

## Berechtigungen

- `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION`
- `SEND_SMS` + `RECEIVE_BOOT_COMPLETED`
- `FOREGROUND_SERVICE_LOCATION` (Android 14+)

## Build

```bash
./gradlew assembleDebug
```

## Test-Konzept

1. **Funkloch-Test:** Gerät in Alufolie → 2km Fahrt → Route nachsynchronisiert?
2. **Shutdown-Test:** Shutdown bei niedrigem Akku → Telegram/SMS in <5s?
3. **Stationary-Test:** 24h im Home-WLAN → nur 1 Datenpunkt?

## Security

Bot-Token und Chat-ID werden verschlüsselt in EncryptedSharedPreferences gespeichert.
Niemals im Klartext in Logs oder KML-Files!
# Trigger rebuild with secrets

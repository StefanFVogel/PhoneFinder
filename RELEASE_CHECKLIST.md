# TraceBack Release Checklist

Bei **JEDEM Commit** mit Feature-Änderungen diese Punkte prüfen:

## 📋 Dokumentation

- [ ] **strings.xml** - Hilfe-Dialoge aktualisieren
- [ ] **store/store-listing.md** - Play Store Beschreibung
- [ ] **store-assets/STORE_LISTING.md** - Play Store Beschreibung (Kopie)
- [ ] **privacy-policy.md** - Falls Datensammlung sich ändert
- [ ] **README.md** - Architektur und Features

## 📝 Changelog

- [ ] **CHANGELOG.md** - Version + Änderungen dokumentieren

## 🧪 Vor Release

- [ ] Version in `build.gradle.kts` erhöhen (versionCode + versionName)
- [ ] Alle neuen Features in Hilfe dokumentiert (strings.xml + dialog_help.xml)
- [ ] Privacy Policy aktuell (wenn Berechtigungen geändert)
- [ ] Unit-Tests laufen: `./gradlew :app:testDebugUnitTest`
- [ ] Debug-Build erfolgreich: `./gradlew assembleDebug`

## 🔍 Google Play Compliance

- [ ] Prominent Disclosure vor jeder sensiblen Berechtigung
- [ ] Background-Location: Text enthält "Standort", "Hintergrund"/"geschlossen", Feature-Liste
- [ ] Keine Features im Store-Listing beworben die nicht existieren
- [ ] Store-Beschreibung stimmt mit tatsächlichen App-Features überein

---

## Aktuelle Version: 1.5.0 (versionCode 10)

### Änderungen in 1.5.0
- Lade-Warnung bei 80% (Akku-Schonung)
- Neue Last Breath Schwellen: 20%, 10%, 5%, 3% (ersetzt alte 15%, 8%, 4%, 2%)
- BatteryThresholdChecker für Testbarkeit extrahiert
- SmsManager-Deprecation-Fix (Android 12+)
- Dead Code entfernt (service/BootReceiver.kt)
- README, Store-Listings und Hilfe-Texte komplett überarbeitet
- 22 neue Unit-Tests

### Änderungen in 1.4.0
- Zuverlässige Ping-Intervalle via Foreground Service + AlarmManager
- Automatischer Neustart nach Geräte-Boot
- Sofortiger erster Ping beim Aktivieren

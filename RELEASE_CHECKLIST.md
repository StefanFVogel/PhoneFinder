# TraceBack Release Checklist

Bei **JEDEM Commit** mit Feature-Änderungen diese Punkte prüfen:

## 📋 Dokumentation

- [ ] **strings.xml** - Hilfe-Dialoge aktualisieren
- [ ] **STORE_LISTING.md** - Play Store Beschreibung
- [ ] **privacy-policy.md** - Falls Datensammlung sich ändert
- [ ] **README.md** - Falls vorhanden

## 📝 Changelog

- [ ] **CHANGELOG.md** - Version + Änderungen dokumentieren

## 🧪 Vor Release

- [ ] Version in `build.gradle.kts` erhöhen
- [ ] Alle neuen Features in Hilfe dokumentiert
- [ ] Privacy Policy aktuell (wenn Berechtigungen geändert)

---

## Aktuelle Version: 1.3.1 (versionCode 5)

### Änderungen in 1.3.1
- Ping sendet jetzt an ALLE konfigurierten Kanäle (Drive + Telegram)
- Ping-Intervall-Indikator: 🟡 15m/1h (hohe Frequenz), 🟢 5h/24h (niedrig)
- Feature-spezifische Disclosure-Dialoge vor Android-Berechtigungen

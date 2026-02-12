# TraceBack Changelog

## v1.3.2 (versionCode 6) - 2026-02-12

### Neue Features
- **HTML-Ansicht in Drive**: Neben KML wird jetzt auch HTML mit eingebetteter Karte gespeichert
  - `ping.html` - Aktuelle Position mit OpenStreetMap-Karte
  - `last_breath_*.html` - Notfall-Standort mit Karte und WLAN-Liste
  - Gleiche Ansicht wie bei Telegram: Karte + Links zu Google Maps & OpenStreetMap
- WLAN-Netzwerke werden jetzt auch in KML-Dateien gespeichert

---

## v1.3.1 (versionCode 5) - 2026-02-12

### Neue Features
- **Ping an alle Kanäle**: Ping sendet jetzt an Drive UND Telegram (nicht nur Drive)
- **Ping-Intervall-Indikator**: Zeigt Nachrichtenfrequenz an
  - 🟡 Gelb: 15 Minuten, 1 Stunde (hohe Frequenz)
  - 🟢 Grün: 5 Stunden, 1 Tag (niedrige Frequenz)
  - Kein Rot mehr - Ping hilft Berechtigungs-Entzug zu erkennen

### Verbesserungen
- **Feature-spezifische Disclosure-Dialoge**: Vor jeder Android-Berechtigung wird erklärt WARUM sie für DIESES Feature benötigt wird
  - Google Drive: Erklärt Datenspeicherung und Sicherheit
  - Telegram: Erklärt welche Daten gesendet werden
  - SMS: Warnung über fehlende Verschlüsselung
  - Akku: Erklärt warum Ausnahme nötig ist
- Aktualisierte Hilfe-Texte

---

## v1.3.0 (versionCode 4) - 2026-02-11

### Neue Features
- Ping-Intervall auswählbar (15min, 1h, 5h, 1 Tag)
- Unified LastBreathSender

### Verbesserungen
- Network-Code Cleanup (597 Zeilen entfernt)
- Icon-Fix (Adaptive Icons entfernt)

---

## v1.2.0 (versionCode 3) - 2026-02-10

### Neue Features
- Google Drive Integration mit sichtbarem "TraceBack"-Ordner
- Telegram Bot für Notfall-Benachrichtigungen
- Last Breath bei kritischem Akkustand
- Ping-Überwachung zur Funktionsprüfung

### Erste Version
- GPS-Tracking
- KML-Export
- Schwellen-basierte Last Breath Trigger

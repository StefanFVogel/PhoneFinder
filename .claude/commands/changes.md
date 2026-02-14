# Zusammenfassung der Änderungen seit letztem Commit

Fasse alle Änderungen seit dem letzten Commit zusammen:

1. Führe `git status` aus um geänderte und neue Dateien zu sehen
2. Führe `git diff --stat` aus für eine Übersicht der Änderungen
3. Führe `git diff` für die geänderten Dateien aus (nicht für Excel/binäre Dateien)
4. Zeige den letzten Commit mit `git log -1 --format='%h %s'`

**WICHTIG:** Ignoriere alle `.xlsx` und binäre Dateien komplett in der Zusammenfassung. Diese sollen weder in "Geänderte Dateien" noch in "Neue Dateien" aufgelistet werden.

Erstelle dann eine übersichtliche Zusammenfassung in diesem Format:

## Letzter Commit
- Hash und Message

## Geänderte Dateien
- Liste aller geänderten Code-Dateien mit kurzer Beschreibung was sich geändert hat (keine .xlsx!)

## Neue Dateien
- Liste aller neuen (untracked) Code-Dateien (keine .xlsx!)

## Vorgeschlagenes Commit Statement
- Ein sinnvolles Commit-Statement basierend auf den Änderungen (auf Englisch, conventional commits Format)

# Baumradar 🌳

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Willkommen bei **Baumradar**! Dieses Open-Source-Projekt ist eine Kombination aus einem robusten Java-Backend (Data-Processor) und einer modernen Android-App. 

**Baumradar ist ein Open-Data-basiertes Werkzeug, mit dem du Bäume in deiner direkten Umgebung erkunden und bei deiner Fortbewegung durch die Stadt gezielt meiden kannst – besonders hilfreich, wenn du an einer Baumpollen-Allergie (z.B. gegen Frühblüher) leidest.**

## 🌟 Detaillierte Funktionen
- **Allergie-Tracking & Warn-Zonen:** Wähle deine persönlichen Allergien (z. B. Birke, Hasel) aus. Diese Bäume werden auf der Karte hervorgehoben.
- **Erkundungsmodus:** Du stehst vor einem Baum und fragst dich "Was ist das für einer?" Der Erkundungsmodus zeigt dir alle Bäume im Umkreis von 100 Metern an, unabhängig von deinem Allergie-Profil.
- **AR-Richtungsanzeige (Kompass):** Baumradar blendet Pfeile und Entfernungsangaben ein, die dir in Echtzeit die genaue Richtung und Distanz zu den markierten Bäumen anzeigen.
- **Allergiefreies Routing & Warnungen:** Plane deine Wegstrecke durch die Stadt. Die App berechnet deine Route und **warnt dich direkt bei der Routenführung visuell**, sobald du Gefahr läufst, einem für dich konfigurierten Allergen-Baum (oder einer Baumgruppe) zu nahe zu kommen.
- **Multi-City Support:** Unterstützt Städte wie Wien, Graz, Innsbruck, Linz, Berlin, Hamburg, Freiburg, Dortmund, Zürich und Basel.
- **Offline First:** Die App lädt komprimierte, aufbereitete SQLite-Datenbanken für jede Stadt herunter. Sobald geladen, ist keine Internetverbindung mehr nötig (außer für das initialie Routing).
- **Open Data & Zero Trust:** Die Daten werden vom Backend verarbeitet und kryptografisch mit Ed25519 signiert. Dadurch kann jeder sicherstellen, dass die Daten authentisch und unverfälscht sind.

## 🚀 Installation (Android App)
Du kannst die App direkt auf deinem Android-Gerät installieren:
1. Lade dir die aktuellste `Baumradar.apk` aus dem Repository (Releases-Tab) herunter.
2. Erlaube auf deinem Smartphone die Installation aus "Unbekannten Quellen", falls noch nicht geschehen.
3. Öffne die APK und folge den Anweisungen.

Alternativ kannst du das Projekt in Android Studio klonen und selbst kompilieren!

## 📖 Dokumentation
Baumradar besteht aus zwei Hauptteilen und einer offenen Datenstruktur. Hier findest du detaillierte Dokumentationen zu den einzelnen Bereichen:

1. **[Android App Architektur](docs/app_architecture.md)**: Einblicke in die Kotlin-App, Jetpack Compose UI, Room Datenbanken und das Routing-System.
2. **[Backend / Data-Processor](docs/backend_architecture.md)**: Wie das Java-Backend Open Data aus verschiedenen Städten einliest, übersetzt, in Chunks splittet und signiert.
3. **[Datenstruktur & Third-Party Nutzung](docs/data_structure.md)**: Wie du als externer Entwickler die offenen, verifizierten Baumradar-Daten für deine eigene App (z.B. iOS, Web) nutzen kannst.

*(For the English documentation, see [README_en.md](README_en.md))*

## 📜 Lizenz
Dieses Projekt ist unter der **MIT License** veröffentlicht. Siehe [LICENSE](LICENSE) für weitere Details.

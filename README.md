*(For the English documentation, see [README_en.md](README_en.md))*

# Baumradar 🌳

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Willkommen bei **Baumradar**! Dieses Open-Source-Projekt ist eine Kombination aus einem robusten Java-Backend (Data-Processor) und einer modernen Android-App. 

**Baumradar ist ein Open-Data-basiertes Werkzeug, mit dem du Bäume in deiner direkten Umgebung erkunden und bei deiner Fortbewegung durch die Stadt gezielt meiden kannst – besonders hilfreich, wenn du an einer Baumpollen-Allergie (z.B. gegen Frühblüher) leidest.**

## 🌟 Detaillierte Funktionen

### 🌿 Allergie-Profil & Warn-Zonen
In deinem persönlichen Allergie-Profil wählst du gezielt die Baumgattungen aus, auf die du allergisch reagierst (z. B. Birke, Hasel, Esche). Die App unterscheidet dabei zwischen zwei Stufen:
- **"Umfahren 🚫"**: Diese Bäume werden beim Routing berücksichtigt – die App versucht, Routen zu berechnen, die diese Baumgattungen möglichst meiden.
- **"Warnung ⚠️"**: Für diese Bäume registriert Baumradar im Hintergrund Geofence-Zonen bei Android. Du erhältst eine **Push-Benachrichtigung direkt auf den Sperrbildschirm**, sobald du dich einem solchen Baum näherst – auch wenn die App gerade geschlossen ist. Dafür ist keine permanent laufende Hintergrund-App nötig, Android überwacht die Zonen energieeffizient über die Play Services.

### 🔍 Erkundungsmodus
Du stehst vor einem Baum und fragst dich: *„Was ist das da für ein Baum?"* Aktiviere den Erkundungsmodus (das Lupen-Icon unten rechts auf der Karte) und Baumradar zeigt dir **alle Bäume im Umkreis von 100 Metern** an – unabhängig von deinem Allergie-Profil. Jeder Marker auf der Karte zeigt dir den deutschen Gattungsnamen und, falls bekannt, die spezifische Art.

### 🧭 AR-Richtungsanzeige (Kompass-Pfeile)
Auf dem Karten-Bildschirm blendet Baumradar transparente Pfeile und Entfernungsangaben ein. Diese zeigen dir in Echtzeit die Richtung und Distanz zu den nächstgelegenen markierten Bäumen (die nächsten 15). Die Pfeile reagieren auf deinen Kompass (Gyroskop) und drehen sich mit dir mit, sodass du immer weißt, in welcher Himmelsrichtung ein Baum steht – auch wenn er sich außerhalb des sichtbaren Kartenausschnitts befindet.

### 🗺️ Allergiefreies Routing
Klappe die „Route planen"-Karte oben auf dem Bildschirm auf. Gib Start- und Zieladresse ein und wähle dein Fortbewegungsmittel (Zu Fuß, Fahrrad, Auto). Baumradar:
1. Löst die Adressen über den **Nominatim Geocoder** (OpenStreetMap) in Koordinaten auf.
2. Fragt beim öffentlichen **OSRM Routing Server** bis zu 3 Routen-Alternativen an.
3. Lädt alle Geofence-Zonen der Baumarten, die du im Allergie-Profil als "Umfahren" markiert hast, aus der lokalen Datenbank.
4. Prüft für jede Routen-Alternative, wie viele dieser Zonen geschnitten werden (siehe [Kollisionserkennung](docs/architecture/06_collision_activity.png)).
5. Sortiert die Routen: die allergenfreie Route wird zuerst angezeigt und als **"Allergiefrei 🟢"** markiert.

Die berechnete Route kann per GPX-Export geteilt werden, z. B. an eine Navigations-App.

### 🏙️ Multi-City Support
Unterstützte Städte: **Wien, Graz, Innsbruck, Linz** (Österreich), **Berlin, Hamburg, Freiburg, Dortmund** (Deutschland), **Zürich, Basel** (Schweiz). Beim ersten Start wählt man mindestens eine Stadt aus. Wenn man sich später in eine neue Stadt bewegt, schlägt die App automatisch vor, die lokalen Baumdaten herunterzuladen.

### 📴 Offline First
Die App lädt für jede Stadt eine komprimierte, aufbereitete SQLite-Datenbank herunter. Sobald geladen, funktionieren Kartenanzeige, Erkundungsmodus und Hintergrund-Warnungen **komplett ohne Internetverbindung**. Nur für das Routing (Routenberechnung über OSRM) wird kurzzeitig eine Verbindung benötigt.

### 🔐 Open Data & Zero Trust
Die Daten werden vom Backend verarbeitet und kryptografisch mit **Ed25519** signiert. Bevor die App eine heruntergeladene Datenbank verwendet, prüft sie die Signatur gegen einen fest in der App eingebetteten Public Key. Erst wenn die Prüfung erfolgreich ist, werden die Daten importiert. Dadurch kann jeder sicherstellen, dass die Daten authentisch und unverfälscht sind.

---

## 🚀 Installation (Android App)

### APK-Download (empfohlen)
1. Lade dir die aktuellste `Baumradar.apk` aus dem Repository (Releases-Tab) herunter.
2. Erlaube auf deinem Smartphone die Installation aus „Unbekannten Quellen" (wird in der Regel beim Öffnen automatisch abgefragt).
3. Öffne die APK und folge den Anweisungen.
4. Beim ersten Start: Wähle mindestens eine Stadt aus und lade deren Daten herunter.
5. Erteile die Berechtigungen für Standort (inkl. Hintergrund-Standort für die Geofence-Warnungen) und Benachrichtigungen.

### Selber kompilieren
```bash
git clone https://github.com/matthili/BaumRadar.git
cd BaumRadar
./gradlew assembleDebug
# Die APK findest du unter app/build/outputs/apk/debug/
```
Voraussetzungen: Android Studio (aktuelle Version), JDK 17, Android SDK 34.

---

## 🎮 Bedienung der App

### Ersteinrichtung
Beim allerersten Start erscheint ein **Städte-Assistent**. Hier wählst du per Schalter die Städte aus, deren Baumdaten du herunterladen möchtest. Die App zeigt einen Lade-Fortschritt inkl. Signatur-Verifizierung an. Danach tippst du auf „Weiter" und landest auf dem Hauptbildschirm.

### Hauptbildschirm (Tabs)
Die App hat am unteren Rand eine Tab-Leiste mit drei Bereichen:

1. **Karte (🗺️):** Der Hauptbereich. Hier siehst du die OpenStreetMap-Karte mit deinem Standort (blauer Punkt), den markierten Allergie-Bäumen (gelbe Pins), den Geofence-Zonen (rote Kreise) und ggf. berechneten Routen (blaue Linie). Unten rechts gibt es drei Buttons:
   - 📍 **Zentrieren**: Springt zurück zu deinem aktuellen Standort.
   - ⚠️ **Hotspots**: Blendet alle Geofence-Zonen der von dir ausgewählten Allergene im 2-km-Radius ein.
   - 🔍 **Erkundungsmodus**: Zeigt alle Bäume (jeder Art) im Umkreis von 100 m an.

2. **Allergie-Profil (👤):** Hier verwaltest du deine Allergien. Du siehst eine durchsuchbare, nach Gattung gruppierte Liste aller in der Datenbank vorhandenen Baumarten. Für jede Art gibt es zwei Checkboxen:
   - **„Warnung ⚠️"**: Aktiviert die Hintergrund-Benachrichtigung per Geofence für diese Art.
   - **„Umfahren 🚫"**: Berücksichtigt diese Art beim allergiefreien Routing.
   
   Über die Tri-State-Checkbox am Gruppen-Header (z. B. „Ahorn") kannst du eine gesamte Gattung mit einem Klick an- oder abwählen.

3. **Städte (🏙️):** Hier verwaltest du die heruntergeladenen Städte. Du kannst weitere Städte herunterladen, bestehende löschen, oder per Ortssymbol direkt zur Karten-Position einer Stadt springen.

### Langes Drücken auf die Karte
Ein langer Druck auf eine beliebige Stelle der Karte öffnet ein Kontextmenü:
- **Virtueller Standort setzen**: Für Tests oder zum Vorausplanen – die App verhält sich, als wärst du an diesem Punkt.
- **Route HIER starten**: Setzt den Startpunkt für eine Route.
- **Route HIER beenden**: Setzt den Endpunkt und berechnet die Route.

---

## 📖 Technische Dokumentation

Baumradar besteht aus zwei Hauptteilen und einer offenen Datenstruktur. Hier findest du detaillierte Dokumentationen zu den einzelnen Bereichen:

1. **[Android App Architektur](docs/app_architecture.md)**: Einblicke in die Kotlin-App, Jetpack Compose UI, Room-Datenbanken, das Routing-System und die Hintergrund-Geofence-Benachrichtigungen.
2. **[Backend / Data-Processor](docs/backend_architecture.md)**: Wie das Java-Backend Open Data aus verschiedenen Städten einliest, übersetzt, clustert, in Chunks splittet und signiert.
3. **[Datenstruktur & Third-Party Nutzung](docs/data_structure.md)**: Wie du als externer Entwickler die offenen, verifizierten Baumradar-Daten für deine eigene App (z.B. iOS, Web) nutzen kannst – mit Code-Beispielen.

## 📜 Lizenz
Dieses Projekt ist unter der **MIT License** veröffentlicht. Siehe [LICENSE](LICENSE) für weitere Details.

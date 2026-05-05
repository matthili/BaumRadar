# Android App Architektur

Die Baumradar Android App ist eine native Kotlin-Anwendung auf Basis von **Jetpack Compose** (Material 3) und **Room**. Minimale Android Version: **10 (API 29)**, Ziel-SDK: **34**. Sie ist darauf ausgelegt, auch hunderttausende Bäume effizient darzustellen und offline zugänglich zu machen.

---

## Paketstruktur der App

```
at.mafue.baumradar.app
├── MainActivity.kt              # Einstiegspunkt, Navigation (Wizard → Main)
├── data/
│   ├── AppDatabase.kt           # Room Database Definition
│   ├── TreeEntity.kt            # Tabelle "trees" (id, city_id, lat, lon, genus_de, genus_en, species_de, species_en)
│   ├── GeofenceEntity.kt        # Tabelle "geofences" (id, lat, lon, radius, count, genus_de)
│   ├── RouteHistoryEntity.kt    # Tabelle "route_history" (start, end, Zeitstempel)
│   ├── TreeDao.kt               # Room DAO (Bounding-Box-Queries, Geofence-Lookups)
│   ├── HistoryDao.kt            # Room DAO für Routen-Historie
│   ├── AllergyDataStore.kt      # Jetpack DataStore: selectedTrees & warnTrees Sets
│   ├── CityManager.kt           # Katalog-Download, Chunk-Zusammenführung, Signatur-Prüfung, DB-Merge
│   └── CityCatalogEntry.kt      # Datenklasse: id, name, country, dbUrl, dbUrlChunks, sigUrl, boundingBox
├── security/
│   └── SignatureVerifier.kt     # Ed25519 Signaturprüfung (BouncyCastle)
├── routing/
│   ├── OsrmRoutingClient.kt    # HTTP-Anfrage an OSRM, Parsing, integrierte Kollisionserkennung
│   ├── RouteCollisionDetector.kt # Punkt-zu-Linie-Distanz-Berechnung (equirectangulare Projektion)
│   ├── NominatimGeocoder.kt     # Adressauflösung via OpenStreetMap Nominatim
│   ├── GpxGenerator.kt         # GPX-Export & Share-Intent
│   └── RouteResult.kt          # Datenklasse (Polyline, Dauer, Distanz, collisionCount)
├── ui/
│   ├── MapArScreen.kt           # Karten-Composable (OSMDroid), AR-Pfeile, Routing-UI, Long-Press-Dialog
│   ├── MapViewModel.kt          # Zustand: Location, Trees, Routes, Geofences, Erkundungsmodus
│   ├── ArNavigationManager.kt   # GPS-Tracking, Kompass (Magnetometer), Haversine, Bearing
│   ├── ProfileScreen.kt         # Allergie-Profil UI (durchsuchbare Baumliste mit Tri-State)
│   ├── ProfileViewModel.kt     # Daten-Sanitization, Gattungs-Gruppierung, Geofence-Updates
│   ├── CitySelectionScreen.kt  # Stadt-Wizard und Stadt-Verwaltung
│   ├── CitySelectionViewModel.kt # Katalog-Laden, Download-Steuerung
│   ├── MainScreen.kt           # Tab-Navigation (Karte, Profil, Städte)
│   └── TaxonomyUtils.kt        # Trivialname-Extraktion aus Baumlisten
└── background/
    ├── GeofenceManager.kt       # Registriert 99 nächste Geofences + 2km-Update-Zone bei Android
    └── GeofenceBroadcastReceiver.kt # Empfängt Geofence-Events → Push-Benachrichtigung
```

---

## 1. City Manager & Daten-Synchronisation

Der `CityManager` ist das Herzstück der Datenversorgung. Er lädt einen `catalog.json` von GitHub (cache-busted mit Timestamp), der alle verfügbaren Städte mit ihren Download-URLs auflistet.

**Ablauf beim Download einer Stadt:**
1. Falls `dbUrlChunks` vorhanden (z.B. Berlin > 50MB): Lade alle `.001`, `.002`, ... Chunks herunter und hänge sie binär aneinander zu einer `.db.gz` Datei.
2. Falls keine Chunks: Lade die einzelne `.db.gz` Datei herunter.
3. Lade die `.db.gz.sig` Signaturdatei herunter.
4. **Signaturprüfung**: `SignatureVerifier.verifyFile()` prüft die `.db.gz` gegen die `.sig` mit dem hardcodierten Ed25519 Public Key (`MCowBQYDK2VwAyEA...`). Bei Fehlschlag: Dateien löschen, Abbruch.
5. Entpacke die GZIP-Datei zu einer rohen `.db` SQLite-Datei.
6. `ATTACH DATABASE` auf die Room-Datenbank → `INSERT INTO trees SELECT * FROM new_city_db.trees` → `DETACH`.
7. Markiere die Stadt als heruntergeladen in den SharedPreferences (`city_dn_<id>`).

![Synchronisations-Ablauf](architecture/03_app_sync.png)

**Automatische Stadt-Erkennung:** Im `MapViewModel` überwacht ein `effectiveLocation`-Collector alle 5 Sekunden die Position. Befindet sich der Nutzer in der BoundingBox einer noch nicht heruntergeladenen Stadt, erscheint ein Dialog: *"Neue Region entdeckt! Möchtest du die Daten für X laden?"*

---

## 2. Karten-Darstellung & Erkundungsmodus

Die Karte basiert auf **OSMDroid** (OpenStreetMap), eingebettet in Compose via `AndroidView`. Der `MapViewModel` steuert den gesamten Zustand:

- **`nearbyAllergicTrees`**: Ein reaktiver `StateFlow`, der die Kombination aus `effectiveLocation` (aktualisiert alle 2s), `selectedTreesFlow`, `warnTreesFlow` und `isExplorationMode` auswertert. Ergebnis: Eine gefilterte, nach Distanz geprüfte Liste von `TreeEntity`-Objekten.
  - Im **Normalmodus**: Zeigt nur Bäume an, die in `selectedTrees` oder `warnTrees` enthalten sind, im Umkreis von 500 m.
  - Im **Erkundungsmodus**: Zeigt *alle* Bäume im Umkreis von 100 m, unabhängig vom Allergie-Profil.
- **`effectiveLocation`**: Kombiniert den echten GPS-Standort mit einem optionalen `virtualLocation` (für Tests per Long-Press).
- **Bounding-Box-Query**: Die Room-Datenbank wird via `getTreesInBoundingBox()` effizient abgefragt – danach erfolgt ein Haversine-Feinfilter in Kotlin.

---

## 3. AR-Kompass-Overlay

Die Klasse `ArNavigationManager` nutzt den Android `SensorManager` (Magnetometer + Accelerometer) für die Kompassrichtung. `ArOverlay` (ein `Canvas`-Composable) zeichnet:

- **Rotierte Dreiecks-Pfeile** für jede der 15 nächsten Bäume, die sich außerhalb des aktuellen Field-of-View (60° gesamt) befinden.
- Jeder Pfeil zeigt die Entfernung in Metern an.
- Die Berechnung nutzt `calculateBearing()` (Bearing zwischen zwei Geopunkten) und `calculateDistance()` (Haversine-Formel).

---

## 4. Routing & Kollisionserkennung

![Routing & Kollision Sequenz](architecture/04_routing_collision.png)

Der Routing-Ablauf in `MapViewModel.calculateGeocodedRoute()`:

1. **Geocoding**: Adressen → Koordinaten via `NominatimGeocoder`.
2. **Route in Historie speichern** (bis zu 10 Einträge, ältere werden gelöscht).
3. **Allergien laden**: `AllergyDataStore.selectedTreesFlow.first()`.
4. **Geofences laden**: `TreeDao.getGeofencesInBoundingBox()` mit der BoundingBox der Route (+0.05° Puffer) und den aktiven Allergenen.
5. **Route berechnen**: `OsrmRoutingClient.getRoute()` sendet eine HTTP-Anfrage an `routing.openstreetmap.de` (Profil: foot/bike/car). Falls Allergien aktiv → `alternatives=3`.
6. **Kollisionen zählen** (intern im `OsrmRoutingClient`): Für jede Routenalternative ruft der Client `RouteCollisionDetector.countCollisions()` auf.
7. **Sortierung**: Routen werden nach `collisionCount * 100000 + durationSec` sortiert – so steht die allergenfreie Route immer oben.

![Kollisionsalgorithmus (Aktivitätsdiagramm)](architecture/06_collision_activity.png)

**Der Algorithmus im Detail (`RouteCollisionDetector`):**
- Für jeden Geofence und jedes Routen-Segment (Punkt A → Punkt B) wird die kürzeste Distanz vom Geofence-Zentrum zum Liniensegment berechnet.
- Die Berechnung nutzt eine **equirectangulare Projektion** (Umrechnung in ein lokales metrisches Koordinatensystem) und eine **Vektorprojektion** mit Clamping auf das Segment `[0, 1]`.
- Der effektive Warn-Radius ist: `fence.radius + 60 Meter` (Pollen-Toleranz).
- Zusätzlich wird der letzte Routenpunkt per Haversine-Distanz gegen jeden Geofence geprüft.

---

## 5. Hintergrund-Geofence-Benachrichtigungen

Dieses Feature nutzt den **Google Play Services `GeofencingClient`** – eine akkuschonende Lösung, die keine permanent laufende App erfordert.

**Architektur:**
- **`GeofenceManager`**: Wird aufgerufen, wenn der Nutzer im Profil eine "Warn-Baumart" an-/abwählt. Er:
  1. Entfernt alle bisherigen Geofences.
  2. Fragt die 99 nächstgelegenen Geofences (der Warn-Baumarten) aus der lokalen DB ab.
  3. Registriert diese 99 Zonen bei Android als `GEOFENCE_TRANSITION_ENTER` Geofences.
  4. Registriert einen 100. "Update-Zone"-Geofence (2 km Radius) als `GEOFENCE_TRANSITION_EXIT`.
- **`GeofenceBroadcastReceiver`**: Ein `BroadcastReceiver`, der im Manifest registriert ist und vom System aufgeweckt wird:
  - Bei **ENTER** eines Baum-Geofences: Zeigt eine Push-Benachrichtigung (*"Allergie Warnung: Du befindest dich in der Nähe eines potenziell allergenen Baumes (Birke)."*)
  - Bei **EXIT** der Update-Zone: Ruft erneut `GeofenceManager.updateGeofences()` auf, um die 99 nächsten Bäume am neuen Standort zu registrieren.

**Berechtigungen:**
- `ACCESS_BACKGROUND_LOCATION` (Android 10+): Erlaubt Standort-Prüfung im Hintergrund.
- `POST_NOTIFICATIONS` (Android 13+): Erlaubt Push-Benachrichtigungen.

---

## Abhängigkeiten

| Bibliothek | Zweck |
|---|---|
| Jetpack Compose (BOM 2024.02) | UI-Framework |
| Room 2.6.1 | Lokale SQLite-Datenbank |
| Jetpack DataStore | Persistente Allergie-Einstellungen |
| Google Play Services Location 21.1.0 | GPS, FusedLocationProvider, GeofencingClient |
| OSMDroid 6.1.18 | OpenStreetMap Karten-Rendering |
| OkHttp 4.12.0 | Netzwerk (DB-Downloads, Routing, Geocoding) |
| BouncyCastle 1.77 | Ed25519 Signatur-Verifizierung |

[Zurück zur Startseite](../README.md) | [English Version](app_architecture_en.md)

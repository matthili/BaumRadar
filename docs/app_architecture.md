# Android App Architektur

Die Baumradar Android App ist eine native Kotlin-Anwendung, die auf moderner Jetpack Compose UI aufbaut. Sie ist darauf ausgelegt, auch große Datenmengen (wie hunderttausende Bäume) effizient darzustellen und offline zugänglich zu machen.

## Kernkomponenten

1. **City Manager & Daten-Synchronisation**
   Die App zieht sich nicht jeden einzelnen Baum über eine REST-API. Stattdessen lädt sie komplette, vorgefertigte SQLite-Datenbanken für ausgewählte Städte herunter.
   - Der `CityManager` liest einen `catalog.json` (bereitgestellt vom Backend).
   - Er lädt komprimierte `.db.gz` Dateien herunter (bei großen Städten in Chunks wie `.001`, `.002`).
   - Bevor die Daten in die App-Datenbank (`Room`) übernommen werden, verifiziert der `SignatureVerifier` die Daten kryptografisch mit einem Hardcoded Public Key.
   
   ![Synchronisations-Ablauf](architecture/03_app_sync.png)

2. **Routing & Kollisionserkennung**
   Das Herzstück für Allergiker ist das Routing-Feature. Die App nutzt den `OsrmRoutingClient` um Wegstrecken zu berechnen.
   - Danach prüft der `RouteCollisionDetector`, ob diese Wegstrecke mit Geofence-Zonen von allergenen Bäumen kollidiert.
   - Es wird ein mathematischer Linien-Schnittpunkt-Test auf Basis des Zonen-Radius (plus Toleranz) angewandt.
   
   ![Routing & Kollision Algorithmus](architecture/06_collision_activity.png)
   ![Routing Architektur](architecture/04_routing_collision.png)

3. **Room Database & Map Rendering**
   Die importierten SQLite-Bäume werden in einer lokalen Room-Datenbank gespeichert. Map-Marker werden dynamisch und geclustert basierend auf dem aktuellen Kartenausschnitt aus der lokalen Datenbank geladen, um den Arbeitsspeicher zu schonen.

[Zurück zur Startseite](../README.md) | [English Version](app_architecture_en.md)

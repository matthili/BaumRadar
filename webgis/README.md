<p align="center">
  <img src="../assets/favicons/web-app-manifest-192x192.png" alt="BaumRadar WebGIS" width="120"/>
</p>

# BaumRadar WebGIS — Tech-Demo

*(For the English documentation, see [README_en.md](README_en.md))*

Ein lokal betreibbares Web-GIS auf Basis von **OGC-Standards**, das die signierten
BaumRadar-Datenbestände (19 Städte, ~2,6 Mio Bäume) konsumiert — als eigenständige
Ergänzung zur Android-App. Kein Server-Betrieb nötig: alles läuft in Containern
auf dem eigenen Rechner.

```
┌─────────┐     ┌──────────────┐    ┌────────────┐    ┌─────────────────┐
│ loader  │───▶│   PostGIS     │◀──│ GeoServer  │◀──│ Angular-Client  │
│ (Java 25│     │ trees        │    │ WMS 1.3.0  │    │ (OpenLayers)    │
│  Maven) │     │ allergy_zones│    │ WFS 2.0    │    └─────────────────┘
└────┬────┘     └──────────────┘    │ OGC API    │    ┌─────────────────┐
     │  catalog.json + *.db.gz      │ Features   │    │ GraphHopper     │
     └── GitHub Pages (signiert) ───└────────────┘    │ (Insel-Graph)   │
                                                      └─────────────────┘
```

## Schnellstart

Voraussetzung ist einzig **Docker** (Desktop oder Engine, mit Compose v2) —
kein Java, kein Node, kein Maven. Dann:

```powershell
cd webgis
.\start.cmd                              # Windows — Voll-Stack: Karte + Routing + Adresssuche
./start.sh                               # Linux/macOS
```

**Lokales Routing und lokale Adresssuche sind Standard** — dafür ist das Ganze ja da.
Empfehlenswert zum ersten Ausprobieren (kleiner, schneller):

```powershell
.\start.cmd -Cities zug,wien                       # nur bestimmte Städte (kleine Downloads)
.\start.cmd -Cities zug -NoRouting -NoGeocoding    # nur die Karte (kleinster Download)
.\start.cmd -Down                                  # alles stoppen (Daten-Volumes bleiben)
.\start.cmd -Purge                                 # ALLES entfernen (Container, Daten, Images)
```

Unter Linux/macOS heißen die Schalter `--cities`, `--no-routing`, `--no-geocoding`,
`--down`, `--purge`. `-Down`/`--down` stoppt nur (der nächste Start setzt auf den
gecachten Daten auf); `-Purge`/`--purge` baut alles zurück, bis nur noch das
Repository übrig ist.

Das Skript legt beim ersten Lauf die `.env` an — **mit zufällig generierten
Passwörtern** statt Demo-Zugangsdaten — baut die Container und startet den Stack.
Danach: Karte auf http://localhost:8082. Der `loader` lädt die gewählten Städte
aus dem GitHub-Pages-Katalog, prüft die **Ed25519-Signaturen**, importiert nach
PostGIS und provisioniert GeoServer per REST — idempotent, unveränderte Städte
(`dataVersion`) werden bei jedem weiteren Start übersprungen.

**Erstlauf-Datenmengen** (einmalig, danach in Volumes gecacht): Baumdaten je nach
`CITY_FILTER` (Zug ~2 MB … alle 19 ~600 MB) · die Adresssuche (Standard; abwählbar mit
`-NoGeocoding`) lädt die Geocoder-Häppchen der gewählten Städte (15–176 MB je Stadt) ·
das Routing (Standard; abwählbar mit `-NoRouting`) lädt die Länder-PBFs von
Geofabrik (**DE ~4 GB**, AT/CH je ~0,5 GB — nur die Länder der gewählten Städte;
fällt Geofabrik aus, weicht der Download automatisch auf Mirrors aus: GWDG bzw. osm.fr).
Der erste Start baut danach Routing-Graph und Such-Index — **einige Minuten Geduld**;
der Web-Client überbrückt die Adresssuche solange automatisch über photon.komoot.io.
Liegt das Repository vollständig lokal vor, nimmt die Adresssuche die Geocoder-Dateien
direkt aus `docs/data/` — ganz ohne GitHub-Download.

**Plattenbedarf (Voll-Stack, alle 19 Städte):** dauerhaft ~50–60 GB Docker-Daten
(Länder-PBFs ~6 GB, PostGIS ~3–4 GB, Photon-Suchindex ~15–25 GB, Routing-Graph,
Images/Build-Cache ~8–10 GB) — **in der Spitze während des ersten Photon-Imports
bis ~75 GB**, weil der gemergte Rohdump (>20 GB) neben dem entstehenden Index liegt.
Mit `-Cities zug` zum Ausprobieren bleibt alles zusammen unter ~10 GB.

**Dauerbetrieb:** Der Stack ist auf unbeaufsichtigten Langzeitbetrieb ausgelegt — alle
Laufzeitdaten sind begrenzt oder räumen sich selbst: Container-Logs sind per Compose
auf 10 MB × 3 je Dienst gedeckelt (wichtig beim öffentlich erreichbaren Web-Client),
Photon-Arbeitsdateien und Stadt-Extrakte werden nach Gebrauch gelöscht, Neu-Importe
ersetzen ihre Vorgänger, und PostgreSQL erledigt WAL-Recycling/Autovacuum selbst.
Einzige gelegentliche Host-Pflege: Wiederholte Update-Builds füttern den **globalen
Docker-Build-Cache** (gemessen: nach Wochen >20 GB). `docker system df` zeigt den
Stand; `docker builder prune -f --filter until=168h` räumt nur Cache-Einträge, die
älter als 7 Tage sind (kostet nichts außer Rebuild-Zeit). Auf Engines mit klassischem
Image-Store (z. B. Debian-Server) hinterlassen Rebuilds zusätzlich ungetaggte
Alt-Images — dort hilft `docker image prune -f` (entfernt nur Ungetaggtes); neuere
Docker-Desktop-Versionen (containerd-Store) räumen diese selbst.

**Woran erkenne ich, dass noch geladen wird?** Solange Module fehlen, rotiert im
Web-Client ein Ring um das BaumRadar-Logo; Hover (oder Fokus) darüber öffnet ein
Overlay mit dem echten Stand pro Modul — dieselben Meldungen wie `docker logs`
(z. B. „schneidet Stadt-Ausschnitte 3–4/12"). Meldet sich eine laufende Phase
15 Minuten nicht, markiert das Overlay sie als „hängt evtl.".

## Sicherheit (auch für Heim-/Schulnetz)

- **Zufällige Zugangsdaten:** `start.cmd`/`start.sh` ersetzt die Demo-Passwörter
  beim Anlegen der `.env` durch Zufallswerte (einsehbar in `webgis/.env`, die Datei
  ist git-ignoriert). Achtung: das PostGIS-Passwort wird beim ersten Initialisieren
  des Datenbank-Volumes eingebrannt — späteres Ändern braucht `docker compose down -v`.
- **Nur localhost:** GeoServer-Admin, PostGIS, GraphHopper und Photon sind
  standardmäßig an `127.0.0.1` gebunden — im LAN nicht erreichbar. Nur der
  Web-Client (Port 8082) ist im Netz sichtbar; er reicht ausschließlich lesende
  Dienste same-origin durch (kein Admin-Zugang, kein WFS-T).
  Bewusster LAN-Zugriff auf die internen Dienste: `BIND_HOST=0.0.0.0` in `.env`.

## Dienste

| Dienst | URL | Zugang |
|---|---|---|
| **Web-Client** (Angular + OpenLayers) | http://localhost:8082 | — (einziger LAN-sichtbarer Port) |
| GeoServer Web-UI | http://localhost:8081/geoserver | siehe `.env` (nur localhost) |
| WMS 1.3.0 | http://localhost:8081/geoserver/baumradar/wms | — |
| WFS 2.0 | http://localhost:8081/geoserver/baumradar/wfs | — |
| OGC API Features | http://localhost:8081/geoserver/ogc/features/v1 | — |
| PostGIS | localhost:5433 (Container-intern 5432) | siehe `.env` (nur localhost) |
| GraphHopper (Profil `routing`) | http://localhost:8989 | — (nur localhost; Client nutzt `/graphhopper/`) |
| Photon-Geocoder (Profil `geocoding`) | http://localhost:2322 | — (nur localhost; Client nutzt `/photon/`) |

Beispiel-Requests:

```
# Karte (WMS): Bäume + Zonen um den Wiener Hauptbahnhof
http://localhost:8081/geoserver/baumradar/wms?service=WMS&version=1.3.0&request=GetMap&layers=baumradar:allergy_zones,baumradar:trees&crs=EPSG:4326&bbox=48.17,16.36,48.20,16.40&width=1024&height=768&format=image/png

# Features (WFS): alle Birken in einem Ausschnitt als GeoJSON
http://localhost:8081/geoserver/baumradar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=baumradar:trees&outputFormat=application/json&count=100&cql_filter=genus_de='Birke'
```

## Warum diese Architektur?

- **PostGIS statt GeoPackage als Laufzeit-Store:** Die Allergiezonen liegen als
  Mittelpunkt + Radius (Meter) vor; `ST_Buffer(…::geography, radius)` macht daraus
  geodätisch korrekte Kreis-Polygone. Dazu GiST-Indizes und robustes
  Concurrent-Serving für 2,6 Mio Punkte. Die OGC-Konformität nach außen liefert
  GeoServer (WMS/WFS/OGC API Features) — der Store dahinter ist Implementierungsdetail.
- **Maven statt Gradle — bewusst:** Der Android-Teil des Repos ist durch AGP an
  Gradle gebunden. Diese Server-Erweiterung ist absichtlich als eigenständiges
  Maven-Projekt getrennt: eigener Lifecycle, keine Kopplung an den App-Build,
  und der Loader nutzt als Tech-Demo aktuelle Java-25-Features (Virtual Threads,
  Records, `java.net.http`), ohne Rücksicht auf Android-Toolchains.
- **Kein BouncyCastle:** Ed25519 ist seit JDK 15 Teil der Standard-JCE — die
  Signaturprüfung des Loaders kommt ohne Krypto-Dependency aus. (Die App bündelt
  BouncyCastle nur, damit die Prüfung auch auf **älteren Android-Geräten** läuft:
  sie unterstützt Geräte ab Android 10, und dort bringt der System-Krypto-Provider
  noch kein Ed25519 mit — das Angebot der JCA hängt auf Android vom Geräte-OS ab,
  nicht vom SDK, gegen das die App gebaut ist.)
- **Ein GraphHopper mit „Insel-Graph"** (Phase 4): Die 19 Stadt-Ausschnitte werden
  per osmium zu einem PBF zusammengeführt. Routing funktioniert innerhalb jeder
  Stadt; zwischen Städten gibt es bewusst keine Verbindung. Ein Bauplan-Marker macht
  den Neubau idempotent: Solange Städte/BBoxen/Rand unverändert sind, überspringt
  jeder weitere Start Extraktion + Graph-Neubau (frische OSM-Daten erzwingen:
  `docker volume rm baumradar-webgis_routingdata`). Allergiezonen werden
  pro Request als Custom-Model-Areas gemieden (weich — Start *in* einer Zone
  funktioniert immer); die Meidungs-Stärke ist im Client wählbar
  („lieber queren als 5/10/20/50/100-facher Umweg", Standard 20-fach). Der Client
  zieht nur die Zonen im **Schlauch um die Route** heran (je Gattung eigenes Budget),
  zählt selbst die tatsächlichen Querungen (mit Zonen-Metern) und zeigt die
  **Direktroute** als gestrichelten Vergleich; Gattungs-/Faktor-Wechsel rechnen
  automatisch neu (Zonen-Cache je Strecke).

## Konfiguration (`.env`)

| Variable | Default | Bedeutung |
|---|---|---|
| `PG_DB` / `PG_USER` / `PG_PASSWORD` | `baumradar` (Skript: zufällig) | PostGIS-Zugang |
| `PG_PORT` | `5433` | Host-Port für PostGIS (Container: 5432) |
| `GEOSERVER_PORT` | `8081` | Host-Port für GeoServer |
| `GEOSERVER_USER` / `GEOSERVER_PASSWORD` | `admin` / Skript: zufällig | GeoServer-Admin |
| `GEOSERVER_VERSION` | `2.28.0` | Image-Tag; mindestens 2.27 (OGC API Features ist erst ab dort stabile Extension) |
| `BIND_HOST` | `127.0.0.1` | Bind-Adresse der internen Dienste (`0.0.0.0` = LAN) |
| `WEB_PORT` | `8082` | Host-Port des Web-Clients |
| `CITY_FILTER` | *(leer = alle)* | Kommagetrennte Stadt-IDs, z. B. `wien,linz` — gilt für Loader, Routing **und** Geocoder |
| `CATALOG_URL` | GitHub Pages | Quelle des Stadtkatalogs |
| `GH_VERSION` / `GRAPHHOPPER_PORT` / `GH_JAVA_OPTS` | `10.0` / `8989` / `-Xmx2g` | GraphHopper (Profil `routing`) |
| `BBOX_MARGIN_DEG` | `0.03` | Rand um Stadt-BBoxen beim Insel-Graph |
| `PHOTON_VERSION` / `PHOTON_PORT` | `1.2.1` / `2322` | Photon-Geocoder (Profil `geocoding`) |
| `PHOTON_IMPORT_JAVA_OPTS` | `-Xmx4g` | Heap für den **einmaligen** Index-Import — bei allen 19 Städten (~18 GB Rohdaten) ggf. auf `-Xmx6g`/`-Xmx8g` erhöhen, wenn der Import abstürzt |
| `PHOTON_JAVA_OPTS` | `-Xmx1g` | Heap des laufenden Suchdienstes |

## Entwicklung

**Loader** — Maven muss nicht lokal installiert sein, Tests laufen im Container:

```powershell
docker run --rm -v "${PWD}\loader:/src" -w /src -v baumradar-m2:/root/.m2 `
  maven:3.9-eclipse-temurin-25 mvn test
```

**Web-Client** — Angular 22 (Standalone + Signals, zoneless), OpenLayers direkt ohne
Wrapper-Bibliothek; die Karte entsteht in `runOutsideAngular` (unter zoneless ohnehin
entschärft, als Muster dokumentiert). Für lokales `npm start` braucht die Angular-CLI
**Node ≥ 24.15** — alternativ laufen Build und Tests im Container:

```powershell
# Dev-Server lokal (Node >= 24.15): proxied /geoserver -> localhost:8081
cd client; npm install; npm start

# Unit-Tests (vitest) im Container:
docker run --rm -v "${PWD}\client:/src:ro" node:24-alpine `
  sh -c "cp -r /src /app && cd /app && npm install --silent && npx ng test --watch=false"
```

Integrationstest gegen echtes PostGIS (Testcontainers, braucht lokalen Docker-Socket,
daher am besten mit lokal installiertem Maven/JDK 25):

```powershell
mvn test -Pit
```

## Phasen-Stand

- [x] Phase 0 – Gerüst (compose, README)
- [x] Phase 1 – Loader (Download → Verify → PostGIS → GeoServer-Provisionierung)
- [x] Phase 2 – GeoServer-Dienste end-to-end verifiziert (WMS-GetMap, WFS-GetFeature+CQL, OGC API Features)
- [x] Phase 3 – Angular-Client (OpenLayers, Signals, GetFeatureInfo-Popup, GPX-Drop; Filter-Suche über Gattungs- **und** Artnamen, deutsch wie botanisch — Auswahl bleibt gattungsweit, passend zu den genus-geclusterten Zonen; Liste sortierbar nach Anzahl/Name; Stadt-Auswahl scopt Zahlen + Karte)
- [x] Phase 4 – GraphHopper-Routing (Insel-Graph, Schlauch-Zonen-Vermeidung per Custom-Model mit Konvergenz + Querungs-Zähler + Direktrouten-Vergleich, Start/Ziel per Klick **oder** Adresssuche)
- [x] Geocoding – Photon aus den pro-Stadt-Häppchen des Katalogs (lokal, `CITY_FILTER`-bewusst; Fallback photon.komoot.io)
- [x] Phase 5 – Doku: [Architektur (DE)](../docs/webgis_architecture.md) / [EN](../docs/webgis_architecture_en.md) inkl. Diagrammen + Lessons Learned, Verlinkung im Haupt-README

Die ausführliche Architektur-Dokumentation (Datenweg, Routing, Geocoding, Stolpersteine):
**[docs/webgis_architecture.md](../docs/webgis_architecture.md)** · [English version](../docs/webgis_architecture_en.md)

Den Stack **von Grund auf aufbauen** (was wo herunterladen, Verdrahtung, Verifikation je Phase):
**[docs/webgis_bootstrap.md](../docs/webgis_bootstrap.md)** · [English version](../docs/webgis_bootstrap_en.md)

Unbekannte Begriffe (PBF? Photon? CQL?) erklärt das **[Glossar](../docs/glossary.md)** ([EN](../docs/glossary_en.md)).

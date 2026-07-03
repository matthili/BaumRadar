# BaumRadar WebGIS — Tech-Demo

Ein lokal betreibbares Web-GIS auf Basis von **OGC-Standards**, das die signierten
BaumRadar-Datenbestände (19 Städte, ~2,6 Mio Bäume) konsumiert — als eigenständige
Ergänzung zur Android-App. Kein Server-Betrieb nötig: alles läuft in Containern
auf dem eigenen Rechner.

```
┌─────────┐    ┌──────────────┐    ┌────────────┐    ┌─────────────────┐
│ loader  │───▶│   PostGIS    │◀───│ GeoServer  │◀───│ Angular-Client  │
│ (Java 25│    │ trees        │    │ WMS 1.3.0  │    │ (OpenLayers)    │
│  Maven) │    │ allergy_zones│    │ WFS 2.0    │    └─────────────────┘
└────┬────┘    └──────────────┘    │ OGC API    │    ┌─────────────────┐
     │  catalog.json + *.db.gz     │ Features   │    │ GraphHopper     │
     └── GitHub Pages (signiert) ──└────────────┘    │ (Insel-Graph)   │
                                                     └─────────────────┘
```

## Schnellstart

```powershell
cd webgis
copy .env.example .env      # Zugangsdaten anpassen (Demo-Defaults funktionieren)
docker compose up
```

Der `loader` lädt beim ersten Start alle Städte aus dem GitHub-Pages-Katalog,
prüft die **Ed25519-Signaturen**, importiert nach PostGIS und provisioniert
GeoServer (Workspace, Layer, Styles) per REST. Wiederholte Starts sind
idempotent: pro Stadt wird die `dataVersion` verglichen, unveränderte Städte
werden übersprungen.

Nur bestimmte Städte laden (z. B. zum Ausprobieren):

```powershell
$env:CITY_FILTER = "wien,graz"; docker compose up
```

## Dienste

| Dienst | URL | Zugang |
|---|---|---|
| GeoServer Web-UI | http://localhost:8081/geoserver | admin / geoserver (via `.env`) |
| WMS 1.3.0 | http://localhost:8081/geoserver/baumradar/wms | — |
| WFS 2.0 | http://localhost:8081/geoserver/baumradar/wfs | — |
| OGC API Features | http://localhost:8081/geoserver/ogc/features/v1 | — |
| PostGIS | localhost:5433 (Container-intern 5432) | via `.env` |

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
  Signaturprüfung des Loaders kommt ohne Krypto-Dependency aus (die App braucht
  BouncyCastle nur wegen alter Android-API-Level).
- **Ein GraphHopper mit „Insel-Graph"** (Phase 4): Die 19 Stadt-Ausschnitte werden
  per osmium zu einem PBF zusammengeführt. Routing funktioniert innerhalb jeder
  Stadt; zwischen Städten gibt es bewusst keine Verbindung. Allergiezonen werden
  pro Request als Custom-Model-Areas gemieden (weich — Start *in* einer Zone
  funktioniert trotzdem).

## Konfiguration (`.env`)

| Variable | Default | Bedeutung |
|---|---|---|
| `PG_DB` / `PG_USER` / `PG_PASSWORD` | `baumradar` | PostGIS-Zugang |
| `PG_PORT` | `5433` | Host-Port für PostGIS (Container: 5432) |
| `GEOSERVER_PORT` | `8081` | Host-Port für GeoServer |
| `GEOSERVER_USER` / `GEOSERVER_PASSWORD` | `admin` / `geoserver` | GeoServer-Admin |
| `GEOSERVER_VERSION` | `2.28.0` | Image-Tag; mindestens 2.27 (OGC API Features ist erst ab dort stabile Extension) |
| `CITY_FILTER` | *(leer = alle)* | Kommagetrennte Stadt-IDs, z. B. `wien,linz` |
| `CATALOG_URL` | GitHub Pages | Quelle des Stadtkatalogs |

## Entwicklung (Loader)

Maven muss nicht lokal installiert sein — Tests laufen im Container:

```powershell
docker run --rm -v "${PWD}\loader:/src" -w /src -v baumradar-m2:/root/.m2 `
  maven:3.9-eclipse-temurin-25 mvn test
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
- [ ] Phase 3 – Angular-Client (OpenLayers, Signals, `runOutsideAngular`)
- [ ] Phase 4 – GraphHopper-Routing (Insel-Graph, Zonen-Vermeidung)
- [ ] Phase 5 – Doku (EN), Architektur-Diagramm, Verlinkung im Haupt-README

# WebGIS von Grund auf aufbauen (From-Scratch-Anleitung)

> **Szenario:** Das Wissen ist da, aber der `webgis/`-Ordner ist weg. Diese Anleitung
> zeigt, *was* man *wo* herbekommt, *wie* die sieben Container verdrahtet werden und
> *wie* man nach jeder Phase prüft, dass sie wirklich läuft. Ergänzt die
> [Architektur-Doku](webgis_architecture.md) (das *Warum*) um das *Wie des Aufbaus*.
>
> English version: [webgis_bootstrap_en.md](webgis_bootstrap_en.md).

## Was man lokal wirklich braucht

**Nur Docker (Desktop oder Engine) mit Compose v2.** Kein Java, kein Node, kein Maven,
kein osmium auf dem Host — jeder Baustein bringt seine Werkzeuge im Container mit oder
lädt sie während des Builds. Einzige externe Abhängigkeit ist der **Datenkatalog**
(`catalog.json` + signierte `*.db.gz` + `geocoder_*.jsonl.gz`), den das *separate*
`data-processor`-Projekt erzeugt und auf GitHub Pages publiziert. Liegt das Repo lokal
vor (`docs/data/`), liest der Geocoder sogar direkt von dort.

## Die sieben Bausteine

| # | Container | Herkunft | Rolle |
|---|---|---|---|
| 1 | **postgis** | Fertig-Image `postgis/postgis:17-3.5` | Räumlicher Laufzeit-Store (`trees`, `allergy_zones`) |
| 2 | **geoserver** | Fertig-Image `docker.osgeo.org/geoserver:2.28.0` | OGC-Dienste (WMS 1.3.0, WFS 2.0, OGC API Features) auf PostGIS |
| 3 | **loader** | Selbstbau: `maven:3.9-eclipse-temurin-25` → `eclipse-temurin:25-jre` | Katalog → Signaturprüfung → PostGIS-Import → GeoServer-Provisionierung |
| 4 | **web** | Selbstbau: `node:24-alpine` → `nginx:1.27-alpine` | Angular/OpenLayers-UI + Same-Origin-Reverse-Proxy |
| 5 | **graph-builder** | Selbstbau: `debian:bookworm-slim` + osmium-tool/jq/curl | Einmal-Job: Länder-PBFs → `island.osm.pbf` |
| 6 | **graphhopper** | Selbstbau: `eclipse-temurin:21-jre` + GH-Web-JAR aus GitHub-Release | Routing-Engine (foot + bike, flexibler Modus) |
| 7 | **photon** | Selbstbau: `eclipse-temurin:21-jre` + Photon-JAR aus GitHub-Release | Geocoder (Adress-/Ortssuche), lokal |

**Zwei Images zieht man fix und fertig** (1, 2). **Fünf baut man selbst** — aber „selbst
bauen" heißt nur: Compose führt das Dockerfile aus, das seinerseits alles Nötige
herunterlädt (GraphHopper-JAR, Photon-JAR, Maven-Deps, npm-Pakete). Von Hand lädt man
nichts.

## Ordnerstruktur

```
webgis/
├─ docker-compose.yml        # die gesamte Verdrahtung (Netzwerk, Volumes, Env)
├─ .env.example              # Konfig-Vorlage (Ports, Passwörter, CITY_FILTER, Versionen)
├─ start.sh / start.cmd + start.ps1   # Ein-Befehl-Start (erzeugt .env mit Zufallspasswörtern)
├─ .gitattributes            # *.sh = LF erzwingen (CRLF bricht Container-Shebangs)
├─ loader/         Dockerfile · pom.xml · src/               (Java-Maven)
├─ client/         Dockerfile · package.json · nginx.conf · docker/10-stack-config.sh · src/  (Angular)
├─ graph-builder/  Dockerfile · build-island.sh
├─ graphhopper/    Dockerfile · config.yml · entrypoint.sh
└─ photon/         Dockerfile · entrypoint.sh
```

---

## Phase 0 — Gerüst

**Ziel:** Das Skelett steht, bevor eine Zeile Anwendungscode existiert.

1. `docker-compose.yml` mit `name: baumradar-webgis`, den sieben Services und **sechs
   Named Volumes** anlegen:
   ```yaml
   volumes:
     pgdata:        # PostGIS-Datenbank
     gsdata:        # GeoServer-Konfig
     osmcache:      # gecachte Länder-PBFs (groß, bleibt)
     routingdata:   # island.osm.pbf + GraphHopper-Graph-Cache
     photondata:    # Photon-Suchindex
     statusdata:    # Lade-Status-JSONs (Container schreiben, nginx serviert read-only)
   ```
   Das Netzwerk entsteht implizit (`baumradar-webgis_default`); **der Servicename ist
   der Hostname** — das ist die Grundlage der gesamten Verdrahtung.

2. `.env.example` mit den Stellschrauben:
   ```ini
   PG_DB=baumradar
   PG_USER=baumradar
   PG_PASSWORD=baumradar          # start.* ersetzt das durch einen Zufallswert
   PG_PORT=5433                   # Host-Port (Container: 5432)
   GEOSERVER_PORT=8081
   GEOSERVER_USER=admin
   GEOSERVER_PASSWORD=geoserver   # ebenfalls zufällig ersetzt
   GEOSERVER_VERSION=2.28.0
   BIND_HOST=127.0.0.1            # Härtung: interne Dienste nur lokal (0.0.0.0 = LAN)
   WEB_PORT=8082
   CITY_FILTER=                   # leer = alle Städte; z. B. "zug,wien"
   CATALOG_URL=https://raw.githubusercontent.com/<user>/<repo>/master/docs/data/catalog.json
   GH_VERSION=10.0
   GRAPHHOPPER_PORT=8989
   BBOX_MARGIN_DEG=0.03
   EXTRACT_BATCH=1                # Städte je osmium-Lauf (~1,5 GB RAM/Stadt)
   PHOTON_VERSION=1.2.1
   PHOTON_PORT=2322
   PHOTON_IMPORT_JAVA_OPTS=-Xmx4g # Heap für den Index-Import (19 Städte ≈ 18 GB Rohdaten)
   PHOTON_JAVA_OPTS=-Xmx1g
   ```

3. `.gitattributes` mit `*.sh text eol=lf` (sonst brechen Windows-Checkouts die
   Container-Shebangs mit exit 127 — der teuerste Ein-Zeichen-Bug des Projekts).

**Prüfen:** `docker compose config -q` (validiert die YAML ohne zu starten).

---

## Phase 1 — Datenschicht: PostGIS + loader

**Ziel:** Baumdaten liegen verifiziert in PostGIS und GeoServer kennt seine Layer.

**Bauen.** PostGIS ist ein Fertig-Image mit Healthcheck (`pg_isready`). Der `loader`
ist ein eigenständiges Maven-Projekt (bewusst getrennt vom Gradle-App-Build) mit
Multi-Stage-Dockerfile: Maven baut das Fat-JAR, das schlanke JRE-25-Image führt es aus.

**Verbinden.** Der loader bekommt seine gesamte Verdrahtung über Env (Servicenamen als
Hostnamen):
```yaml
CATALOG_URL:       https://…/catalog.json         # woher die Daten kommen
PG_URL:            jdbc:postgresql://postgis:5432/baumradar
GEOSERVER_URL:     http://geoserver:8080/geoserver
GEOSERVER_PG_HOST: postgis                          # was GeoServer als DB-Host einträgt
CITY_FILTER:       ${CITY_FILTER:-}
```
`depends_on` mit `condition: service_healthy` (PostGIS) bzw. `service_started`
(GeoServer) sorgt für die Startreihenfolge. `restart: "no"` — es ist ein Einmal-Job.

**Was der loader je Stadt tut** (idempotent über `dataVersion`):
1. Katalog von `CATALOG_URL` holen, gewünschte Städte per `CITY_FILTER` filtern.
2. `<stadt>.db.gz` + `.sig` laden (bei >50 MB als `.001/.002/…`-Chunks — binär konkatenieren).
3. **Ed25519-Signatur prüfen** (JDK-nativ; die App bündelt BouncyCastle nur für alte Android-Geräte).
4. Entpacken, Bäume + Zonen per JDBC nach PostGIS importieren (`ST_Buffer(…::geography, radius)` macht aus Mittelpunkt+Radius geodätisch korrekte Kreis-Polygone).
5. GeoServer per **REST** provisionieren: Workspace `baumradar`, PostGIS-Datastore, Layer (`trees`, `allergy_zones`), Statistik-Views, Styles.

**Prüfen:**
```bash
docker logs baumradar-loader                    # "importiert" / "übersprungen" je Stadt
docker exec baumradar-postgis psql -U baumradar -d baumradar -c "SELECT count(*) FROM trees;"
docker exec baumradar-postgis psql -U baumradar -d baumradar -c "SELECT count(*) FROM allergy_zones;"
```

---

## Phase 2 — OGC-Dienste verifizieren

**Ziel:** GeoServer liefert konforme Dienste, *bevor* der Client dazukommt — so trennt
man Server- von Client-Fehlern.

**Prüfen** (direkt gegen GeoServer auf `:8081`):
```bash
# WFS: eine Gattungs-Statistik als GeoJSON (bestätigt Datastore + Layer + Daten)
curl -s "http://localhost:8081/geoserver/baumradar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=baumradar:genus_stats&outputFormat=application/json&count=1"

# WFS + CQL: alle Birken in einem Ausschnitt (bestätigt CQL-Filter)
curl -s "http://localhost:8081/geoserver/baumradar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=baumradar:trees&outputFormat=application/json&count=5&cql_filter=genus_de='Birke'"

# WMS GetMap: ein PNG-Kachelausschnitt (bestätigt Rendering + Styles)
curl -s -o /tmp/map.png "http://localhost:8081/geoserver/baumradar/wms?service=WMS&version=1.3.0&request=GetMap&layers=baumradar:allergy_zones,baumradar:trees&crs=EPSG:4326&bbox=48.19,16.36,48.22,16.40&width=512&height=512&format=image/png"
```
GeoServer-Admin zum Durchklicken: `http://localhost:8081/geoserver` (Zugang in `.env`;
nur localhost — von außen per SSH-Tunnel `ssh -L 8081:localhost:8081 …`).

> **Stolperstein CQL-BBOX:** GeoServer nimmt `BBOX(geom, …)` bei EPSG:4326 als **lat,lon**.
> Mit explizitem CRS gilt lon,lat: `BBOX(geom, minLon, minLat, maxLon, maxLat, 'EPSG:4326')`.

---

## Phase 3 — Client (Angular + OpenLayers)

**Ziel:** Karte im Browser, alle Dienste same-origin — kein CORS.

**Bauen.** Multi-Stage: `node:24-alpine` baut die Angular-App (`npm run build`), das
`nginx:1.27-alpine`-Image liefert das statische Ergebnis aus.

**Verbinden — das ist der Kern.** nginx ist zugleich Webserver *und* Reverse-Proxy. In
`nginx.conf`:
```nginx
location /geoserver/   { proxy_pass http://geoserver:8080/geoserver/; }
location /graphhopper/ { resolver 127.0.0.11; set $u http://graphhopper:8989; … }
location /photon/      { resolver 127.0.0.11; set $u http://photon:2322;   … }
location /status/      { alias /webstatus/; }        # Lade-Status aus dem statusdata-Volume
```
Damit sieht der Browser nur **einen Port (8082)**, spricht aber GeoServer, GraphHopper
und Photon unter demselben Origin an — kein CORS, und nur *ein* Port muss ins LAN. Die
optionalen Upstreams (graphhopper/photon) löst nginx per **Resolver-Variable erst pro
Request** auf → nginx startet auch, wenn diese Profile gar nicht laufen (dann 502 statt
Startfehler).

**Prüfen:**
```bash
curl -s http://localhost:8082/stack.json                        # {"routing":…, "geocoding":…, "cityFilter":…}
curl -s "http://localhost:8082/geoserver/baumradar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=baumradar:genus_stats&outputFormat=application/json&count=1"
```
Beides muss über 8082 das Gleiche liefern wie in Phase 2 über 8081 — dann proxied nginx korrekt.

---

## Phase 4 — Routing (Profil `routing`, optional)

**Ziel:** Fuß-/Rad-Routing innerhalb jeder Stadt mit weicher Allergiezonen-Vermeidung.

**Bauen — zwei Container in Reihe.**
- `graph-builder` (Debian + osmium-tool/jq/curl): Einmal-Job. Lädt die DE/AT/CH-Länder-PBFs
  von Geofabrik (Mirror-Kaskade bei Ausfall), schneidet je Stadt die Katalog-BBox
  (+ `BBOX_MARGIN_DEG`) heraus und merged alles zu `island.osm.pbf`. **`EXTRACT_BATCH=1`**
  ist Pflicht auf normalen VMs (osmium hält ~1,5 GB je Stadt im Batch → sonst OOM/exit 137).
  Ein `island.marker` macht den Job idempotent.
- `graphhopper` (JRE 21 + Web-JAR aus dem GitHub-Release, Version über `GH_VERSION`): baut
  den Graphen aus dem Insel-PBF (Cache im Volume), Profile `foot`/`bike`, **flexibler Modus**
  (kein CH-Preprocessing → pro Anfrage ein Custom-Model). **Kein Fork.**

**Verbinden.** `graph-builder` schreibt `island.osm.pbf` ins **`routingdata`-Volume**,
`graphhopper` liest es dort — `depends_on: { graph-builder: { condition:
service_completed_successfully } }` erzwingt die Reihenfolge. `config.yml` braucht
zwingend `import.osm.ignored_highways: motorway` (ab GH 10 Pflichtparameter).

**Prüfen:**
```bash
curl -s http://localhost:8082/graphhopper/info | head -c 300      # Profile + BBox
# Eine Route (lon,lat!) mit Custom-Model schickt der Client; roh geht auch:
curl -s -X POST http://localhost:8082/graphhopper/route -H 'Content-Type: application/json' \
  -d '{"profile":"foot","points":[[8.515,47.169],[8.516,47.171]],"points_encoded":false,"ch.disable":true}'
```

---

## Phase 5 — Geocoding (Profil `geocoding`, optional)

**Ziel:** Adress-/Ortssuche lokal, ohne 11-GB-Länder-Indizes.

**Bauen.** `photon` (JRE 21 + Photon-JAR aus dem GitHub-Release, Version über
`PHOTON_VERSION`). Beim ersten Start importiert der Entrypoint die pro-Stadt-Geocoder-
Häppchen (`CITY_FILTER`-bewusst), **dedupliziert die Rand-Überlappungen** (Photon lehnt
doppelte `place_id` sonst mit HTTP 409 ab und bricht den ganzen Import ab) und merged
sie zu *einem* Import (Photon 1.x erlaubt nur einen pro Datenbank). Ein `imported_versions`-
Marker + Absturz-Wächter (hält nach 3 Fehlversuchen an statt endlos neu zu starten).

**Verbinden.** Liegt das Repo lokal vor, ist `../docs/data` als `/local-data:ro` gemountet
— dann liest photon Katalog + Häppchen **direkt von der Platte** statt von GitHub. Fällt
die lokale Instanz aus, wechselt der Client transparent auf `photon.komoot.io`.

**Prüfen:**
```bash
curl -s "http://localhost:8082/photon/api?q=Kolinplatz&limit=1" | head -c 200
```

---

## Der Ein-Befehl-Start (kapselt alles)

`start.sh` (Linux/macOS) bzw. `start.cmd`→`start.ps1` (Windows) fasst den Betrieb zusammen:
- legt beim ersten Lauf die `.env` aus `.env.example` an — **mit zufällig generierten
  Passwörtern** statt Demo-Werten;
- setzt `STACK_ROUTING`/`STACK_GEOCODING` passend zu den gewählten Profilen (für die
  Lade-Status-Anzeige);
- ruft `docker compose <profiles> up -d --build`.

```bash
./start.sh                               # Voll-Stack: Karte + Routing + Adresssuche
./start.sh --cities zug,wien             # nur bestimmte Städte (schneller Erststart)
./start.sh --no-routing --no-geocoding   # nur die Karte (kleinster Download)
./start.sh --down                        # stoppen (Daten-Volumes bleiben)
./start.sh --purge                       # alles entfernen bis auf das Repo
```

Der **erste** Start lädt/baut Images, Graph und Suchindex — je nach Städte-Auswahl und
Leitung Minuten bis Stunden; der rotierende Ring um das Logo zeigt den Stand. Danach
greifen die drei Idempotenz-Marker (loader `dataVersion`, graph-builder `island.marker`,
photon `imported_versions`) und **jeder weitere Start ist in Sekunden durch**.

---

## Die Verdrahtung auf einen Blick

```
Browser :8082 ─► [web / nginx] ─┬─ /geoserver/   ─► geoserver ──JDBC──► postgis
                                ├─ /graphhopper/ ─► graphhopper ◄─volume(routingdata)─ graph-builder ◄─ Geofabrik-PBFs
                                ├─ /photon/       ─► photon ◄─ Geocoder-Häppchen (docs/data | GitHub Pages)
                                └─ /status/       ◄─volume(statusdata)─ (graph-builder · graphhopper · photon)

   loader ──JDBC──► postgis      loader ──REST──► geoserver
   Datenquelle für loader/graph-builder/photon: catalog.json + signierte Artefakte @ GitHub Pages
```

**Vier Verbindungsmechanismen, mehr nicht:** (1) Docker-Netzwerk = DNS (Servicename =
Hostname); (2) JDBC + REST zwischen loader ↔ PostGIS ↔ GeoServer; (3) nginx als
Same-Origin-Reverse-Proxy; (4) geteilte Volumes als Übergabekanäle. Die `.env` ist die
Konfig-Klammer darüber.

## Verwandte Dokumente

- [WebGIS-Architektur](webgis_architecture.md) — das *Warum* hinter diesen Entscheidungen (inkl. Stolpersteine-Tabelle)
- [`webgis/README.md`](../webgis/README.md) — Schnellstart, Dienste-Tabelle, Konfiguration
- [Glossar](glossary.md) — PBF, Photon, CQL, Insel-Graph & Co. erklärt
- [Backend / Data-Processor](backend_architecture.md) — die *produzierende* Seite (erzeugt den Katalog, den dieser Stack konsumiert)

# Building the WebGIS from scratch (from-scratch guide)

> **Scenario:** the know-how is still there, but the `webgis/` folder is gone. This guide
> shows *what* to get *where*, *how* the seven containers are wired together, and *how* to
> confirm after each phase that it actually works. It complements the
> [architecture doc](webgis_architecture_en.md) (the *why*) with the *how of building it*.
>
> Deutsche Fassung: [webgis_bootstrap.md](webgis_bootstrap.md).

## What you actually need locally

**Only Docker (Desktop or Engine) with Compose v2.** No Java, no Node, no Maven, no
osmium on the host — every building block ships its tools inside its container or
downloads them during the build. The single external dependency is the **data catalog**
(`catalog.json` + signed `*.db.gz` + `geocoder_*.jsonl.gz`), produced and published to
GitHub Pages by the *separate* `data-processor` project. If the repo is checked out
locally (`docs/data/`), the geocoder even reads straight from there.

## The seven building blocks

| # | Container | Origin | Role |
|---|---|---|---|
| 1 | **postgis** | Off-the-shelf `postgis/postgis:17-3.5` | Spatial database — PostgreSQL + geo (`trees`, `allergy_zones`) |
| 2 | **geoserver** | Off-the-shelf `docker.osgeo.org/geoserver:3.0.0` | OGC services (WMS 1.3.0, WFS 2.0, OGC API Features, vector tiles) on PostGIS |
| 3 | **loader** | Self-built: `maven:3.9-eclipse-temurin-25` → `eclipse-temurin:25-jre` | Catalog → signature check → PostGIS import → GeoServer provisioning |
| 4 | **web** | Self-built: `node:24.18-alpine` → `nginx:1.27-alpine` | Angular UI (OpenLayers **and** MapLibre) + same-origin reverse proxy |
| 5 | **graph-builder** | Self-built: `debian:bookworm-slim` + osmium-tool/jq/curl | One-shot job: country PBFs → `island.osm.pbf` |
| 6 | **graphhopper** | Self-built: `eclipse-temurin:21-jre` + GH web JAR from GitHub release | Routing engine (foot + bike, flexible mode) |
| 7 | **photon** | Self-built: `eclipse-temurin:21-jre` + Photon JAR from GitHub release | Geocoder (address/place search), local |

**Two images are pulled ready-made** (1, 2). **Five you build yourself** — but "build
yourself" only means: Compose runs the Dockerfile, which in turn downloads everything
needed (GraphHopper JAR, Photon JAR, Maven deps, npm packages). You download nothing by
hand.

## Folder layout

```
webgis/
├─ docker-compose.yml        # the entire wiring (network, volumes, env)
├─ .env.example              # config template (ports, passwords, CITY_FILTER, versions)
├─ start.sh / start.cmd + start.ps1   # one-command start (creates .env with random passwords)
├─ .gitattributes            # *.sh = force LF (CRLF breaks container shebangs)
├─ loader/         Dockerfile · pom.xml · src/               (Java/Maven)
├─ client/         Dockerfile · package.json · nginx.conf · docker/10-stack-config.sh · src/  (Angular)
├─ graph-builder/  Dockerfile · build-island.sh
├─ graphhopper/    Dockerfile · config.yml · entrypoint.sh
└─ photon/         Dockerfile · entrypoint.sh
```

---

## Phase 0 — Scaffolding

**Goal:** the skeleton stands before a single line of application code exists.

1. Create `docker-compose.yml` with `name: baumradar-webgis`, the seven services and
   **six named volumes**:
   ```yaml
   volumes:
     pgdata:        # PostGIS database
     gsdata:        # GeoServer config
     osmcache:      # cached country PBFs (large, persistent)
     routingdata:   # island.osm.pbf + GraphHopper graph cache
     photondata:    # Photon search index
     statusdata:    # loading-status JSONs (containers write, nginx serves read-only)
   ```
   The network is created implicitly (`baumradar-webgis_default`); **the service name is
   the hostname** — that is the foundation of the entire wiring.

2. `.env.example` with the knobs:
   ```ini
   PG_DB=baumradar
   PG_USER=baumradar
   PG_PASSWORD=baumradar          # start.* replaces this with a random value
   PG_PORT=5433                   # host port (container: 5432)
   GEOSERVER_PORT=8081
   GEOSERVER_USER=admin
   GEOSERVER_PASSWORD=geoserver   # also replaced by a random value
   GEOSERVER_VERSION=3.0.0         # "3.0.x"/"3.1.x" would be nightly snapshots!
   BIND_HOST=127.0.0.1            # hardening: internal services localhost-only (0.0.0.0 = LAN)
   WEB_PORT=8082
   CITY_FILTER=                   # empty = all cities; e.g. "zug,wien"
   CATALOG_URL=https://raw.githubusercontent.com/<user>/<repo>/master/docs/data/catalog.json
   GH_VERSION=10.0
   GRAPHHOPPER_PORT=8989
   BBOX_MARGIN_DEG=0.03
   EXTRACT_BATCH=1                # cities per osmium run (~1.5 GB RAM/city)
   PHOTON_VERSION=1.2.1
   PHOTON_PORT=2322
   PHOTON_IMPORT_JAVA_OPTS=-Xmx4g # heap for the index import (31 cities ≈ 20 GB raw)
   PHOTON_JAVA_OPTS=-Xmx1g
   ```

3. `.gitattributes` with `*.sh text eol=lf` (otherwise Windows checkouts break the
   container shebangs with exit 127 — the project's most expensive one-character bug).

**Verify:** `docker compose config -q` (validates the YAML without starting anything).

---

## Phase 1 — Data layer: PostGIS + loader

**Goal:** tree data sits verified in PostGIS and GeoServer knows its layers.

**Build.** PostGIS is an off-the-shelf image with a healthcheck (`pg_isready`). The
`loader` is a standalone Maven project (deliberately separate from the Gradle app build)
with a multi-stage Dockerfile: Maven builds the fat JAR, the slim JRE-25 image runs it.

**Wire.** The loader gets its entire wiring via env (service names as hostnames):
```yaml
CATALOG_URL:       https://…/catalog.json         # where the data comes from
PG_URL:            jdbc:postgresql://postgis:5432/baumradar
GEOSERVER_URL:     http://geoserver:8080/geoserver
GEOSERVER_PG_HOST: postgis                          # what GeoServer records as DB host
CITY_FILTER:       ${CITY_FILTER:-}
```
`depends_on` with `condition: service_healthy` (PostGIS) resp. `service_started`
(GeoServer) enforces start order. `restart: "no"` — it is a one-shot job.

**What the loader does per city** (idempotent via `dataVersion`):
1. Fetch the catalog from `CATALOG_URL`, filter the wanted cities via `CITY_FILTER`.
2. Download `<city>.db.gz` + `.sig` (above 50 MB as `.001/.002/…` chunks — concatenate binary).
3. **Verify the Ed25519 signature** (JDK-native; the app bundles BouncyCastle only for old Android devices).
4. Unzip, import trees + zones into PostGIS via JDBC (`ST_Buffer(…::geography, radius)` turns centre+radius into geodetically correct circle polygons).
5. Provision GeoServer via **REST**: workspace `baumradar`, PostGIS datastore, layers (`trees`, `allergy_zones`), statistics views, styles.

**Verify:**
```bash
docker logs baumradar-loader                    # "imported" / "skipped" per city
docker exec baumradar-postgis psql -U baumradar -d baumradar -c "SELECT count(*) FROM trees;"
docker exec baumradar-postgis psql -U baumradar -d baumradar -c "SELECT count(*) FROM allergy_zones;"
```

---

## Phase 2 — Verify the OGC services

**Goal:** GeoServer serves conformant services *before* the client joins — that separates
server bugs from client bugs.

**Verify** (directly against GeoServer on `:8081`):
```bash
# WFS: one genus statistic as GeoJSON (confirms datastore + layer + data)
curl -s "http://localhost:8081/geoserver/baumradar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=baumradar:genus_stats&outputFormat=application/json&count=1"

# WFS + CQL: all birches in a window (confirms the CQL filter)
curl -s "http://localhost:8081/geoserver/baumradar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=baumradar:trees&outputFormat=application/json&count=5&cql_filter=genus_de='Birke'"

# WMS GetMap: a PNG tile (confirms rendering + styles)
curl -s -o /tmp/map.png "http://localhost:8081/geoserver/baumradar/wms?service=WMS&version=1.3.0&request=GetMap&layers=baumradar:allergy_zones,baumradar:trees&crs=EPSG:4326&bbox=48.19,16.36,48.22,16.40&width=512&height=512&format=image/png"
```
GeoServer admin to click through: `http://localhost:8081/geoserver` (credentials in
`.env`; localhost only — from outside via SSH tunnel `ssh -L 8081:localhost:8081 …`).

> **Pitfall CQL bbox:** GeoServer reads `BBOX(geom, …)` at EPSG:4326 as **lat,lon**. With
> an explicit CRS it is lon,lat: `BBOX(geom, minLon, minLat, maxLon, maxLat, 'EPSG:4326')`.

---

## Phase 3 — Client (Angular + OpenLayers/MapLibre)

**Goal:** a map in the browser, all services same-origin — no CORS.

**Build.** Multi-stage: `node:24-alpine` builds the Angular app (`npm run build`), the
`nginx:1.27-alpine` image serves the static result.

**Wire — this is the core.** nginx is both web server *and* reverse proxy. In
`nginx.conf`:
```nginx
location /geoserver/   { proxy_pass http://geoserver:8080/geoserver/; }
location /graphhopper/ { resolver 127.0.0.11; set $u http://graphhopper:8989; … }
location /photon/      { resolver 127.0.0.11; set $u http://photon:2322;   … }
location /status/      { alias /webstatus/; }        # loading status from the statusdata volume
```
So the browser sees only **one port (8082)** yet reaches GeoServer, GraphHopper and Photon
under the same origin — no CORS, and only *one* port needs to be exposed to the LAN. The
optional upstreams (graphhopper/photon) are resolved by nginx **per request via a resolver
variable** → nginx starts even when those profiles aren't running (502 per request instead
of a startup failure).

**Verify:**
```bash
curl -s http://localhost:8082/stack.json                        # {"routing":…, "geocoding":…, "cityFilter":…}
curl -s "http://localhost:8082/geoserver/baumradar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=baumradar:genus_stats&outputFormat=application/json&count=1"
```
Both must return over 8082 what phase 2 returned over 8081 — then nginx proxies correctly.

---

## Phase 4 — Routing (profile `routing`, optional)

**Goal:** foot/bike routing within each city with soft allergy-zone avoidance.

**Build — two containers in sequence.**
- `graph-builder` (Debian + osmium-tool/jq/curl): one-shot. Downloads the DE/AT/CH country
  PBFs from Geofabrik (mirror cascade on outage), cuts each city's catalog bbox
  (+ `BBOX_MARGIN_DEG`) and merges everything into `island.osm.pbf`. **`EXTRACT_BATCH=1`**
  is mandatory on normal VMs (osmium keeps ~1.5 GB per city in a batch → otherwise OOM/exit 137).
  An `island.marker` makes the job idempotent.
- `graphhopper` (JRE 21 + web JAR from the GitHub release, version via `GH_VERSION`): builds
  the graph from the island PBF (cached in the volume), profiles `foot`/`bike`, **flexible
  mode** (no CH preprocessing → a custom model per request). **No fork.**

**Wire.** `graph-builder` writes `island.osm.pbf` into the **`routingdata` volume**,
`graphhopper` reads it there — `depends_on: { graph-builder: { condition:
service_completed_successfully } }` enforces the order. `config.yml` mandatorily needs
`import.osm.ignored_highways: motorway` (required parameter since GH 10).

**Verify:**
```bash
curl -s http://localhost:8082/graphhopper/info | head -c 300      # profiles + bbox
# a route (lon,lat!) with a custom model is sent by the client; raw works too:
curl -s -X POST http://localhost:8082/graphhopper/route -H 'Content-Type: application/json' \
  -d '{"profile":"foot","points":[[8.515,47.169],[8.516,47.171]],"points_encoded":false,"ch.disable":true}'
```

---

## Phase 5 — Geocoding (profile `geocoding`, optional)

**Goal:** address/place search locally, without 11 GB country indexes.

**Build.** `photon` (JRE 21 + Photon JAR from the GitHub release, version via
`PHOTON_VERSION`). On first start the entrypoint imports the per-city geocoder slices
(`CITY_FILTER`-aware), **deduplicates the margin overlaps** (Photon otherwise rejects
duplicate `place_id`s with HTTP 409 and aborts the whole import) and merges them into
*one* import (Photon 1.x allows only one per database). An `imported_versions` marker +
crash guard (halts after 3 failed attempts instead of restarting forever).

**Wire.** If the repo is checked out locally, `../docs/data` is mounted as `/local-data:ro`
— then photon reads catalog + slices **straight from disk** instead of from GitHub. If the
local instance is absent, the client transparently falls back to `photon.komoot.io`.

**Verify:**
```bash
curl -s "http://localhost:8082/photon/api?q=Kolinplatz&limit=1" | head -c 200
```

---

## The one-command start (wraps everything)

`start.sh` (Linux/macOS) resp. `start.cmd`→`start.ps1` (Windows) wraps operation:
- on first run creates `.env` from `.env.example` — **with randomly generated passwords**
  instead of demo values;
- sets `STACK_ROUTING`/`STACK_GEOCODING` to match the chosen profiles (for the
  loading-status display);
- calls `docker compose <profiles> up -d --build`.

```bash
./start.sh                               # full stack: map + routing + address search
./start.sh --cities zug,wien             # only certain cities (faster first start)
./start.sh --no-routing --no-geocoding   # map only (smallest download)
./start.sh --down                        # stop (data volumes remain)
./start.sh --purge                       # remove everything but the repo
```

The **first** start downloads/builds images, graph and search index — minutes to hours
depending on city selection and bandwidth; the spinning ring around the logo shows the
state. After that the three idempotency markers (loader `dataVersion`, graph-builder
`island.marker`, photon `imported_versions`) take over and **every further start is done
in seconds**.

---

## The wiring at a glance

```
Browser :8082 ─► [web / nginx] ─┬─ /geoserver/   ─► geoserver ──JDBC──► postgis
                                ├─ /graphhopper/ ─► graphhopper ◄─volume(routingdata)─ graph-builder ◄─ Geofabrik PBFs
                                ├─ /photon/       ─► photon ◄─ geocoder slices (docs/data | GitHub Pages)
                                └─ /status/       ◄─volume(statusdata)─ (graph-builder · graphhopper · photon)

   loader ──JDBC──► postgis      loader ──REST──► geoserver
   data source for loader/graph-builder/photon: catalog.json + signed artefacts @ GitHub Pages
```

**Four wiring mechanisms, no more:** (1) Docker network = DNS (service name = hostname);
(2) JDBC + REST between loader ↔ PostGIS ↔ GeoServer; (3) nginx as a same-origin reverse
proxy; (4) shared volumes as handoff channels. The `.env` is the config bracket over all
of it.

## Related documents

- [WebGIS architecture](webgis_architecture_en.md) — the *why* behind these decisions (incl. the lessons-learned table)
- [`webgis/README.md`](../webgis/README.md) — quick start, service table, configuration
- [Glossary](glossary_en.md) — PBF, Photon, CQL, island graph & co. explained
- [Backend / data-processor](backend_architecture_en.md) — the *producing* side (creates the catalog this stack consumes)

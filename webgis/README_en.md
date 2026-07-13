<p align="center">
  <img src="../assets/favicons/web-app-manifest-192x192.png" alt="BaumRadar WebGIS" width="120"/>
</p>

# BaumRadar WebGIS — tech demo

*(Für die deutsche Dokumentation, siehe [README.md](README.md))*

A locally runnable web GIS built on **OGC standards** that consumes the signed
BaumRadar datasets (19 cities, ~2.6 M trees) — a standalone complement to the
Android app. No server operation required: everything runs in containers on your
own machine.

```
┌─────────┐     ┌──────────────┐    ┌────────────┐    ┌─────────────────┐
│ loader  │───▶│   PostGIS     │◀──│ GeoServer  │◀──│ Angular client  │
│ (Java 25│     │ trees        │    │ WMS 1.3.0  │    │ (OpenLayers)    │
│  Maven) │     │ allergy_zones│    │ WFS 2.0    │    └─────────────────┘
└────┬────┘     └──────────────┘    │ OGC API    │    ┌─────────────────┐
     │  catalog.json + *.db.gz      │ Features   │    │ GraphHopper     │
     └── GitHub Pages (signed) ─────└────────────┘    │ (island graph)  │
                                                      └─────────────────┘
```

## Quick start

The only prerequisite is **Docker** (Desktop or Engine, with Compose v2) —
no Java, no Node, no Maven. Then:

```powershell
cd webgis
.\start.cmd                              # Windows — full stack: map + routing + address search
./start.sh                               # Linux/macOS
```

**Local routing and local address search are the default** — that's the whole point.
Recommended for a first try (smaller, faster):

```powershell
.\start.cmd -Cities zug,wien                       # only certain cities (small downloads)
.\start.cmd -Cities zug -NoRouting -NoGeocoding    # map only (smallest download)
.\start.cmd -Down                                  # stop everything (data volumes remain)
.\start.cmd -Purge                                 # remove EVERYTHING (containers, data, images)
```

On Linux/macOS the switches are `--cities`, `--no-routing`, `--no-geocoding`,
`--down`, `--purge`. `-Down`/`--down` only stops (the next start builds on the
cached data); `-Purge`/`--purge` tears everything down until only the repository
remains.

On first run the script creates `.env` — **with randomly generated passwords**
instead of demo credentials — builds the containers and starts the stack.
Afterwards: map at http://localhost:8082. The `loader` fetches the chosen cities
from the GitHub Pages catalog, verifies the **Ed25519 signatures**, imports into
PostGIS and provisions GeoServer via REST — idempotent, unchanged cities
(`dataVersion`) are skipped on every further start.

**First-run data volumes** (one-time, cached in volumes afterwards): tree data
depending on `CITY_FILTER` (Zug ~2 MB … all 19 ~600 MB) · address search (default;
opt out with `-NoGeocoding`) downloads the geocoder slices of the chosen cities
(15–176 MB per city) · routing (default; opt out with `-NoRouting`) downloads the
country PBFs from Geofabrik (**DE ~4 GB**, AT/CH ~0.5 GB each — only the countries
of the chosen cities; if Geofabrik is down, the download automatically falls back
to mirrors: GWDG resp. osm.fr). The first start then builds the routing graph and
search index — **a few minutes of patience**; the web client bridges the address
search over photon.komoot.io in the meantime. If the repository is checked out in
full locally, the address search takes the geocoder files straight from `docs/data/`
— entirely without a GitHub download.

**Disk footprint (full stack, all 19 cities):** persistently ~50–60 GB of Docker
data (country PBFs ~6 GB, PostGIS ~3–4 GB, Photon search index ~15–25 GB, routing
graph, images/build cache ~8–10 GB) — **peaking during the first Photon import at
up to ~75 GB**, because the merged raw dump (>20 GB) sits next to the index being
built. With `-Cities zug` for a try, everything together stays under ~10 GB.

**Continuous operation:** the stack is designed for unattended long-term operation —
all runtime data is bounded or cleans up after itself: container logs are capped via
Compose at 10 MB × 3 per service (important for the publicly reachable web client),
Photon working files and city extracts are deleted after use, re-imports replace
their predecessors, and PostgreSQL handles WAL recycling/autovacuum on its own.
The only occasional host-side chore: repeated update builds feed the **global
Docker build cache** (measured: >20 GB after a few weeks). `docker system df`
shows the state; `docker builder prune -f --filter until=168h` removes only cache
entries older than 7 days (costs nothing but rebuild time). On engines with the
classic image store (e.g. Debian servers) rebuilds additionally leave untagged
old images — there `docker image prune -f` helps (removes only untagged images);
newer Docker Desktop versions (containerd store) clean these up on their own.

**How do I tell it is still loading?** As long as modules are missing, a ring
spins around the BaumRadar logo in the web client; hovering (or focusing) it opens
an overlay with the real per-module state — the same messages as `docker logs`
(e.g. "cutting city extracts 3–4/12"). If a running phase doesn't report in for
15 minutes, the overlay marks it as "possibly stuck".

## Security (home/school network too)

- **Random credentials:** `start.cmd`/`start.sh` replaces the demo passwords with
  random values when creating the `.env` (viewable in `webgis/.env`, which is
  git-ignored). Note: the PostGIS password is baked in when the database volume is
  first initialised — changing it later requires `docker compose down -v`.
- **Localhost only:** GeoServer admin, PostGIS, GraphHopper and Photon are bound to
  `127.0.0.1` by default — not reachable on the LAN. Only the web client (port 8082)
  is visible on the network; it forwards read-only services same-origin (no admin
  access, no WFS-T). Deliberate LAN access to the internal services:
  `BIND_HOST=0.0.0.0` in `.env`.

## Services

| Service | URL | Access |
|---|---|---|
| **Web client** (Angular + OpenLayers) | http://localhost:8082 | — (only LAN-visible port) |
| GeoServer web UI | http://localhost:8081/geoserver | see `.env` (localhost only) |
| WMS 1.3.0 | http://localhost:8081/geoserver/baumradar/wms | — |
| WFS 2.0 | http://localhost:8081/geoserver/baumradar/wfs | — |
| OGC API Features | http://localhost:8081/geoserver/ogc/features/v1 | — |
| PostGIS | localhost:5433 (container-internal 5432) | see `.env` (localhost only) |
| GraphHopper (profile `routing`) | http://localhost:8989 | — (localhost only; client uses `/graphhopper/`) |
| Photon geocoder (profile `geocoding`) | http://localhost:2322 | — (localhost only; client uses `/photon/`) |

Example requests:

```
# Map (WMS): trees + zones around Vienna Hauptbahnhof
http://localhost:8081/geoserver/baumradar/wms?service=WMS&version=1.3.0&request=GetMap&layers=baumradar:allergy_zones,baumradar:trees&crs=EPSG:4326&bbox=48.17,16.36,48.20,16.40&width=1024&height=768&format=image/png

# Features (WFS): all birches in a window as GeoJSON
http://localhost:8081/geoserver/baumradar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=baumradar:trees&outputFormat=application/json&count=100&cql_filter=genus_de='Birke'
```

## Why this architecture?

- **PostGIS instead of GeoPackage as the runtime store:** the allergy zones come as
  centre + radius (metres); `ST_Buffer(…::geography, radius)` turns that into
  geodetically correct circle polygons. Plus GiST indexes and robust concurrent
  serving for 2.6 M points. The outward OGC conformance is delivered by GeoServer
  (WMS/WFS/OGC API Features) — the store behind it is an implementation detail.
- **Maven instead of Gradle — on purpose:** the Android part of the repo is bound to
  Gradle by AGP. This server extension is deliberately separated as a standalone
  Maven project: its own lifecycle, no coupling to the app build, and as a tech demo
  the loader uses current Java 25 features (virtual threads, records, `java.net.http`)
  regardless of Android toolchains.
- **No BouncyCastle:** Ed25519 has been part of the standard JCE since JDK 15 — the
  loader's signature check needs no crypto dependency. (The app bundles BouncyCastle
  only so the check also runs on **older Android devices**: it supports devices from
  Android 10, and there the system crypto provider doesn't yet ship Ed25519 — on
  Android the JCA's offering depends on the device OS, not on the SDK the app is
  built against.)
- **One GraphHopper with an "island graph"** (phase 4): the 19 city extracts are
  merged into one PBF with osmium. Routing works within each city; between cities
  there is deliberately no connection. A build-plan marker makes the rebuild
  idempotent: as long as cities/bboxes/margin are unchanged, every further start
  skips extraction + graph rebuild (force fresh OSM data:
  `docker volume rm baumradar-webgis_routingdata`). Allergy zones are avoided per
  request as custom-model areas (soft — starting *inside* a zone always works); the
  avoidance strength is selectable in the client ("rather cross than a 5/10/20/50/100-fold
  detour", default 20-fold). The client only pulls the zones in the **tube around the
  route** (a budget per genus), counts the actual crossings itself (with zone metres)
  and shows the **direct route** as a dashed comparison; genus/factor changes recompute
  automatically (zone cache per leg).

## Configuration (`.env`)

| Variable | Default | Meaning |
|---|---|---|
| `PG_DB` / `PG_USER` / `PG_PASSWORD` | `baumradar` (script: random) | PostGIS access |
| `PG_PORT` | `5433` | host port for PostGIS (container: 5432) |
| `GEOSERVER_PORT` | `8081` | host port for GeoServer |
| `GEOSERVER_USER` / `GEOSERVER_PASSWORD` | `admin` / script: random | GeoServer admin |
| `GEOSERVER_VERSION` | `2.28.0` | image tag; at least 2.27 (OGC API Features is a stable extension only from there) |
| `BIND_HOST` | `127.0.0.1` | bind address of the internal services (`0.0.0.0` = LAN) |
| `WEB_PORT` | `8082` | host port of the web client |
| `CITY_FILTER` | *(empty = all)* | comma-separated city IDs, e.g. `wien,linz` — applies to loader, routing **and** geocoder |
| `CATALOG_URL` | GitHub Pages | source of the city catalog |
| `GH_VERSION` / `GRAPHHOPPER_PORT` / `GH_JAVA_OPTS` | `10.0` / `8989` / `-Xmx2g` | GraphHopper (profile `routing`) |
| `BBOX_MARGIN_DEG` | `0.03` | margin around city bboxes for the island graph |
| `PHOTON_VERSION` / `PHOTON_PORT` | `1.2.1` / `2322` | Photon geocoder (profile `geocoding`) |
| `PHOTON_IMPORT_JAVA_OPTS` | `-Xmx4g` | heap for the **one-time** index import — for all 19 cities (~18 GB raw) raise to `-Xmx6g`/`-Xmx8g` if the import crashes |
| `PHOTON_JAVA_OPTS` | `-Xmx1g` | heap of the running search service |

## Development

**Loader** — Maven need not be installed locally, tests run in the container:

```powershell
docker run --rm -v "${PWD}\loader:/src" -w /src -v baumradar-m2:/root/.m2 `
  maven:3.9-eclipse-temurin-25 mvn test
```

**Web client** — Angular 22 (standalone + signals, zoneless), OpenLayers directly
without a wrapper library; the map is created in `runOutsideAngular` (already
defused under zoneless, kept as a documented pattern). For a local `npm start` the
Angular CLI needs **Node ≥ 24.15** — alternatively build and tests run in the
container:

```powershell
# Dev server locally (Node >= 24.15): proxies /geoserver -> localhost:8081
cd client; npm install; npm start

# Unit tests (vitest) in the container:
docker run --rm -v "${PWD}\client:/src:ro" node:24-alpine `
  sh -c "cp -r /src /app && cd /app && npm install --silent && npx ng test --watch=false"
```

Integration test against real PostGIS (Testcontainers, needs a local Docker socket,
so best with a locally installed Maven/JDK 25):

```powershell
mvn test -Pit
```

## Phase status

- [x] Phase 0 – scaffolding (compose, README)
- [x] Phase 1 – loader (download → verify → PostGIS → GeoServer provisioning)
- [x] Phase 2 – GeoServer services verified end-to-end (WMS GetMap, WFS GetFeature+CQL, OGC API Features)
- [x] Phase 3 – Angular client (OpenLayers, signals, GetFeatureInfo popup, GPX drop; filter search over genus **and** species names, German as well as botanical — the selection stays genus-wide, matching the genus-clustered zones; list sortable by count/name; city selection scopes numbers + map)
- [x] Phase 4 – GraphHopper routing (island graph, tube-zone avoidance via custom model with convergence + crossing counter + direct-route comparison, start/destination by click **or** address search)
- [x] Geocoding – Photon from the catalog's per-city slices (local, `CITY_FILTER`-aware; fallback photon.komoot.io)
- [x] Phase 5 – docs: [architecture (DE)](../docs/webgis_architecture.md) / [EN](../docs/webgis_architecture_en.md) incl. diagrams + lessons learned, linked from the main README

The detailed architecture documentation (data path, routing, geocoding, pitfalls):
**[docs/webgis_architecture_en.md](../docs/webgis_architecture_en.md)** · [Deutsche Fassung](../docs/webgis_architecture.md)

Build the stack **from scratch** (what to download where, wiring, verification per phase):
**[docs/webgis_bootstrap_en.md](../docs/webgis_bootstrap_en.md)** · [Deutsche Fassung](../docs/webgis_bootstrap.md)

Unknown terms (PBF? Photon? CQL?) are explained by the **[glossary](../docs/glossary_en.md)** ([DE](../docs/glossary.md)).

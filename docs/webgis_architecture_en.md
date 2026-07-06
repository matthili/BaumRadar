# WebGIS Architecture (Tech Demo)

The BaumRadar WebGIS is a **fully self-hostable geographic information system** living in [`webgis/`](../webgis/): PostGIS, GeoServer, an Angular/OpenLayers client, GraphHopper routing and a Photon geocoder — all as a Docker Compose stack, started with a single script. It consumes **the same signed datasets as the Android app** (GitHub Pages, `docs/data/`) and demonstrates how to build an OGC-conformant GIS with offline routing and offline address search on top of an open-data pipeline.

![WebGIS architecture](architecture/08_webgis_architecture.png)

---

## Core idea: small, signed per-city artifacts

The central design decision of the whole project carries through to the WebGIS: **data is prepared once, centrally, and published per city** — every instance downloads only what it needs.

| Data | Raw source | Published per city | Zug example | Cologne example |
|---|---|---|---|---|
| Trees + allergy zones | 19 tree cadastres (CSV/GeoJSON/WFS/XLSX) | `<city>.db.gz` + `.sig` | ~2 MB | ~7 MB |
| Geocoder (addresses + POIs) | Photon planet dump (**25.9 GB**) | `geocoder_<city>.jsonl.gz` + `.sig` | 25 MB | 176 MB |
| Routing graph | Geofabrik country PBFs (DE ~4 GB) | *(cut locally: island PBF)* | — | — |

The effect in numbers: someone trying the webapp with just Zug downloads **25 MB for the address search instead of the 11 GB** that the pre-built Photon country indexes (DE+AT+CH) would weigh — with **identical search quality** inside the city area, because it is the same Photon with the same documents, merely spatially trimmed.

All published artifacts are **Ed25519-signed** and carry a **content-based version** (`dataVersion` / `geocoderVersion`) in [`catalog.json`](data_structure_en.md) — consumers detect both tampering and staleness.

---

## Containers at a glance

| Service | Tech | Purpose | Compose profile |
|---|---|---|---|
| `loader` | Java 25, Maven, virtual threads | fetch catalog → verify signatures → import into PostGIS → provision GeoServer | *(base)* |
| `postgis` | PostGIS 17/3.5 | runtime store: `trees`, `allergy_zones`, statistics | *(base)* |
| `geoserver` | GeoServer 2.28 | OGC services: WMS 1.3.0, WFS 2.0 (+CQL), OGC API Features | *(base)* |
| `web` | nginx + Angular 22/OpenLayers 10 | map, filters, routing UI; same-origin proxies | *(base)* |
| `graph-builder` | osmium-tool, jq | cut city bboxes from country PBFs → `island.osm.pbf` | `routing` |
| `graphhopper` | GraphHopper 10 | foot/bike routing, zone avoidance via custom model | `routing` |
| `photon` | Photon 1.2 (OpenSearch) | local address & POI search from the per-city slices | `geocoding` |

Starting is deliberately trivial (**only Docker required** — no Java, no Node on the host):

```powershell
cd webgis
.\start.cmd -Cities zug     # Linux/macOS: ./start.sh --cities zug
```

On first run the script creates the `.env` **with random passwords**, builds the images and starts the stack — **routing and address search run by default** (opt out via `-NoRouting`/`-NoGeocoding`). `CITY_FILTER` applies uniformly to the loader, routing **and** the geocoder.

---

## The loader: verify → import → provision

The loader is the Java counterpart of the `data-processor` — consuming instead of producing, and deliberately a **standalone Maven project** (the app side is bound to Gradle via AGP; there are no Android constraints here, hence Java 25 with virtual threads, records and `java.net.http`).

1. **Catalog + city selection**: `catalog.json` from GitHub Pages, filtered via `CITY_FILTER`.
2. **Idempotency**: the imported `dataVersion` per city is recorded in `import_state` — a repeated `docker compose up` skips unchanged cities in milliseconds.
3. **Signature checking without a crypto dependency**: Ed25519 has been part of the standard JCE since JDK 15 (`KeyFactory.getInstance("Ed25519")`). The app bundles BouncyCastle only so verification also works on older Android *devices* (it supports Android 10+, where the system crypto provider does not yet offer Ed25519) — the loader on a desktop JDK doesn't need it.
4. **Geodetically correct zones**: allergy zones arrive as *center + radius in metres*. Buffering in degrees would be latitude-dependent and wrong; hence:
   ```sql
   ST_Buffer(ST_SetSRID(ST_MakePoint(lon, lat), 4326)::geography, radius_m)::geometry
   ```
   The detour through the `geography` type buffers in real metres on the ellipsoid.
5. **Statistics as tables, not views**: `genus_stats` (global) and `genus_stats_city` (per city) are refilled with a `GROUP BY` after every import — so the client's WFS requests never aggregate 2.6 M rows on the fly.
6. **GeoServer as code**: workspace, PostGIS datastore, SLD styles and all layers are created idempotently through the **REST API** — no click-configuration, the stack is reproducible.

---

## OGC services

GeoServer publishes the PostGIS tables in a standards-conformant way:

- **WMS 1.3.0** renders trees and zones server-side (SLD styles); the client only requests visible tiles — 2.6 M points stay fast.
- **WFS 2.0 + CQL** serves features as GeoJSON; the client uses it for statistics, the search base and the **corridor zones for routing**.
- **OGC API Features** (stable extension since GeoServer 2.27) is the modern REST/JSON access to the same data.

Filters combine as CQL — genus selection and city scoping apply to WMS **and** WFS alike:

```
genus_de IN ('Birke','Hasel') AND city_id = 'wien'
```

---

## Client: Angular 22 + OpenLayers, no wrapper library

- **Zoneless + signals**: all UI state lives in signals; three `effect()`s translate state changes into imperative map calls. The OpenLayers map itself is created inside `runOutsideAngular` — OL fires high-frequency events (`pointermove`, `postrender`) that must not trigger change detection.
- **Same-origin proxies instead of CORS**: nginx forwards `/geoserver`, `/graphhopper` and `/photon` to the containers. The client only knows relative URLs; in dev mode `proxy.conf.json` plays the same role. No CORS setup, no origin issues.
- **City selection is a real filter**: it doesn't just zoom — it scopes the genus counts (from `genus_stats_city`), the map layers (CQL) and the address search (bbox parameter). "Ahorn" (maple) shows 50,241 trees in Vienna — not the global half million.
- **Search like the app profile**: genus *and* species names, German and botanical ("Acer" finds maple); selection stays genus-wide, matching the genus-clustered zones.

---

## Routing: island graph + corridor zones

![Route sequence](architecture/09_webgis_route_geocoding.png)

**Island graph:** the `graph-builder` downloads the Geofabrik country PBFs (once, volume-cached), cuts out the 19 city bboxes (+ margin) with `osmium extract` and merges them into **one** `island.osm.pbf` (a few hundred MB instead of ~5 GB). Routing works within each city; between cities there is deliberately no connection. GraphHopper builds its graph from it on first start (cached in a volume) — profiles `foot` and `bike`, **flexible mode** (no contraction-hierarchies preprocessing), so each request can carry a custom model.

**Zone avoidance (corridor approach):** you cannot feed GraphHopper thousands of circle zones globally. Instead, the client fetches only the relevant zones **per route request**:

1. WFS query: zones of the genera selected in the profile, restricted to the **start–destination bbox + buffer** (CQL `BBOX`), capped at 300.
2. The hits are bundled client-side into **one MultiPolygon** (one custom-model area instead of N — a lean request, one condition).
3. GraphHopper receives `priority: [{ if: "in_avoid", multiply_by: <factor> }]` — edges inside zones are penalised but never forbidden (**soft avoidance**: a route that *starts* inside a zone always works). The strength is chosen in the client under "advanced options": *"rather cross a zone than take an N-fold detour"* with N = 5/10/**20 (default)**/50/100 (≙ `multiply_by` 0.2 … 0.01) — at 100× the avoidance is effectively strict, without the pitfalls of a hard block.

Measured example (Zug, foot): baseline 1,438 m → with 49 maple zones in the corridor 1,884 m (+446 m detour around the zones).

*Deliberately rejected:* globally pre-merging all zones (they are already clustered — another `ST_Union` merely wraps disjoint circles) and marking affected graph edges at import time (more scalable, but requires a custom GraphHopper build; the corridor approach works with stock GraphHopper).

---

## Geocoding: the planet, sliced

Address and POI search ("TU Wien", "Kolinplatz 1") locally, without 11 GB country indexes:

1. **Source** is the official **Photon planet dump** (`.jsonl.zst`, 25.9 GB) — line-wise JSON batches, every place with a `centroid` coordinate.
2. The **GeocoderCutter** in the `data-processor` streams the dump **once** (zstd → Jackson streaming, ~38 min for 367 M places) and distributes each place into the city files whose bbox+15 km margin contains it. Every file keeps the header and CountryInfo lines — it remains a **standalone, Photon-importable dump**.
3. Publication is per city (`geocoder_<city>.jsonl.gz`, signed, chunked above 50 MB); refreshes are triggered per city from the backend runner UI — street names change slowly, quarterly is plenty.
4. The **photon container** downloads the slices for the `CITY_FILTER` cities on first start, merges them (skipping the preamble lines of subsequent files — Photon 1.x allows only *one* import per database) and then serves `/api` incl. typo tolerance, POIs and `bbox` scoping.
5. If the local instance is absent (profile not started), the client transparently falls back to **photon.komoot.io** — with a visible note that queries leave the machine.

A place inside the overlap of two city margins (Ruhr area!) deliberately lands in both files — every file is self-contained; Photon deduplicates at import via the stable `place_id`.

---

## Security (home/school-network friendly)

- **No default passwords**: `start.cmd`/`start.sh` generates random credentials for PostGIS and GeoServer when creating the `.env`. (The PostGIS password is baked in at first volume initialisation — changing it later requires `docker compose down -v`.)
- **Localhost only**: GeoServer admin, PostGIS, GraphHopper and Photon bind to `127.0.0.1` by default. **Only the web client (8082) is visible on the LAN** — and it forwards read-only services same-origin (no admin access, no WFS-T). Deliberate LAN exposure: `BIND_HOST=0.0.0.0`.

---

## Pitfalls (lessons learned)

Things only real operation revealed — documented because they teach something:

| Pitfall | Symptom | Cause & fix |
|---|---|---|
| **CQL BBOX axis order** | WFS query returns 0 zones although 918 exist | GeoServer reads `BBOX(geom, …)` as **lat,lon** for EPSG:4326. With an explicit CRS it is lon,lat: `BBOX(geom, minLon, minLat, maxLon, maxLat, 'EPSG:4326')` |
| **GraphHopper 10: mandatory parameter** | container stuck in a restart loop | `import.osm.ignored_highways` is **required** since GH 10 (for foot/bike: `motorway`) |
| **Photon 1.x: import drops data** | after importing several cities only the last one is searchable | every `photon import` **wipes** the existing index → merge all city dumps, import once |
| **Photon 1.x: CLI + bind** | "database not found" despite import; API unreachable from outside | new command syntax (`photon import` / `photon serve`) and default bind `127.0.0.1` → `-listen-ip 0.0.0.0` in the container |
| **Chunks are byte slices** | `gunzip` of a single chunk: "unexpected end of file" | the 50 MB chunks are slices of **one** gz file: concatenate binarily first, then decompress |
| **nginx + optional upstreams** | nginx refuses to start when `graphhopper`/`photon` (profile off) don't exist | `resolver 127.0.0.11` + upstream in a **variable** → DNS resolves per request, nginx always starts |
| **Opendatasoft export limit** | a city yields exactly 9,997/10,000 trees | `/exports/geojson` caps at `offset+limit>10000` → single-shot without paging (hit Dortmund *and* Basel) |
| **`jq`: context in function arguments** | `Cannot index array with string "id"` | in `$ids \| index(.id)`, `.id` is evaluated against `$ids` — **bind the id first**: `.id as $cid` |
| **PowerShell 5.1 reads BOM-less UTF-8 as ANSI** | `start.cmd` fails on a fresh machine with garbled parse errors (`lÃ¤uft`, "missing parenthesis") | `start.cmd` invokes *Windows PowerShell 5.1*, which reads BOM-less `.ps1` files in the ANSI codepage. An em dash (UTF-8 `E2 80 94`) contains byte `0x94` = the cp1252 **curly quote** — which terminates strings early. Fix: save the `.ps1` **with a UTF-8 BOM** and avoid typographic characters in scripts. (Sneaky: under pwsh 7 the file works even without a BOM — you must test the `start.cmd` path.) |
| **Git `autocrlf` breaks container scripts** | `graph-builder` dies instantly: "exit 127" — with no further message | Git for Windows with `core.autocrlf=true` checks out shell scripts with **CRLF**; inside the Linux container the shebang becomes `bash\r` → interpreter "not found" = exit 127. (ZIP downloads are unaffected — only git checkouts!) Double protection: `.gitattributes` with `*.sh text eol=lf` **and** `sed -i 's/\r$//'` in the Dockerfile right after the `COPY`. |
| **Docker Desktop aborts long `compose up` waits** | After minutes: `request returned 500 Internal Server Error … dockerDesktopLinuxEngine` | Docker Desktop (Windows) occasionally throws internal errors while polling status over its pipe — especially during long one-shot jobs (PBF downloads). The **detached containers keep running**; only the CLI wait dies. Remedy: re-run the start script (idempotent) — and make **downloads atomic** (`.part` + `mv`, `curl -C -`) so an abort never leaves a half file that looks complete. |
| **osmium multi-extract gets shot by the OOM killer** | `graph-builder`: `Killed  osmium extract …` → exit **137** (= SIGKILL) — while it worked on the 96 GB dev PC | `osmium extract` keeps **a bitmap over the global node-ID space per extract** — measured ~1.5–2.5 GB, *regardless of city size* (a city's IDs are scattered across the whole ID space by 20 years of OSM history). 12 cities in one pass ≈ 18 GB → dead even on 16 GB VMs. Fix: **one city per osmium run** (`EXTRACT_BATCH=1`, calibrated: works from ~3 GB Docker VM; raise on big machines) + `-s simple`. Rules of thumb: exit 137 almost always means "out of memory" — and "works on my machine" often just means "my RAM is bigger". |

---

## Related documents

- [Glossary](glossary_en.md) — every term, service and standard used here, explained
- [`webgis/README.md`](../webgis/README.md) — quick start, service table, configuration
- [Backend / data-processor](backend_architecture_en.md) — the producing side of the pipeline (incl. GeocoderCutter)
- [Data structure & third party](data_structure_en.md) — `catalog.json`, database schema, building your own consumers
- [App architecture](app_architecture_en.md) — the Android side of the same datasets

# Glossary

Terms, modules, services and standards that keep appearing throughout the
BaumRadar documentation — each explained in general **and** with its role in
this project. *(Deutsche Fassung: [glossary.md](glossary.md))*

---

## BaumRadar building blocks

**data-processor** — The project's Java backend (a Gradle module next to the Android app). A batch pipeline: reads the open tree cadastres of 19 cities (CSV, GeoJSON, WFS, XLSX, Esri JSON), unifies names and coordinates, clusters allergy zones, signs everything and publishes it as per-city slices to `docs/data/`.

**Runner (runner UI)** — The data-processor's local web interface (`--args="--ui"`, port 8420, zero extra dependencies). Cities can be re-published individually via checkboxes, geocoder data refreshed per city; progress streams live via Server-Sent Events, and once all work is done the runner shuts itself down.

**catalog.json (catalog)** — The central directory on GitHub Pages and the entry point for *every* consumer (Android app, WebGIS loader, Photon container). Per city: download URLs, signature URLs, bounding box and versions. Fields are documented in [data_structure_en.md](data_structure_en.md).

**dataVersion / geocoderVersion** — Content-based fingerprints (16 hex characters) per city in the catalog. They only change when the *data* actually changes (computed independent of IDs and row order) — that is how app and loader detect staleness and re-download only what changed.

**Allergy zone (geofence)** — A circular zone (center + radius in metres) around spatially clustered trees of *one* genus. The backend computes them over a ~100 m grid. The Android app registers them as Android geofences for lock-screen warnings; the WebGIS turns them into metre-true polygons via `ST_Buffer` that routing avoids.

**Per-city slices** — The project's core design idea: all data is published **per city** (`<city>.db.gz` for trees/zones, `geocoder_<city>.jsonl.gz` for address search), signed and versioned. Every consumer downloads only what it needs — 25 MB for Zug's address search instead of an 11 GB country index.

**Chunk** — GitHub Pages dislikes files above 50 MB, so larger artifacts are cut into numbered parts (`berlin.db.gz.001`, `.002`, …). Important: chunks are **byte slices of a single file** — concatenate binarily first, then decompress.

**Harmonisation** — Three-layer clean-up of species names in the backend: layer 1 normalises deterministically (cultivar spellings, mojibake, Latin canonicalisation), layer 2 unifies German names via a curated alias table, layer 3 writes a report as a work list. Result: 18 spellings of *Acer platanoides* collapse into one clean "Spitz-Ahorn".

**GeocoderCutter** — Backend tool that slices per-city geocoder data out of the official Photon planet dump (~26 GB): one streaming pass, each place assigned to the city bboxes (+15 km margin) containing its coordinate. Every output file remains a standalone, Photon-importable dump.

**Island graph (island.osm.pbf)** — The 19 city cut-outs (+ margin) merged into **one** OSM file. GraphHopper builds its routing graph from it: routing works within each city; between cities there is deliberately no connection — hence "island".

**graph-builder** — One-shot container in the WebGIS stack: downloads the country PBFs from Geofabrik (cached, abort-safe), cuts out the city bboxes with osmium (one city per run — see the pitfalls table) and merges them into the island graph.

**loader (WebGIS)** — The Java 25 consumer in the WebGIS stack: fetch catalog → verify Ed25519 signatures → import trees and zones into PostGIS → provision GeoServer via REST. Idempotent: unchanged cities (same `dataVersion`) are skipped.

**web (container)** — nginx serving the Angular client and proxying `/geoserver`, `/graphhopper` and `/photon` same-origin to the other containers (no CORS needed). The only port of the stack visible on the LAN.

---

## Services & applications

**GeoServer** — The most widely used open-source map server (Java): publishes geodata from databases as standards-compliant OGC services. In this project it is provisioned entirely **via its REST API** ("configuration as code") — workspace, layers and styles are created reproducibly without any clicking.

**PostGIS** — The geo extension of PostgreSQL: geometry data types, spatial indexes (GiST) and hundreds of functions. Central for BaumRadar: `ST_Buffer(…::geography, radius)` buffers in *true metres* on the ellipsoid — a buffer in degrees would be latitude-dependent and wrong.

**GraphHopper** — Open-source routing engine based on OSM (Java). In the WebGIS it runs in *flexible mode* with the `foot` and `bike` profiles and accepts a custom model per request — the foundation of the allergy-zone avoidance.

**Custom model (GraphHopper)** — A JSON rule set sent along with a routing request: it can adjust edge priorities and speeds and reference **areas** (polygons). BaumRadar bundles the allergy zones in the route corridor into one MultiPolygon and down-weights edges inside it — the factor is user-selectable in the client ("rather cross than a 5/10/20/50/100-fold detour", default 20× ≙ 0.05). *Soft* avoidance: a route that starts inside a zone always works.

**Contraction Hierarchies (CH)** — A precomputation of the road graph for extremely fast queries. The catch: edge weights are frozen at preprocessing time — per-request custom models become impossible. That is why CH is deliberately disabled here (the small island graph is fast enough without it).

**Photon** — Open-source geocoder by Komoot (the geocoder, not the elementary particle): typo-tolerant, built for search-as-you-type, knows addresses *and* POIs. In the WebGIS it is fed from the catalog's per-city slices; if it is absent, the client automatically falls back to the public instance photon.komoot.io.

**OpenSearch** — Full-text search engine (Elasticsearch fork); Photon 1.x embeds it as its index store. Invisible to operators — it lives inside the Photon process.

**Nominatim** — The "official" OSM geocoder (powers the search on openstreetmap.org, among others). The Photon dumps are produced from Nominatim data, and the **Android app** uses Nominatim's public API for its address search.

**OSRM** — Open Source Routing Machine, a public routing service. The **Android app** fetches up to three route alternatives from it and checks them against the local zones; the **WebGIS** instead routes entirely locally with GraphHopper (only that makes custom-model zone avoidance possible).

**OpenLayers** — JavaScript map library in the web client: renders the OSM base map, the WMS layers, markers and routes, and provides interactions (click, drag & drop). Used deliberately without an Angular wrapper; the map lives outside Angular's change detection.

**osmium (osmium-tool)** — The Swiss army knife for OSM files: `extract` (cut by bbox), `merge`, `tags-filter`, `fileinfo`. A memory quirk that earned us a pitfall entry: `extract` keeps a bitmap over the *global* node-ID space per cut-out — ~1.5 GB, no matter how small the city is.

**Geofabrik** — German provider of daily-updated OSM extracts (continents, countries, states) as PBF. Source of the raw routing data (`germany-latest.osm.pbf` ~4 GB etc.).

**Compose profile** — Docker Compose mechanism for switching service groups on and off. In the WebGIS: `routing` (graph-builder + GraphHopper) and `geocoding` (Photon). The start script enables both by default; `-NoRouting`/`-NoGeocoding` opt out.

---

## Map standards (OGC & friends)

**OGC** — The *Open Geospatial Consortium*, the standards body for geospatial interfaces. "OGC-compliant" means: any standard client (QGIS, ArcGIS, web libraries) can use the services without special knowledge.

**WMS 1.3.0 (Web Map Service)** — Delivers **pre-rendered map images** (tiles) instead of raw data. That keeps 2.6 M trees fluid: the server renders, the browser only shows images. Styling comes from SLD files.

**WFS 2.0 (Web Feature Service)** — Delivers **raw features** (here as GeoJSON) with filtering. In this project the basis for statistics, the client search and the routing's corridor zones.

**OGC API Features** — The modern REST/JSON successor of WFS (same data, contemporary interface). A stable GeoServer extension only since 2.27 — the reason for our version floor.

**CQL (Common Query Language)** — GeoServer's filter language for WMS/WFS requests, e.g. `genus_de IN ('Birke') AND city_id = 'wien'`. Pitfall: for `BBOX(…)` in EPSG:4326 GeoServer expects **lat,lon** — unless the CRS is named explicitly.

**SLD (Styled Layer Descriptor)** — XML format that gives WMS layers their appearance (symbols, colours, scale rules). The yellow tree dots and red zones come from two SLD files the loader provisions.

**GPX (GPS Exchange Format)** — XML interchange format for routes and tracks. The Android app exports planned routes as GPX (e.g. for navigation apps); the web client accepts GPX files via drag & drop onto the map.

---

## Data formats & geo fundamentals

**OSM (OpenStreetMap)** — The free map of the world, maintained by millions of volunteers. Data basis for the base map, routing and geocoding. Licence: ODbL — free to use, attribution required.

**PBF (Protocolbuffer Binary Format)** — The compact binary format for OSM data (roughly half the size of the XML equivalent and much faster to process). All raw routing data comes as `.osm.pbf`.

**GeoJSON** — Geodata as JSON (points, lines, polygons + attributes). The rule that prevents bugs: coordinates are **always [longitude, latitude]** — [lon, lat], never the other way round.

**JSONL (JSON Lines)** — One JSON object per text line. Streamable without parsing the whole content — which is why the Photon dumps use it and the GeocoderCutter streams them line by line.

**Zstandard (zstd)** — Modern compression format (fast at high ratios). The Photon planet dump ships as `.jsonl.zst`; since the JDK has no native zstd, the data-processor uses the `zstd-jni` library.

**SQLite** — A database in a single file, no server. Format of the per-city tree databases: the backend produces them, the app imports them into its Room database, the WebGIS loader reads them via JDBC.

**BBox (bounding box)** — The enclosing rectangle of an area, given by two corner coordinates. Mind the conventions: the BaumRadar catalog uses `[minLat, minLon, maxLat, maxLon]`, while GeoJSON/Photon/GraphHopper are lon-first — mixing them up is *the* classic geo bug.

**WGS84 / EPSG:4326** — The coordinate system of GPS: latitude/longitude in degrees on the ellipsoid; `EPSG:4326` is its catalogue number. Related: **EPSG:3857** ("web Mercator"), the projection of browser map tiles, and **UTM**, metric zone systems some cadastres deliver in (the backend converts to WGS84).

**Ed25519** — Modern, fast signature scheme (elliptic curves). The backend signs every published file; app and loader verify against a built-in public key — tampering or transfer corruption is caught before any data is used.

**GitHub Pages / raw.githubusercontent** — Static file hosting straight from the repository. BaumRadar's "server without a server": catalog and per-city slices live there, no operations needed. Quirk: aggressive caching — which is why all consumers append a timestamp parameter (cache buster).

---

*Back to: [README](../README_en.md) · [WebGIS README](../webgis/README.md) · [WebGIS architecture](webgis_architecture_en.md)*

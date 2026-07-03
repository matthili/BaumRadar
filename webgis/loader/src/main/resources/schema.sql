-- BaumRadar WebGIS — PostGIS-Schema (idempotent).
-- Primärschlüssel sind bewusst zusammengesetzt (city_id, id): die IDs der
-- Quelldaten sind zwar city-präfixiert, aber so sind Kollisionen zwischen
-- Städten strukturell ausgeschlossen.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS trees (
    id         TEXT NOT NULL,
    city_id    TEXT NOT NULL,
    genus_de   TEXT,
    genus_en   TEXT,
    species_de TEXT,
    species_en TEXT,
    geom       geometry(Point, 4326) NOT NULL,
    PRIMARY KEY (city_id, id)
);
CREATE INDEX IF NOT EXISTS trees_geom_gix  ON trees USING GIST (geom);
CREATE INDEX IF NOT EXISTS trees_genus_idx ON trees (genus_de);

-- Allergiezonen: aus Mittelpunkt + Radius (Meter) gebufferte Kreis-Polygone.
-- Das Buffern passiert beim Import via ST_Buffer(geography) — geodätisch korrekt.
CREATE TABLE IF NOT EXISTS allergy_zones (
    id         TEXT NOT NULL,
    city_id    TEXT NOT NULL,
    genus_de   TEXT,
    radius_m   INTEGER NOT NULL,
    tree_count INTEGER NOT NULL,
    geom       geometry(Polygon, 4326) NOT NULL,
    PRIMARY KEY (city_id, id)
);
CREATE INDEX IF NOT EXISTS zones_geom_gix  ON allergy_zones USING GIST (geom);
CREATE INDEX IF NOT EXISTS zones_genus_idx ON allergy_zones (genus_de);

-- Idempotenz-Buchführung: welche dataVersion je Stadt bereits importiert ist.
CREATE TABLE IF NOT EXISTS import_state (
    city_id      TEXT PRIMARY KEY,
    data_version TEXT NOT NULL,
    tree_count   INTEGER NOT NULL DEFAULT 0,
    zone_count   INTEGER NOT NULL DEFAULT 0,
    imported_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

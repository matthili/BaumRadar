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

-- Gattungs-Statistik für den Web-Client (Filter-UI): klein, geometrielos,
-- wird vom Loader nach jedem Import-Lauf neu befüllt und via WFS publiziert.
CREATE TABLE IF NOT EXISTS genus_stats (
    genus_de   TEXT PRIMARY KEY,
    tree_count INTEGER NOT NULL
);
-- Nachrüstung für Bestände aus früheren Loader-Versionen (idempotent).
ALTER TABLE genus_stats ADD COLUMN IF NOT EXISTS genus_en TEXT;

-- Dieselbe Gattungs-Statistik, aber je Stadt aufgeschlüsselt: Grundlage dafür, dass
-- die Auswahl einer Stadt im Client die angezeigten Baumzahlen auf DIESE Stadt scopt
-- (statt der globalen Summe über alle Städte). Geometrielos, via WFS gelesen.
CREATE TABLE IF NOT EXISTS genus_stats_city (
    city_id    TEXT NOT NULL,
    genus_de   TEXT NOT NULL,
    genus_en   TEXT,
    tree_count INTEGER NOT NULL,
    PRIMARY KEY (city_id, genus_de)
);

-- Art-Tupel je Gattung (DISTINCT wie im Allergie-Profil der App): Grundlage der
-- Client-Suche über deutsche UND botanische Namen ("Acer", "irgendwas mit spitz").
-- Die Auswahl im Client bleibt gattungsweit — passend zu den genus-geclusterten Zonen.
CREATE TABLE IF NOT EXISTS species_stats (
    genus_de   TEXT NOT NULL,
    species_de TEXT NOT NULL DEFAULT '',
    species_en TEXT NOT NULL DEFAULT '',
    tree_count INTEGER NOT NULL,
    PRIMARY KEY (genus_de, species_de, species_en)
);

-- Generalisierung für die Vektorkachel-Ansicht (MapLibre): vorgerechnete
-- Aggregate, damit Kacheln transportieren, was ein Bildschirm unterscheiden
-- kann — nicht jeden einzelnen Baum (Wien roh: ~4,5 MB pro Übersichts-Kachel).

-- Ein Symbol je Stadt für die DACH-Übersicht (Zoom < 8).
CREATE TABLE IF NOT EXISTS city_points (
    city_id    TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    tree_count INTEGER NOT NULL,
    geom       geometry(Point, 4326) NOT NULL
);
CREATE INDEX IF NOT EXISTS city_points_gix ON city_points USING GIST (geom);

-- Rasterzellen je Zoom-Band (Zellgröße in Grad, floor-gerastert => deterministisch
-- und damit kachel-cachebar). dominant_genus färbt die Aggregat-Ansicht allergie-
-- relevant ein, ohne die volle Gattungs-Aufschlüsselung zu transportieren.
CREATE TABLE IF NOT EXISTS tree_cells (
    zoom_band      SMALLINT NOT NULL,
    cell_x         INTEGER  NOT NULL,
    cell_y         INTEGER  NOT NULL,
    city_id        TEXT     NOT NULL,
    tree_count     INTEGER  NOT NULL,
    dominant_genus TEXT,
    dominant_count INTEGER  NOT NULL DEFAULT 0,
    geom           geometry(Point, 4326) NOT NULL,
    PRIMARY KEY (zoom_band, cell_x, cell_y, city_id)
);
CREATE INDEX IF NOT EXISTS tree_cells_gix ON tree_cells USING GIST (geom);

-- Ein View je Zoom-Band: so enthält eine Vektorkachel eines Bandes nur die
-- eigenen Zellen (die gemeinsame Tabelle würde jedes Band dreifach ausliefern).
CREATE OR REPLACE VIEW tree_cells_z8  AS SELECT * FROM tree_cells WHERE zoom_band = 8;
CREATE OR REPLACE VIEW tree_cells_z11 AS SELECT * FROM tree_cells WHERE zoom_band = 11;
CREATE OR REPLACE VIEW tree_cells_z13 AS SELECT * FROM tree_cells WHERE zoom_band = 13;

-- Idempotenz-Buchführung: welche dataVersion je Stadt bereits importiert ist.
CREATE TABLE IF NOT EXISTS import_state (
    city_id      TEXT PRIMARY KEY,
    data_version TEXT NOT NULL,
    tree_count   INTEGER NOT NULL DEFAULT 0,
    zone_count   INTEGER NOT NULL DEFAULT 0,
    imported_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

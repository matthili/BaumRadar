/** Gemeinsame Datentypen des BaumRadar-WebGIS-Clients. */

/** Stadteintrag aus docs/data/catalog.json (GitHub Pages — dieselbe Quelle wie die Android-App). */
export interface City {
  id: string;
  name: string;
  country: string;
  /** [minLat, minLon, maxLat, maxLon] — Reihenfolge wie im Katalog. */
  boundingBox: [number, number, number, number];
  dataVersion: string;
}

export interface Catalog {
  version: number;
  cities: City[];
}

/** Zeile aus baumradar:genus_stats (vom Loader befüllt, via WFS gelesen). */
export interface GenusStat {
  genusDe: string;
  genusEn: string | null;
  treeCount: number;
}

/**
 * Art-Tupel aus baumradar:species_stats (DISTINCT Gattung + beide Artnamen,
 * analog zur Profil-Liste der App). `speciesEn` trägt den botanischen Namen.
 */
export interface SpeciesStat {
  genusDe: string;
  speciesDe: string;
  speciesEn: string;
  treeCount: number;
}

/** Baum-Treffer aus WMS GetFeatureInfo. */
export interface TreeHit {
  genusDe: string;
  genusEn: string | null;
  speciesDe: string | null;
  speciesEn: string | null;
}

/** Allergiezonen-Treffer aus WMS GetFeatureInfo. */
export interface ZoneHit {
  genusDe: string;
  treeCount: number;
  radiusM: number;
}

/** Inhalt der Karten-Sprechblase nach einem Klick. */
export interface PopupData {
  trees: TreeHit[];
  zones: ZoneHit[];
}

/** Routing-Profil (GraphHopper). */
export type RouteProfile = 'foot' | 'bike';

/** Punkt in WGS84 als (lon, lat) — die Reihenfolge, die GraphHopper erwartet. */
export interface LonLat {
  lon: number;
  lat: number;
}

/** Treffer der Adress-/Ortssuche (Photon), Koordinate in WGS84. */
export interface GeocodeHit {
  label: string;
  lon: number;
  lat: number;
}

/** Ergebnis einer Routenberechnung. */
export interface RouteResult {
  /** Stützpunkte der Route als [lon, lat]. */
  coords: [number, number][];
  distanceM: number;
  timeMs: number;
  /** Wie viele Allergiezonen im Routen-Schlauch als Meide-Flächen mitgegeben wurden (0 = keine Vermeidung). */
  avoidedZones: number;
  /** true, wenn das Zonen-Limit einer Gattungs-Abfrage erreicht wurde (es gäbe noch mehr Zonen). */
  zonesCapped: boolean;
  /** Wie viele der Meide-Zonen die finale Route tatsächlich durchquert. */
  crossedZones: number;
  /** Querungen aufgeschlüsselt je Gattung (nur Gattungen mit mindestens einer Querung). */
  crossedByGenus: Record<string, number>;
}

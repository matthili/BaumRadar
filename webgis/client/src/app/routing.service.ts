import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { LonLat, RouteProfile, RouteResult } from './models';

/** Minimal-Ausschnitt der GraphHopper-Antwort (nur, was wir lesen). */
interface GhResponse {
  paths?: {
    distance: number;
    time: number;
    points: { coordinates: [number, number][] };
  }[];
  message?: string;
}

interface GeoJsonGeometry {
  type: string;
  coordinates: unknown;
}

interface GeoJsonFc {
  features?: { geometry: GeoJsonGeometry | null; properties?: { genus_de?: string } }[];
}

/** Eine Meide-Zone samt Gattung (für die Querungs-Statistik). */
interface Zone {
  geometry: GeoJsonGeometry;
  genusDe: string;
}

interface GhPath {
  coords: [number, number][];
  distanceM: number;
  timeMs: number;
}

/**
 * Routing über den lokalen GraphHopper (same-origin `/graphhopper`) mit
 * Allergiezonen-Vermeidung in zwei Pässen:
 *
 * 1. Basisroute OHNE Meidung — sie definiert den SCHLAUCH, in dem Zonen überhaupt
 *    relevant sind. (Eine einzige Start–Ziel-BBox deckte bei Diagonalen die halbe
 *    Stadt ab: Zonen 10 km abseits zählten mit und fraßen das Limit auf.)
 * 2. Zonen der gewählten Gattungen im Schlauch holen — mit Budget JE GATTUNG,
 *    denn ein gemeinsames `IN (…)`-Limit bevorzugt am Deckel die Gattung mit den
 *    "ersten" Tabellenzeilen und verschluckt andere komplett (live beobachtet:
 *    Birke+Linde ergab exakt die Birken-Route). Dann finale Route MIT Meidung.
 *
 * Zum Schluss zählt der Client, welche der Zonen die finale Route tatsächlich
 * durchquert — GraphHopper selbst meldet das nicht.
 */
@Injectable({ providedIn: 'root' })
export class RoutingService {
  private readonly http = inject(HttpClient);

  private static readonly GH_URL = '/graphhopper/route';
  private static readonly WFS_URL = '/geoserver/baumradar/wfs';
  /** Rand (Grad) der Schlauch-Segmente um die Basisroute (~400 m je Seite). */
  private static readonly TUBE_PAD_DEG = 0.004;
  /** Gesamt-Budget an Meide-Zonen je Anfrage (wird auf die Gattungen aufgeteilt). */
  private static readonly MAX_ZONES = 300;

  /**
   * @param avoidFactor Prioritätsfaktor für Kanten in Allergiezonen: jeder Meter
   *        in einer Zone „kostet" das (1/avoidFactor)-fache eines normalen Meters.
   *        Bewusst nie 0 (hart gesperrt) — so existiert immer eine Route, auch
   *        wenn der Start mitten in einer Zone liegt.
   */
  async route(
    profile: RouteProfile,
    start: LonLat,
    end: LonLat,
    avoidGenera: ReadonlySet<string>,
    avoidFactor: number,
  ): Promise<RouteResult> {
    const base = await this.ghRoute(profile, start, end);
    if (!avoidGenera.size) {
      return { ...base, avoidedZones: 0, zonesCapped: false, crossedZones: 0, crossedByGenus: {} };
    }
    const { zones, capped } = await this.zonesAlongLine(base.coords, avoidGenera);
    let path = base;
    if (zones.length) {
      path = await this.ghRoute(
        profile, start, end,
        this.avoidModel(zones.map((z) => z.geometry), avoidFactor),
      );
    }
    const crossedByGenus = RoutingService.crossings(path.coords, zones);
    const crossedZones = Object.values(crossedByGenus).reduce((a, b) => a + b, 0);
    return { ...path, avoidedZones: zones.length, zonesCapped: capped, crossedZones, crossedByGenus };
  }

  private async ghRoute(
    profile: RouteProfile,
    start: LonLat,
    end: LonLat,
    customModel?: object,
  ): Promise<GhPath> {
    const body: Record<string, unknown> = {
      profile,
      points: [
        [start.lon, start.lat],
        [end.lon, end.lat],
      ],
      points_encoded: false,
      'ch.disable': true,
    };
    if (customModel) body['custom_model'] = customModel;
    const resp = await firstValueFrom(this.http.post<GhResponse>(RoutingService.GH_URL, body));
    const path = resp.paths?.[0];
    if (!path) throw new Error(resp.message ?? 'Keine Route gefunden');
    return { coords: path.points.coordinates ?? [], distanceM: path.distance, timeMs: path.time };
  }

  /** Schlauch um die Basisroute: Segment-BBoxen statt einer Gesamt-BBox. */
  private static tubeBoxes(coords: [number, number][]): string {
    const pad = RoutingService.TUBE_PAD_DEG;
    // Max. ~14 Segmente, damit der CQL-Filter kompakt bleibt; jedes Segment
    // umschließt seinen Routen-Abschnitt eng statt die ganze Diagonale.
    const step = Math.max(1, Math.ceil((coords.length - 1) / 14));
    const parts: string[] = [];
    for (let i = 0; i < coords.length - 1; i += step) {
      const seg = coords.slice(i, Math.min(i + step, coords.length - 1) + 1);
      let minLon = seg[0][0], maxLon = seg[0][0], minLat = seg[0][1], maxLat = seg[0][1];
      for (const [lon, lat] of seg) {
        if (lon < minLon) minLon = lon;
        if (lon > maxLon) maxLon = lon;
        if (lat < minLat) minLat = lat;
        if (lat > maxLat) maxLat = lat;
      }
      // GeoServer nimmt CQL-BBOX bei 4326 sonst als lat,lon; mit explizitem
      // 'EPSG:4326' gilt lon,lat (live verifiziert).
      parts.push(
        `BBOX(geom, ${minLon - pad}, ${minLat - pad}, ${maxLon + pad}, ${maxLat + pad}, 'EPSG:4326')`,
      );
    }
    return parts.join(' OR ');
  }

  /** Zonen der gewählten Gattungen im Routen-Schlauch — eine Abfrage je Gattung. */
  private async zonesAlongLine(
    coords: [number, number][],
    genera: ReadonlySet<string>,
  ): Promise<{ zones: Zone[]; capped: boolean }> {
    const boxes = RoutingService.tubeBoxes(coords);
    const perGenus = Math.max(50, Math.floor(RoutingService.MAX_ZONES / genera.size));
    const results = await Promise.all(
      [...genera].map(async (g) => {
        const cql = `genus_de = '${g.replaceAll("'", "''")}' AND (${boxes})`;
        const params = new HttpParams({
          fromObject: {
            service: 'WFS',
            version: '2.0.0',
            request: 'GetFeature',
            typeNames: 'baumradar:allergy_zones',
            outputFormat: 'application/json',
            srsName: 'EPSG:4326',
            count: String(perGenus),
            CQL_FILTER: cql,
          },
        });
        const fc = await firstValueFrom(this.http.get<GeoJsonFc>(RoutingService.WFS_URL, { params }));
        const zones = (fc.features ?? [])
          .filter((f) => !!f.geometry)
          .map((f) => ({ geometry: f.geometry as GeoJsonGeometry, genusDe: f.properties?.genus_de ?? g }));
        return { zones, capped: zones.length >= perGenus };
      }),
    );
    return { zones: results.flatMap((r) => r.zones), capped: results.some((r) => r.capped) };
  }

  /**
   * Custom-Model: alle Schlauch-Zonen zu EINEM MultiPolygon gebündelt (eine Area,
   * eine Bedingung `in_avoid`) statt N Einzel-Areas — deutlich schlankerer Request.
   * Die Zonen sind Polygone (PostGIS-Schema); ein evtl. MultiPolygon wird flach übernommen.
   */
  private avoidModel(zoneGeoms: GeoJsonGeometry[], avoidFactor: number): object {
    const polygons: unknown[] = [];
    for (const g of zoneGeoms) {
      if (g.type === 'Polygon') polygons.push(g.coordinates);
      else if (g.type === 'MultiPolygon') polygons.push(...(g.coordinates as unknown[]));
    }
    return {
      priority: [{ if: 'in_avoid', multiply_by: String(avoidFactor) }],
      areas: {
        type: 'FeatureCollection',
        features: [
          {
            type: 'Feature',
            id: 'avoid',
            properties: {},
            geometry: { type: 'MultiPolygon', coordinates: polygons },
          },
        ],
      },
    };
  }

  /**
   * Zählt je Gattung die Zonen, die die Route tatsächlich durchquert: die Linie
   * wird in ~25-m-Schritten abgetastet, Punkt-in-Polygon per Ray-Casting auf dem
   * Außenring (die Zonen sind gepufferte Kreise ohne Löcher). Jede Zone zählt
   * höchstens einmal. GraphHopper liefert diese Information nicht selbst.
   */
  private static crossings(coords: [number, number][], zones: Zone[]): Record<string, number> {
    const byGenus: Record<string, number> = {};
    if (!zones.length || coords.length < 2) return byGenus;
    const hit = new Set<number>();
    const stepDeg = 0.00025; // ≈ 25 m
    for (let s = 0; s < coords.length - 1; s++) {
      const [x1, y1] = coords[s];
      const [x2, y2] = coords[s + 1];
      const n = Math.max(1, Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)) / stepDeg));
      for (let k = 0; k <= n; k++) {
        const x = x1 + ((x2 - x1) * k) / n;
        const y = y1 + ((y2 - y1) * k) / n;
        for (let z = 0; z < zones.length; z++) {
          if (!hit.has(z) && RoutingService.inZone(x, y, zones[z].geometry)) hit.add(z);
        }
      }
    }
    for (const z of hit) {
      const g = zones[z].genusDe;
      byGenus[g] = (byGenus[g] ?? 0) + 1;
    }
    return byGenus;
  }

  private static inZone(x: number, y: number, g: GeoJsonGeometry): boolean {
    if (g.type === 'Polygon') {
      return RoutingService.inRing(x, y, (g.coordinates as number[][][])[0]);
    }
    if (g.type === 'MultiPolygon') {
      return (g.coordinates as number[][][][]).some((p) => RoutingService.inRing(x, y, p[0]));
    }
    return false;
  }

  /** Ray-Casting (gerade/ungerade Kantenkreuzungen) auf einem Polygon-Außenring. */
  private static inRing(x: number, y: number, ring: number[][]): boolean {
    let inside = false;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const [xi, yi] = ring[i];
      const [xj, yj] = ring[j];
      if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
    }
    return inside;
  }
}

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
  features?: { geometry: GeoJsonGeometry | null }[];
}

/**
 * Routing über den lokalen GraphHopper (same-origin `/graphhopper`) mit
 * Allergiezonen-Vermeidung nach Ansatz A′: pro Anfrage nur die Zonen im
 * Start–Ziel-Korridor der gewählten Gattungen holen (WFS-CQL) und als
 * Custom-Model-Block-Areas mitschicken — kein globales Vor-Aggregieren.
 */
@Injectable({ providedIn: 'root' })
export class RoutingService {
  private readonly http = inject(HttpClient);

  private static readonly GH_URL = '/graphhopper/route';
  private static readonly WFS_URL = '/geoserver/baumradar/wfs';
  /** Puffer (Grad) um die Start–Ziel-BBox, in dem Zonen als Hindernis zählen (~1 km). */
  private static readonly CORRIDOR_BUFFER_DEG = 0.012;
  /** Obergrenze der Zonen je Anfrage (deckelt dichte Innenstädte). */
  private static readonly MAX_ZONES = 300;

  /**
   * @param avoidFactor Prioritätsfaktor für Kanten in Allergiezonen: jeder Meter
   *        in einer Zone „kostet" das (1/avoidFactor)-fache eines normalen Meters.
   *        0,05 = Umwege bis zum ~20-Fachen des Zonenabschnitts werden bevorzugt.
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
    const zones = avoidGenera.size ? await this.corridorZones(start, end, avoidGenera) : [];
    const body: Record<string, unknown> = {
      profile,
      points: [
        [start.lon, start.lat],
        [end.lon, end.lat],
      ],
      points_encoded: false,
      'ch.disable': true,
    };
    if (zones.length) body['custom_model'] = this.avoidModel(zones, avoidFactor);

    const resp = await firstValueFrom(this.http.post<GhResponse>(RoutingService.GH_URL, body));
    const path = resp.paths?.[0];
    if (!path) throw new Error(resp.message ?? 'Keine Route gefunden');
    return {
      coords: path.points.coordinates ?? [],
      distanceM: path.distance,
      timeMs: path.time,
      avoidedZones: zones.length,
      zonesCapped: zones.length >= RoutingService.MAX_ZONES,
    };
  }

  /** Zonen der gewählten Gattungen im Start–Ziel-Korridor (WFS GetFeature, GeoJSON in WGS84). */
  private async corridorZones(
    start: LonLat,
    end: LonLat,
    genera: ReadonlySet<string>,
  ): Promise<GeoJsonGeometry[]> {
    const b = RoutingService.CORRIDOR_BUFFER_DEG;
    const minLon = Math.min(start.lon, end.lon) - b;
    const maxLon = Math.max(start.lon, end.lon) + b;
    const minLat = Math.min(start.lat, end.lat) - b;
    const maxLat = Math.max(start.lat, end.lat) + b;
    const inList = [...genera].map((g) => `'${g.replaceAll("'", "''")}'`).join(',');
    // GeoServer nimmt CQL-BBOX bei 4326 sonst als lat,lon; mit explizitem 'EPSG:4326'
    // gilt lon,lat (live verifiziert). Die Ausgabe-Geometrie ist ohnehin lon,lat.
    const cql =
      `genus_de IN (${inList}) AND BBOX(geom, ${minLon}, ${minLat}, ${maxLon}, ${maxLat}, 'EPSG:4326')`;
    const params = new HttpParams({
      fromObject: {
        service: 'WFS',
        version: '2.0.0',
        request: 'GetFeature',
        typeNames: 'baumradar:allergy_zones',
        outputFormat: 'application/json',
        srsName: 'EPSG:4326',
        count: String(RoutingService.MAX_ZONES),
        CQL_FILTER: cql,
      },
    });
    const fc = await firstValueFrom(this.http.get<GeoJsonFc>(RoutingService.WFS_URL, { params }));
    return (fc.features ?? [])
      .map((f) => f.geometry)
      .filter((g): g is GeoJsonGeometry => !!g);
  }

  /**
   * Custom-Model: alle Korridor-Zonen zu EINEM MultiPolygon gebündelt (eine Area,
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
}

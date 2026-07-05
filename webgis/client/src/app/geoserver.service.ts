import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { GenusStat, SpeciesStat } from './models';

/**
 * Zugriffe auf die OGC-Dienste des GeoServers.
 *
 * Alle URLs sind relativ (`/geoserver/...`): im Dev-Modus proxied `ng serve`
 * (proxy.conf.json), im Container nginx — dadurch gibt es kein CORS-Thema.
 */
@Injectable({ providedIn: 'root' })
export class GeoServerService {
  private readonly http = inject(HttpClient);

  static readonly WMS_URL = '/geoserver/baumradar/wms';
  private static readonly WFS_URL = '/geoserver/baumradar/wfs';

  /** Gattungsliste (Filter-UI) aus baumradar:genus_stats, absteigend nach Baumzahl. */
  async fetchGenera(): Promise<GenusStat[]> {
    const params = new HttpParams({
      fromObject: {
        service: 'WFS',
        version: '2.0.0',
        request: 'GetFeature',
        typeNames: 'baumradar:genus_stats',
        outputFormat: 'application/json',
        count: '500',
      },
    });
    interface Fc {
      features: {
        properties: { genus_de: string; genus_en: string | null; tree_count: number };
      }[];
    }
    const fc = await firstValueFrom(
      this.http.get<Fc>(GeoServerService.WFS_URL, { params }),
    );
    return fc.features
      .map((f) => ({
        genusDe: f.properties.genus_de,
        genusEn: f.properties.genus_en ?? null,
        treeCount: f.properties.tree_count,
      }))
      .sort((a, b) => b.treeCount - a.treeCount);
  }

  /** Alle Art-Tupel (~8k Zeilen, einmalig) — Grundlage der Client-Suche. */
  async fetchSpecies(): Promise<SpeciesStat[]> {
    const params = new HttpParams({
      fromObject: {
        service: 'WFS',
        version: '2.0.0',
        request: 'GetFeature',
        typeNames: 'baumradar:species_stats',
        outputFormat: 'application/json',
        count: '20000',
      },
    });
    interface Fc {
      features: {
        properties: {
          genus_de: string;
          species_de: string;
          species_en: string;
          tree_count: number;
        };
      }[];
    }
    const fc = await firstValueFrom(
      this.http.get<Fc>(GeoServerService.WFS_URL, { params }),
    );
    return fc.features.map((f) => ({
      genusDe: f.properties.genus_de,
      speciesDe: f.properties.species_de ?? '',
      speciesEn: f.properties.species_en ?? '',
      treeCount: f.properties.tree_count,
    }));
  }

  /** Gattungs-Statistik JE STADT (baumradar:genus_stats_city) → {@code cityId → GenusStat[]}. */
  async fetchGeneraByCity(): Promise<Map<string, GenusStat[]>> {
    const params = new HttpParams({
      fromObject: {
        service: 'WFS',
        version: '2.0.0',
        request: 'GetFeature',
        typeNames: 'baumradar:genus_stats_city',
        outputFormat: 'application/json',
        count: '10000',
      },
    });
    interface Fc {
      features: {
        properties: { city_id: string; genus_de: string; genus_en: string | null; tree_count: number };
      }[];
    }
    const fc = await firstValueFrom(this.http.get<Fc>(GeoServerService.WFS_URL, { params }));
    const map = new Map<string, GenusStat[]>();
    for (const f of fc.features) {
      const p = f.properties;
      const list = map.get(p.city_id) ?? [];
      list.push({ genusDe: p.genus_de, genusEn: p.genus_en ?? null, treeCount: p.tree_count });
      map.set(p.city_id, list);
    }
    for (const list of map.values()) list.sort((a, b) => b.treeCount - a.treeCount);
    return map;
  }

  /**
   * CQL-Filter für eine Gattungs-Auswahl; `null` = keine Einschränkung.
   * Einfache Anführungszeichen werden CQL-konform verdoppelt.
   */
  static genusCql(selected: ReadonlySet<string>): string | null {
    if (selected.size === 0) return null;
    const quoted = [...selected]
      .map((g) => `'${g.replaceAll("'", "''")}'`)
      .join(',');
    return `genus_de IN (${quoted})`;
  }

  /** CQL-Filter für eine einzelne Stadt; `null` = keine Einschränkung. */
  static cityCql(cityId: string | null): string | null {
    return cityId ? `city_id = '${cityId.replaceAll("'", "''")}'` : null;
  }

  /** Verknüpft CQL-Teilfilter mit AND; `INCLUDE` (Neutralelement), wenn keiner aktiv ist. */
  static combineCql(...parts: (string | null)[]): string {
    const active = parts.filter((p): p is string => !!p);
    return active.length ? active.join(' AND ') : 'INCLUDE';
  }
}

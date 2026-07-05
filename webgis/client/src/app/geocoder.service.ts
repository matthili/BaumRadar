import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { GeocodeHit } from './models';

/** Minimal-Ausschnitt der Photon-Antwort (GeoJSON FeatureCollection). */
interface PhotonFc {
  features?: {
    properties: {
      name?: string;
      street?: string;
      housenumber?: string;
      city?: string;
      postcode?: string;
      osm_value?: string;
    };
    geometry: { coordinates: [number, number] };
  }[];
}

/**
 * Adress- & Ortssuche über Photon — bevorzugt die <b>lokale</b> Instanz
 * (Compose-Profil {@code geocoding}, gespeist aus den pro-Stadt-Häppchen des
 * BaumRadar-Katalogs, same-origin über {@code /photon/}). Antwortet die lokale
 * Instanz nicht, fällt die Suche dauerhaft auf die öffentliche Instanz
 * {@code photon.komoot.io} zurück — {@link #online} macht das im UI transparent
 * (Anfragen verlassen dann den Rechner).
 */
@Injectable({ providedIn: 'root' })
export class GeocoderService {
  private readonly http = inject(HttpClient);

  private static readonly LOCAL_URL = '/photon/api';
  private static readonly ONLINE_URL = 'https://photon.komoot.io/api';

  /** {@code true}, solange die lokale Instanz nicht erreichbar ist (→ Komoot-Fallback). */
  readonly online = signal(false);

  /** Zeitpunkt des letzten lokalen Fehlschlags — danach wird periodisch neu probiert. */
  private lastLocalFailure = 0;

  /** Nach dieser Zeit wird die lokale Instanz erneut versucht (sie könnte z. B.
   *  gerade erst ihren Index importieren und kurz darauf verfügbar sein). */
  private static readonly LOCAL_RETRY_MS = 30_000;

  /**
   * Suche mit Autocomplete-Charakteristik. {@code bbox} (optional, WGS84
   * {@code [minLat, minLon, maxLat, maxLon]} — Katalog-Reihenfolge) begrenzt
   * die Treffer auf die gewählte Stadt.
   */
  async search(query: string, bbox: [number, number, number, number] | null): Promise<GeocodeHit[]> {
    let params = new HttpParams()
      .set('q', query)
      .set('limit', '6')
      .set('lang', 'de');
    if (bbox) {
      // Photon erwartet bbox=minLon,minLat,maxLon,maxLat.
      params = params.set('bbox', `${bbox[1]},${bbox[0]},${bbox[3]},${bbox[2]}`);
    }

    // Lokal zuerst — und nach einem Fehlschlag nicht dauerhaft aufgeben: die lokale
    // Instanz importiert beim allerersten Start minutenlang ihren Index und wird
    // danach automatisch wieder übernommen (Banner verschwindet von selbst).
    const retryLocal = !this.online()
      || Date.now() - this.lastLocalFailure > GeocoderService.LOCAL_RETRY_MS;
    if (retryLocal) {
      try {
        const fc = await firstValueFrom(
          this.http.get<PhotonFc>(GeocoderService.LOCAL_URL, { params }),
        );
        this.online.set(false);
        return this.toHits(fc);
      } catch {
        this.lastLocalFailure = Date.now();
        this.online.set(true);
      }
    }
    const fc = await firstValueFrom(
      this.http.get<PhotonFc>(GeocoderService.ONLINE_URL, { params }),
    );
    return this.toHits(fc);
  }

  private toHits(fc: PhotonFc): GeocodeHit[] {
    return (fc.features ?? []).map((f) => {
      const p = f.properties;
      const main = p.name ?? [p.street, p.housenumber].filter(Boolean).join(' ');
      const context = [
        p.name && p.street ? [p.street, p.housenumber].filter(Boolean).join(' ') : null,
        p.postcode && p.city ? `${p.postcode} ${p.city}` : (p.city ?? null),
      ].filter(Boolean).join(', ');
      return {
        label: context ? `${main} · ${context}` : main,
        lon: f.geometry.coordinates[0],
        lat: f.geometry.coordinates[1],
      };
    }).filter((h) => h.label.length > 1);
  }
}

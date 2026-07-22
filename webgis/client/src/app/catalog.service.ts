import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Catalog, City } from './models';

/**
 * Lädt den Stadtkatalog von GitHub Pages — exakt dieselbe signierte Quelle,
 * die auch die Android-App nutzt (raw.githubusercontent liefert CORS `*`).
 */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);

  private static readonly CATALOG_URL =
    'https://raw.githubusercontent.com/matthili/BaumRadar/master/docs/data/catalog.json';

  async fetchCities(): Promise<City[]> {
    // Cache-Buster wie in der App: raw.githubusercontent cached aggressiv.
    const url = `${CatalogService.CATALOG_URL}?t=${Date.now()}`;
    const catalog = await firstValueFrom(this.http.get<Catalog>(url));
    return CatalogService.sortByName(catalog.cities);
  }

  /**
   * Katalog-Reihenfolge = Provider-Registrierung; fürs Dropdown alphabetisch.
   * de-Locale, damit Umlaute wie Basisbuchstaben einsortiert werden
   * (Köln vor Konstanz — naiv nach Codepoints läge „ö" hinter „z").
   */
  static sortByName(cities: readonly City[]): City[] {
    return [...cities].sort((a, b) => a.name.localeCompare(b.name, 'de'));
  }
}

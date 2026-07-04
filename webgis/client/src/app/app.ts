import {
  AfterViewInit,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { CatalogService } from './catalog.service';
import { GeoServerService } from './geoserver.service';
import { MapService } from './map.service';
import { City, GenusStat, PopupData, SpeciesStat } from './models';
import { matchGenera } from './search';

/**
 * BaumRadar WebGIS — Karten-Shell.
 *
 * Der gesamte UI-Zustand lebt in Signals; die OpenLayers-Karte selbst gehört
 * dem {@link MapService} (außerhalb der Angular-Welt). Drei `effect`s
 * propagieren Zustandsänderungen (Gattungsfilter, Layer-Sichtbarkeit) als
 * imperative Aufrufe an die Karte — die Gegenrichtung (Klick-Treffer,
 * GPX-Drop) kommt über Callbacks zurück in Signals.
 */
@Component({
  selector: 'app-root',
  imports: [],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements AfterViewInit {
  private readonly mapService = inject(MapService);
  private readonly geoserver = inject(GeoServerService);
  private readonly catalog = inject(CatalogService);

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');
  private readonly popupHost = viewChild.required<ElementRef<HTMLDivElement>>('popupHost');

  readonly cities = signal<City[]>([]);
  readonly genera = signal<GenusStat[]>([]);
  readonly species = signal<SpeciesStat[]>([]);
  readonly generaLoading = signal(true);
  readonly genusQuery = signal('');
  readonly selectedGenera = signal<ReadonlySet<string>>(new Set<string>());
  readonly showTrees = signal(true);
  readonly showZones = signal(true);
  readonly popup = signal<PopupData | null>(null);
  readonly routeName = signal<string | null>(null);
  /** Aufgeklappte Gattungen (zeigen ihre Arten-Liste wie im App-Profil). */
  readonly expandedGenera = signal<ReadonlySet<string>>(new Set<string>());

  /** Art-Tupel je Gattung, je Gattung absteigend nach Häufigkeit — so nennt
   *  „gefunden über" die geläufigste passende Art, nicht ein seltenes Kultivar. */
  private readonly speciesByGenus = computed(() => {
    const map = new Map<string, SpeciesStat[]>();
    for (const s of this.species()) {
      const list = map.get(s.genusDe);
      if (list) {
        list.push(s);
      } else {
        map.set(s.genusDe, [s]);
      }
    }
    for (const list of map.values()) {
      list.sort((a, b) => b.treeCount - a.treeCount);
    }
    return map;
  });

  readonly filteredGenera = computed(() =>
    matchGenera(this.genusQuery(), this.genera(), this.speciesByGenus()),
  );

  constructor() {
    // Start-Zustand aus der URL: ?q=<Suchbegriff> und ?open=<Gattung,...>
    // machen Such- und Aufklapp-Zustand als Link teilbar.
    const urlParams = new URLSearchParams(globalThis.location?.search ?? '');
    const initialQuery = urlParams.get('q');
    if (initialQuery) {
      this.genusQuery.set(initialQuery);
    }
    const initialOpen = urlParams.get('open');
    if (initialOpen) {
      this.expandedGenera.set(new Set(initialOpen.split(',').map((s) => s.trim())));
    }
    // Zustands-Änderungen an die (Angular-fremde) Karte durchreichen.
    // Vor createMap() sind die MapService-Methoden No-ops (optional chaining).
    effect(() =>
      this.mapService.setGenusFilter(GeoServerService.genusCql(this.selectedGenera())),
    );
    effect(() => this.mapService.setTreesVisible(this.showTrees()));
    effect(() => this.mapService.setZonesVisible(this.showZones()));
  }

  ngAfterViewInit(): void {
    this.mapService.createMap(
      this.mapHost().nativeElement,
      this.popupHost().nativeElement,
      (data) => this.popup.set(data),
      (name) => this.routeName.set(name),
    );
    void this.loadData();
  }

  private async loadData(): Promise<void> {
    try {
      const [cities, genera] = await Promise.all([
        this.catalog.fetchCities(),
        this.geoserver.fetchGenera(),
      ]);
      this.cities.set(cities);
      this.genera.set(genera);
      // Getrennt geladen: fällt die Art-Statistik aus, degradiert die Suche
      // schlicht auf Gattungsnamen statt die ganze Liste zu blockieren.
      try {
        this.species.set(await this.geoserver.fetchSpecies());
      } catch (err) {
        console.warn('Art-Statistik nicht verfügbar — Suche nur über Gattungen', err);
      }
    } catch (err) {
      console.error('Stammdaten konnten nicht geladen werden', err);
    } finally {
      this.generaLoading.set(false);
    }
  }

  toggleGenus(genus: string): void {
    const next = new Set(this.selectedGenera());
    if (next.has(genus)) {
      next.delete(genus);
    } else {
      next.add(genus);
    }
    this.selectedGenera.set(next);
  }

  clearGenera(): void {
    this.selectedGenera.set(new Set<string>());
  }

  toggleExpand(genus: string): void {
    const next = new Set(this.expandedGenera());
    if (next.has(genus)) {
      next.delete(genus);
    } else {
      next.add(genus);
    }
    this.expandedGenera.set(next);
  }

  /** Arten-Liste einer Gattung (absteigend nach Häufigkeit; leer, bis geladen). */
  speciesFor(genusDe: string): SpeciesStat[] {
    return this.speciesByGenus().get(genusDe) ?? [];
  }

  jumpToCity(id: string): void {
    const city = this.cities().find((c) => c.id === id);
    if (city) {
      this.mapService.fitCity(city.boundingBox);
    }
  }

  closePopup(): void {
    this.popup.set(null);
    this.mapService.hidePopup();
  }

  clearRoute(): void {
    this.routeName.set(null);
    this.mapService.clearRoute();
  }

  fmt(n: number): string {
    return n.toLocaleString('de-AT');
  }
}

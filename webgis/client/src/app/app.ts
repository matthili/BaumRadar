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
import { City, GenusStat, LonLat, PopupData, RouteProfile, RouteResult, SpeciesStat } from './models';
import { RoutingService } from './routing.service';
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
  private readonly routingService = inject(RoutingService);

  private readonly mapHost = viewChild.required<ElementRef<HTMLDivElement>>('mapHost');
  private readonly popupHost = viewChild.required<ElementRef<HTMLDivElement>>('popupHost');

  readonly cities = signal<City[]>([]);
  readonly generaGlobal = signal<GenusStat[]>([]);
  readonly generaByCity = signal<ReadonlyMap<string, GenusStat[]>>(new Map());
  readonly selectedCity = signal<string | null>(null);
  readonly species = signal<SpeciesStat[]>([]);
  readonly generaLoading = signal(true);
  readonly genusQuery = signal('');
  readonly selectedGenera = signal<ReadonlySet<string>>(new Set<string>());
  readonly showTrees = signal(true);
  readonly showZones = signal(true);
  readonly popup = signal<PopupData | null>(null);
  readonly routeName = signal<string | null>(null);

  // Routing (Phase 4) — Start/Ziel per Karten-Klick, Vermeidung nutzt selectedGenera.
  readonly routingActive = signal(false);
  readonly routeProfile = signal<RouteProfile>('foot');
  readonly routeStart = signal<LonLat | null>(null);
  readonly routeEnd = signal<LonLat | null>(null);
  readonly routeInfo = signal<RouteResult | null>(null);
  readonly routing = signal(false);
  readonly routeError = signal<string | null>(null);
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

  /** Angezeigte Gattungen: die der gewählten Stadt, sonst global über alle Städte. */
  readonly genera = computed(() => {
    const city = this.selectedCity();
    return city ? (this.generaByCity().get(city) ?? []) : this.generaGlobal();
  });

  readonly selectedCityName = computed(() => {
    const id = this.selectedCity();
    return id ? (this.cities().find((c) => c.id === id)?.name ?? null) : null;
  });

  readonly filteredGenera = computed(() =>
    matchGenera(this.genusQuery(), this.genera(), this.speciesByGenus()),
  );

  readonly routingHint = computed(() => {
    if (!this.routeStart()) return 'Startpunkt auf die Karte klicken.';
    if (!this.routeEnd()) return 'Zielpunkt auf die Karte klicken.';
    return this.routing() ? 'Berechne Route …' : 'Neuer Klick startet eine neue Route.';
  });

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
      this.mapService.setLayerFilter(
        GeoServerService.combineCql(
          GeoServerService.genusCql(this.selectedGenera()),
          GeoServerService.cityCql(this.selectedCity()),
        ),
      ),
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
      const [cities, genera, generaByCity] = await Promise.all([
        this.catalog.fetchCities(),
        this.geoserver.fetchGenera(),
        this.geoserver.fetchGeneraByCity(),
      ]);
      this.cities.set(cities);
      this.generaGlobal.set(genera);
      this.generaByCity.set(generaByCity);
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

  /** Stadt wählen: scopt Zahlen + Karte auf diese Stadt; leer = alle Städte. */
  selectCity(id: string): void {
    this.selectedCity.set(id || null);
    const city = this.cities().find((c) => c.id === id);
    if (city) this.mapService.fitCity(city.boundingBox);
  }

  closePopup(): void {
    this.popup.set(null);
    this.mapService.hidePopup();
  }

  clearRoute(): void {
    this.routeName.set(null);
    this.mapService.clearRoute();
  }

  toggleRouting(): void {
    const on = !this.routingActive();
    this.routingActive.set(on);
    if (on) {
      this.mapService.enableRouting(true, (p) => this.addRoutingPoint(p));
    } else {
      this.mapService.enableRouting(false, () => undefined);
      this.resetRouting();
      this.mapService.clearRouting();
    }
  }

  setProfile(p: RouteProfile): void {
    if (p === this.routeProfile()) return;
    this.routeProfile.set(p);
    if (this.routeStart() && this.routeEnd()) void this.computeRoute();
  }

  clearRoutingUi(): void {
    this.resetRouting();
    this.mapService.clearRouting();
  }

  /** 1. Klick = Start, 2. = Ziel (dann berechnen); ein weiterer Klick startet neu. */
  private addRoutingPoint(p: LonLat): void {
    const start = this.routeStart();
    const end = this.routeEnd();
    if (!start || (start && end)) {
      this.mapService.clearRouting();
      this.routeStart.set(p);
      this.routeEnd.set(null);
      this.routeInfo.set(null);
      this.routeError.set(null);
      this.mapService.setRouteMarkers(p, null);
    } else {
      this.routeEnd.set(p);
      this.mapService.setRouteMarkers(start, p);
      void this.computeRoute();
    }
  }

  private async computeRoute(): Promise<void> {
    const start = this.routeStart();
    const end = this.routeEnd();
    if (!start || !end) return;
    this.routing.set(true);
    this.routeError.set(null);
    try {
      const result = await this.routingService.route(
        this.routeProfile(),
        start,
        end,
        this.selectedGenera(),
      );
      this.routeInfo.set(result);
      this.mapService.drawRoute(result.coords);
    } catch (err) {
      this.routeError.set('Route konnte nicht berechnet werden.');
      console.error('Routing fehlgeschlagen', err);
    } finally {
      this.routing.set(false);
    }
  }

  private resetRouting(): void {
    this.routeStart.set(null);
    this.routeEnd.set(null);
    this.routeInfo.set(null);
    this.routeError.set(null);
  }

  fmtDistance(m: number): string {
    return m >= 1000 ? (m / 1000).toFixed(1) + ' km' : Math.round(m) + ' m';
  }

  fmtDuration(ms: number): string {
    const min = Math.round(ms / 60000);
    if (min < 60) return `${min} min`;
    return `${Math.floor(min / 60)} h ${min % 60} min`;
  }

  fmt(n: number): string {
    return n.toLocaleString('de-AT');
  }
}

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
import { GeocoderService } from './geocoder.service';
import { City, GenusStat, GeocodeHit, LonLat, PopupData, RouteProfile, RouteResult, SpeciesStat } from './models';
import { RoutingService } from './routing.service';
import { StatusService } from './status.service';
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
  private readonly geocoder = inject(GeocoderService);
  /** Lade-Status des Stacks (Ring ums Logo + Hover-Overlay). */
  readonly status = inject(StatusService);

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
  /** Zonen-Meidefaktor: 0,05 = „lieber bis zu 20-facher Umweg als eine Zone queren". */
  readonly routeAvoidFactor = signal(0.05);
  readonly routeStart = signal<LonLat | null>(null);
  readonly routeEnd = signal<LonLat | null>(null);
  readonly routeInfo = signal<RouteResult | null>(null);
  readonly routing = signal(false);
  readonly routeError = signal<string | null>(null);

  // Adress-/Ortssuche für Start & Ziel (Photon; lokal mit Online-Fallback).
  readonly startQuery = signal('');
  readonly endQuery = signal('');
  readonly startHits = signal<GeocodeHit[]>([]);
  readonly endHits = signal<GeocodeHit[]>([]);
  readonly geocoderOnline = this.geocoder.online;
  private startTimer?: ReturnType<typeof setTimeout>;
  private endTimer?: ReturnType<typeof setTimeout>;
  private rerouteTimer?: ReturnType<typeof setTimeout>;
  /** Laufnummer der Routen-Anfragen: nur die Antwort auf die JÜNGSTE zählt. */
  private routeSeq = 0;
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
    if (!this.routeStart()) return 'Start: Adresse suchen oder auf die Karte klicken.';
    if (!this.routeEnd()) return 'Ziel: Adresse suchen oder auf die Karte klicken.';
    return this.routing()
      ? 'Berechne Route …'
      : 'Gattungs- oder Umweg-Änderungen berechnen die Route automatisch neu. '
        + 'Ein Kartenklick beginnt eine NEUE Route (setzt den Start).';
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
    this.status.start();
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
    this.scheduleReroute();
  }

  clearGenera(): void {
    this.selectedGenera.set(new Set<string>());
    this.scheduleReroute();
  }

  /**
   * Gattungs-Änderungen berechnen eine bestehende Route automatisch neu — die
   * Zonenauswahl ist Teil der Routenfrage. Entprellt, damit schnelles Durchklicken
   * mehrerer Gattungen nur EINE Anfrage auslöst.
   */
  private scheduleReroute(): void {
    if (!this.routingActive() || !this.routeStart() || !this.routeEnd()) return;
    clearTimeout(this.rerouteTimer);
    this.rerouteTimer = setTimeout(() => void this.computeRoute(), 400);
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

  // --- Adress-/Ortssuche (Start & Ziel) ------------------------------------

  onStartQuery(value: string): void {
    this.startQuery.set(value);
    clearTimeout(this.startTimer);
    if (value.trim().length < 2) {
      this.startHits.set([]);
      return;
    }
    this.startTimer = setTimeout(() => void this.searchFor('start', value.trim()), 300);
  }

  onEndQuery(value: string): void {
    this.endQuery.set(value);
    clearTimeout(this.endTimer);
    if (value.trim().length < 2) {
      this.endHits.set([]);
      return;
    }
    this.endTimer = setTimeout(() => void this.searchFor('end', value.trim()), 300);
  }

  pickStart(hit: GeocodeHit): void {
    this.startQuery.set(hit.label);
    this.startHits.set([]);
    this.routeStart.set({ lon: hit.lon, lat: hit.lat });
    this.afterPointChosen();
  }

  pickEnd(hit: GeocodeHit): void {
    this.endQuery.set(hit.label);
    this.endHits.set([]);
    this.routeEnd.set({ lon: hit.lon, lat: hit.lat });
    this.afterPointChosen();
  }

  /** Marker aktualisieren und rechnen, sobald Start und Ziel beisammen sind. */
  private afterPointChosen(): void {
    this.routeInfo.set(null);
    this.routeError.set(null);
    this.mapService.setRouteMarkers(this.routeStart(), this.routeEnd());
    if (this.routeStart() && this.routeEnd()) {
      void this.computeRoute();
    }
  }

  private async searchFor(field: 'start' | 'end', query: string): Promise<void> {
    try {
      const cityId = this.selectedCity();
      const bbox = cityId
        ? (this.cities().find((c) => c.id === cityId)?.boundingBox ?? null)
        : null;
      const hits = await this.geocoder.search(query, bbox);
      (field === 'start' ? this.startHits : this.endHits).set(hits);
    } catch {
      (field === 'start' ? this.startHits : this.endHits).set([]);
    }
  }

  setProfile(p: RouteProfile): void {
    if (p === this.routeProfile()) return;
    this.routeProfile.set(p);
    if (this.routeStart() && this.routeEnd()) void this.computeRoute();
  }

  /** Meidefaktor aus dem Dropdown übernehmen und ggf. sofort neu rechnen. */
  setAvoidFactor(value: string): void {
    const factor = Number(value);
    if (!factor || factor === this.routeAvoidFactor()) return;
    this.routeAvoidFactor.set(factor);
    if (this.routeStart() && this.routeEnd()) void this.computeRoute();
  }

  clearRoutingUi(): void {
    this.resetRouting();
    this.mapService.clearRouting();
  }

  /**
   * 1. Klick = Start, 2. = Ziel (dann berechnen); ein weiterer Klick startet neu.
   * Die Klickpunkte tragen sich in die Suchfelder ein — Felder und Route zeigen
   * damit IMMER denselben Zustand (vorher blieben alte Adressen stehen, während
   * längst zwischen Klickpunkten geroutet wurde).
   */
  private addRoutingPoint(p: LonLat): void {
    const start = this.routeStart();
    const end = this.routeEnd();
    if (!start || (start && end)) {
      this.mapService.clearRouting();
      this.routeStart.set(p);
      this.routeEnd.set(null);
      this.routeInfo.set(null);
      this.routeError.set(null);
      this.startQuery.set(App.fmtPoint(p));
      this.endQuery.set('');
      this.startHits.set([]);
      this.endHits.set([]);
      this.mapService.setRouteMarkers(p, null);
    } else {
      this.routeEnd.set(p);
      this.endQuery.set(App.fmtPoint(p));
      this.endHits.set([]);
      this.mapService.setRouteMarkers(start, p);
      void this.computeRoute();
    }
  }

  /** Kartenklick als lesbarer Feldinhalt, z. B. „Kartenpunkt 48,2094 / 16,3831". */
  private static fmtPoint(p: LonLat): string {
    const f = (n: number) => n.toFixed(4).replace('.', ',');
    return `Kartenpunkt ${f(p.lat)} / ${f(p.lon)}`;
  }

  private async computeRoute(): Promise<void> {
    const start = this.routeStart();
    const end = this.routeEnd();
    if (!start || !end) return;
    // Schnelle Folge-Änderungen (Gattung an/aus, Faktor) können sich überholen —
    // eine verspätete ältere Antwort darf die jüngere nicht überschreiben.
    const seq = ++this.routeSeq;
    this.routing.set(true);
    this.routeError.set(null);
    try {
      const result = await this.routingService.route(
        this.routeProfile(),
        start,
        end,
        this.selectedGenera(),
        this.routeAvoidFactor(),
      );
      if (seq !== this.routeSeq) return;
      this.routeInfo.set(result);
      this.mapService.drawRoute(result.coords);
    } catch (err) {
      if (seq !== this.routeSeq) return;
      // Unterscheiden: Dienst gar nicht erreichbar (Profil »routing« aus oder
      // Graph baut noch) vs. GraphHopper hat geantwortet, findet aber keine Route.
      const status = (err as { status?: number }).status;
      const serviceDown = status === 0 || status === 404 || status === 502
        || status === 503 || status === 504;
      this.routeError.set(serviceDown
        ? 'Routing-Dienst nicht erreichbar — er ist Standard beim Start-Skript '
          + '(beim allerersten Start dauert der Graph-Aufbau einige Minuten).'
        : 'Keine Route gefunden — liegen Start und Ziel im Gebiet derselben Stadt?');
      console.error('Routing fehlgeschlagen', err);
    } finally {
      if (seq === this.routeSeq) this.routing.set(false);
    }
  }

  private resetRouting(): void {
    this.routeStart.set(null);
    this.routeEnd.set(null);
    this.routeInfo.set(null);
    this.routeError.set(null);
    this.startQuery.set('');
    this.endQuery.set('');
    this.startHits.set([]);
    this.endHits.set([]);
  }

  /** Tooltip der Querungs-Anzeige: Aufschlüsselung je Gattung. */
  crossedTitle(ri: RouteResult): string {
    if (ri.crossedZones === 0) {
      return 'Die Route durchquert keine der berücksichtigten Allergiezonen.';
    }
    const parts = Object.entries(ri.crossedByGenus)
      .sort((a, b) => b[1] - a[1])
      .map(([g, n]) => `${g}: ${n}`);
    return `Die Route durchquert Zonen von: ${parts.join(' · ')}`;
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

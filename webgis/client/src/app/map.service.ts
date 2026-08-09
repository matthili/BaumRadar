import { Injectable, NgZone, inject, signal } from '@angular/core';
import { EngineKind, MapCallbacks, MapEngine, ViewState } from './map-engine';
import { LonLat } from './models';

/**
 * Fassade über den beiden Karten-Motoren (Schalter „Ansicht"):
 * OpenLayers (serverseitig gerenderte WMS-Bilder) und MapLibre (Vektorkacheln,
 * im Browser gerendert). Die App spricht nur mit dieser Klasse; beim Wechsel
 * wird der Motor lazy nachgeladen (dynamic import → eigenes Bundle-Häppchen),
 * die Kamera übernommen und der letzte Zustand (Filter, Sichtbarkeit, Route,
 * Marker, Routing-Modus) neu eingespielt.
 *
 * Vor {@code createMap()} sind alle Methoden No-ops; während eines Wechsels
 * puffern die Zustands-Felder — der neue Motor bekommt sie beim Replay.
 */
@Injectable({ providedIn: 'root' })
export class MapService {
  private static readonly ENGINE_KEY = 'br-map-engine';

  private readonly zone = inject(NgZone);

  private engine?: MapEngine;
  private target?: HTMLElement;
  private popupElement?: HTMLElement;
  /** Wohin das Popup-Element gehört, wenn gerade kein Motor es sich ausborgt. */
  private popupHome?: HTMLElement;
  private callbacks?: MapCallbacks;
  private switching = false;

  /** Aktiver Motor — fürs UI (Schalter-Zustand, Beta-Hinweis). */
  readonly engineKind = signal<EngineKind>(MapService.storedEngine());

  /** Automatik-Signale: Gerät kann kein GPU-WebGL bzw. MapLibre ruckelt messbar. */
  readonly gpuCaveat = signal(false);
  readonly slowFrameMs = signal<number | null>(null);

  // Replay-Zustand für den Motor-Wechsel.
  private genera: ReadonlySet<string> = new Set();
  private cityId: string | null = null;
  private treesVisible = true;
  private zonesVisible = true;
  private routingOn = false;
  private onRoutingPoint: (p: LonLat) => void = () => undefined;
  private markers: { start: LonLat | null; end: LonLat | null } = { start: null, end: null };
  private route: [number, number][] = [];
  private directRoute: [number, number][] | null = null;

  /**
   * Einstiegspunkt aus der Komponente: merkt sich Ziel-Element, Popup-Rahmen und
   * Rückrufe und startet den zuletzt gewählten Motor. Alles Weitere läuft über
   * die Delegations-Methoden unten — die Komponente kennt die Motoren nicht.
   */
  createMap(
    target: HTMLElement,
    popupElement: HTMLElement,
    onFeatureInfo: MapCallbacks['onFeatureInfo'],
    onRouteLoaded: MapCallbacks['onRouteLoaded'],
  ): void {
    this.target = target;
    this.popupElement = popupElement;
    this.popupHome = popupElement.parentElement ?? undefined;
    this.callbacks = {
      onFeatureInfo,
      onRouteLoaded,
      onSlowRendering: (ms) => this.slowFrameMs.set(ms),
    };
    // Gespeicherte MapLibre-Wahl auf einem Gerät ohne GPU-WebGL: leise auf OL.
    if (this.engineKind() === 'maplibre' && !MapService.webglOk()) {
      this.engineKind.set('ol');
      this.gpuCaveat.set(true);
    }
    void this.mount(this.engineKind(), null);
  }

  /** Motor wechseln: Kamera mitnehmen, alten Motor abbauen, Zustand neu einspielen. */
  async switchEngine(kind: EngineKind): Promise<void> {
    if (kind === this.engineKind() || this.switching || !this.target) return;
    // Harter Vorab-Test: ohne GPU-beschleunigtes WebGL wäre MapLibre eine Qual —
    // Wechsel ablehnen und der App den Grund signalisieren (Hinweis-Banner).
    if (kind === 'maplibre' && !MapService.webglOk()) {
      this.gpuCaveat.set(true);
      return;
    }
    this.slowFrameMs.set(null);
    this.switching = true;
    try {
      const view = this.engine?.viewState() ?? null;
      this.engine?.destroy();
      this.engine = undefined;
      this.engineKind.set(kind);
      try { globalThis.localStorage?.setItem(MapService.ENGINE_KEY, kind); } catch { /* egal */ }
      await this.mount(kind, view);
    } finally {
      this.switching = false;
    }
  }

  private async mount(kind: EngineKind, view: ViewState | null): Promise<void> {
    // OpenLayers hängt das Popup-Element in seinen Overlay-Container und nimmt es
    // beim Abbau mit — ohne Rückholung stünde der neue Motor ohne Popup da.
    if (this.popupElement && this.popupHome && !this.popupHome.contains(this.popupElement)) {
      this.popupHome.appendChild(this.popupElement);
    }

    const engine: MapEngine = kind === 'maplibre'
      ? new (await import('./maplibre-engine')).MaplibreEngine(this.zone)
      : new (await import('./ol-engine')).OlEngine(this.zone);
    engine.create(this.target!, this.popupElement!, this.callbacks!, view);
    this.engine = engine;

    // Replay: der neue Motor soll aussehen und reagieren wie der alte.
    engine.setFilter(this.genera, this.cityId);
    engine.setTreesVisible(this.treesVisible);
    engine.setZonesVisible(this.zonesVisible);
    if (this.routingOn) engine.enableRouting(true, this.onRoutingPoint);
    if (this.markers.start || this.markers.end) engine.setRouteMarkers(this.markers.start, this.markers.end);
    if (this.directRoute) engine.drawDirectRoute(this.directRoute);
    if (this.route.length > 0) engine.drawRoute(this.route);
  }

  /** WebGL mit failIfMajorPerformanceCaveat: Software-Rendering zählt als Nein. */
  private static webglOk(): boolean {
    try {
      const canvas = document.createElement('canvas');
      const opts = { failIfMajorPerformanceCaveat: true };
      return !!(canvas.getContext('webgl2', opts) ?? canvas.getContext('webgl', opts));
    } catch {
      return false;
    }
  }

  private static storedEngine(): EngineKind {
    try {
      return globalThis.localStorage?.getItem(MapService.ENGINE_KEY) === 'maplibre' ? 'maplibre' : 'ol';
    } catch {
      return 'ol';
    }
  }

  // --- Delegation (merkt sich den Zustand fürs Replay) ----------------------

  setFilter(genera: ReadonlySet<string>, cityId: string | null): void {
    this.genera = genera;
    this.cityId = cityId;
    this.engine?.setFilter(genera, cityId);
  }

  setTreesVisible(visible: boolean): void {
    this.treesVisible = visible;
    this.engine?.setTreesVisible(visible);
  }

  setZonesVisible(visible: boolean): void {
    this.zonesVisible = visible;
    this.engine?.setZonesVisible(visible);
  }

  fitCity(boundingBox: [number, number, number, number]): void {
    this.engine?.fitCity(boundingBox);
  }

  clearRoute(): void {
    this.engine?.clearRoute();
  }

  enableRouting(on: boolean, onPoint: (p: LonLat) => void): void {
    this.routingOn = on;
    this.onRoutingPoint = onPoint;
    this.engine?.enableRouting(on, onPoint);
  }

  setRouteMarkers(start: LonLat | null, end: LonLat | null): void {
    this.markers = { start, end };
    this.engine?.setRouteMarkers(start, end);
  }

  drawRoute(coords: [number, number][]): void {
    this.route = coords;
    this.engine?.drawRoute(coords);
  }

  drawDirectRoute(coords: [number, number][] | null): void {
    this.directRoute = coords;
    this.engine?.drawDirectRoute(coords);
  }

  clearRouting(): void {
    this.markers = { start: null, end: null };
    this.route = [];
    this.directRoute = null;
    this.engine?.clearRouting();
  }

  hidePopup(): void {
    this.engine?.hidePopup();
  }
}

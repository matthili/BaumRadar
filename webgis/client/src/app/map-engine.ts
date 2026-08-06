import { LonLat, PopupData } from './models';

/** Welcher Karten-Motor gerade fährt (Schalter „Ansicht" im Panel). */
export type EngineKind = 'ol' | 'maplibre';

/** Kameraposition, die beim Motor-Wechsel übernommen wird (Zoom in OL-Zählung). */
export interface ViewState {
  center: [number, number]; // [lon, lat]
  zoom: number;
}

export interface MapCallbacks {
  /** Klick-Resultate (Popup-Inhalt); `null` = Klick ins Leere. */
  onFeatureInfo: (data: PopupData | null) => void;
  /** Name der per Drag&Drop geladenen GPX-Datei. */
  onRouteLoaded: (fileName: string) => void;
}

/**
 * Gemeinsame Schnittstelle der beiden Karten-Motoren. Der MapService (Fassade)
 * delegiert hierhin und spielt beim Motor-Wechsel den letzten Zustand (Filter,
 * Sichtbarkeit, Route, Marker) neu ein — die Motoren selbst bleiben zustandsarm.
 *
 * - {@link OlEngine}: OpenLayers, serverseitig gerenderte WMS-Bilder.
 * - {@link MaplibreEngine}: MapLibre GL, Vektorkacheln, im Browser gerendert.
 */
export interface MapEngine {
  create(target: HTMLElement, popupElement: HTMLElement, cb: MapCallbacks, view: ViewState | null): void;
  destroy(): void;
  viewState(): ViewState | null;

  /** Gattungs-/Stadtfilter — jeder Motor übersetzt selbst (CQL bzw. Stil-Filter). */
  setFilter(genera: ReadonlySet<string>, cityId: string | null): void;
  setTreesVisible(visible: boolean): void;
  setZonesVisible(visible: boolean): void;

  /** Auf eine Stadt-BoundingBox zoomen (Katalog-Reihenfolge: [minLat, minLon, maxLat, maxLon]). */
  fitCity(boundingBox: [number, number, number, number]): void;

  clearRoute(): void;
  enableRouting(on: boolean, onPoint: (p: LonLat) => void): void;
  setRouteMarkers(start: LonLat | null, end: LonLat | null): void;
  drawRoute(coords: [number, number][]): void;
  drawDirectRoute(coords: [number, number][] | null): void;
  clearRouting(): void;
  hidePopup(): void;
}

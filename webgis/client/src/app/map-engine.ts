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
  /**
   * Automatik-Vorschlag: Der Motor meldet, dass er auf diesem Gerät zäh läuft
   * (gemessene Bildwiederholzeiten, kein Datenblatt-Raten). Die App zeigt dann
   * einen dezenten Wechsel-Hinweis — entschieden wird von Menschenhand.
   */
  onSlowRendering?: (medianFrameMs: number) => void;
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
  /**
   * Baut die Karte im Ziel-Element auf. `view` übernimmt die Kamera des
   * abgelösten Motors; `null` heißt Kaltstart (DACH-Überblick).
   *
   * Beide Motoren borgen sich dasselbe `popupElement` — OL hängt es in sein
   * Overlay, MapLibre in seinen Popup-Rahmen. Wer es beim Abbau mitnimmt,
   * bekommt es vom MapService zurückgeholt.
   */
  create(target: HTMLElement, popupElement: HTMLElement, cb: MapCallbacks, view: ViewState | null): void;

  /** Karte samt Ereignis-Bindungen abbauen; danach ist die Instanz verbraucht. */
  destroy(): void;

  /** Aktuelle Kamera für die Übergabe an den anderen Motor; `null` vor {@link create}. */
  viewState(): ViewState | null;

  /** Gattungs-/Stadtfilter — jeder Motor übersetzt selbst (CQL bzw. Stil-Filter). */
  setFilter(genera: ReadonlySet<string>, cityId: string | null): void;
  setTreesVisible(visible: boolean): void;
  setZonesVisible(visible: boolean): void;

  /** Auf eine Stadt-BoundingBox zoomen (Katalog-Reihenfolge: [minLat, minLon, maxLat, maxLon]). */
  fitCity(boundingBox: [number, number, number, number]): void;

  /** Nur die per Drag&Drop geladene GPX-Spur entfernen — die berechnete Route bleibt. */
  clearRoute(): void;

  /**
   * Routing-Modus: Klicks auf die Karte melden Start/Ziel, statt eine
   * Sprechblase zu öffnen.
   */
  enableRouting(on: boolean, onPoint: (p: LonLat) => void): void;

  /** Start-/Ziel-Marker setzen; `null` entfernt den jeweiligen Marker. */
  setRouteMarkers(start: LonLat | null, end: LonLat | null): void;

  /** Berechnete Route zeichnen und in den Blick rücken (Stützpunkte [lon, lat]). */
  drawRoute(coords: [number, number][]): void;

  /** Vergleichs-Direktroute gestrichelt zeichnen; `null` entfernt sie. */
  drawDirectRoute(coords: [number, number][] | null): void;

  /** Route, Direktroute und Marker gemeinsam entfernen. */
  clearRouting(): void;

  hidePopup(): void;
}

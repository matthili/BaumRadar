import { Injectable, NgZone, inject } from '@angular/core';
import Map from 'ol/Map';
import View from 'ol/View';
import Overlay from 'ol/Overlay';
import TileLayer from 'ol/layer/Tile';
import VectorLayer from 'ol/layer/Vector';
import OSM from 'ol/source/OSM';
import TileWMS from 'ol/source/TileWMS';
import VectorSource from 'ol/source/Vector';
import GPX from 'ol/format/GPX';
import type FeatureFormat from 'ol/format/Feature';
import Feature from 'ol/Feature';
import type { FeatureLike } from 'ol/Feature';
import { LineString, Point } from 'ol/geom';
import DragAndDrop, { DragAndDropEvent } from 'ol/interaction/DragAndDrop';
import { fromLonLat, toLonLat, transformExtent } from 'ol/proj';
import { Style, Stroke, Circle as CircleStyle, Fill } from 'ol/style';
import type { Coordinate } from 'ol/coordinate';
import { GeoServerService } from './geoserver.service';
import { LonLat, PopupData, TreeHit, ZoneHit } from './models';

/**
 * Kapselt die komplette OpenLayers-Karte — bewusst ohne Wrapper-Bibliothek.
 *
 * Die Karte wird in {@link NgZone#runOutsideAngular} erzeugt: OL feuert
 * hochfrequente Events (pointermove, postrender), die unter zone.js sonst bei
 * jeder Mausbewegung Change Detection auslösen würden. Angular 22 läuft zwar
 * standardmäßig zoneless (damit ist das ohnehin entschärft), aber das Muster
 * bleibt als dokumentierte Defensive — der Code funktioniert unter beiden Modi.
 * Ergebnisse fließen ausschließlich über Signals/Callbacks zurück in die UI.
 */
@Injectable({ providedIn: 'root' })
export class MapService {
  private readonly zone = inject(NgZone);

  private map?: Map;
  private popupOverlay?: Overlay;
  private treesSource?: TileWMS;
  private zonesSource?: TileWMS;
  private treesLayer?: TileLayer<TileWMS>;
  private zonesLayer?: TileLayer<TileWMS>;
  private routeSource?: VectorSource;
  private routingSource?: VectorSource;
  private routingMode = false;
  private onRoutingPoint?: (p: LonLat) => void;

  /**
   * @param onFeatureInfo Klick-Resultate (GetFeatureInfo); `null` = Klick ins Leere
   * @param onRouteLoaded Name der per Drag&Drop geladenen GPX-Datei
   */
  createMap(
    target: HTMLElement,
    popupElement: HTMLElement,
    onFeatureInfo: (data: PopupData | null) => void,
    onRouteLoaded: (fileName: string) => void,
  ): void {
    this.zone.runOutsideAngular(() => {
      this.zonesSource = this.wmsSource('baumradar:allergy_zones');
      this.treesSource = this.wmsSource('baumradar:trees');
      this.zonesLayer = new TileLayer({ source: this.zonesSource });
      this.treesLayer = new TileLayer({ source: this.treesSource });

      // GPX-Routen (z. B. der Export der Android-App) im Marken-Grün.
      this.routeSource = new VectorSource();
      const routeLayer = new VectorLayer({
        source: this.routeSource,
        style: new Style({
          stroke: new Stroke({ color: 'rgba(46, 107, 46, 0.9)', width: 5 }),
          image: new CircleStyle({
            radius: 6,
            fill: new Fill({ color: '#2E6B2E' }),
            stroke: new Stroke({ color: '#FFFFFF', width: 2 }),
          }),
        }),
      });

      // Eigene Ebene für die berechnete Route + Start/Ziel-Marker (getrennt von der
      // GPX-Ebene). Style je nach Feature-Art ('start' | 'end' | 'route').
      this.routingSource = new VectorSource();
      const routingLayer = new VectorLayer({
        source: this.routingSource,
        style: (feature) => this.routingStyle(feature),
      });

      const dragAndDrop = new DragAndDrop({
        // Bekannte OL-Typing-Lücke: GPX ist zur Laufzeit ein gültiger
        // FeatureFormat-Konstruktor, die Generics passen nur nominell nicht.
        formatConstructors: [GPX as unknown as typeof FeatureFormat],
      });
      dragAndDrop.on('addfeatures', (event: DragAndDropEvent) => {
        const features = (event.features ?? []).filter(
          (f): f is Feature => f instanceof Feature,
        );
        if (features.length === 0) return;
        this.routeSource!.clear();
        this.routeSource!.addFeatures(features);
        const extent = this.routeSource!.getExtent();
        if (extent) {
          this.map!.getView().fit(extent, { padding: [90, 90, 90, 90], duration: 600 });
        }
        onRouteLoaded(event.file?.name ?? 'route.gpx');
      });

      this.popupOverlay = new Overlay({
        element: popupElement,
        positioning: 'bottom-center',
        offset: [0, -14],
        autoPan: { animation: { duration: 250 } },
      });

      this.map = new Map({
        target,
        layers: [new TileLayer({ source: new OSM() }), this.zonesLayer, this.treesLayer, routeLayer, routingLayer],
        overlays: [this.popupOverlay],
        view: new View({
          center: fromLonLat([12.5, 49.4]), // DACH-Überblick; Städte-Auswahl zoomt hinein
          zoom: 6,
        }),
      });
      this.map.addInteraction(dragAndDrop);

      this.map.on('singleclick', (event) => {
        // Im Routing-Modus setzen Klicks Start/Ziel statt eine Sprechblase zu öffnen.
        if (this.routingMode && this.onRoutingPoint) {
          const [lon, lat] = toLonLat(event.coordinate);
          this.onRoutingPoint({ lon, lat });
          return;
        }
        void this.queryFeatureInfo(event.coordinate, onFeatureInfo);
      });
    });
  }

  /**
   * Kombinierten CQL-Filter (Gattung + Stadt) auf beide WMS-Layer anwenden.
   * Der Aufrufer liefert stets einen gültigen Ausdruck ({@code INCLUDE} = alles).
   */
  setLayerFilter(cql: string): void {
    this.treesSource?.updateParams({ CQL_FILTER: cql });
    this.zonesSource?.updateParams({ CQL_FILTER: cql });
  }

  setTreesVisible(visible: boolean): void {
    this.treesLayer?.setVisible(visible);
  }

  setZonesVisible(visible: boolean): void {
    this.zonesLayer?.setVisible(visible);
  }

  /** Auf eine Stadt-BoundingBox zoomen (Katalog-Reihenfolge: [minLat, minLon, maxLat, maxLon]). */
  fitCity(boundingBox: [number, number, number, number]): void {
    const [minLat, minLon, maxLat, maxLon] = boundingBox;
    const extent = transformExtent([minLon, minLat, maxLon, maxLat], 'EPSG:4326', 'EPSG:3857');
    this.map?.getView().fit(extent, { padding: [70, 70, 70, 70], duration: 700 });
  }

  clearRoute(): void {
    this.routeSource?.clear();
  }

  /** Routing-Modus schalten: Klicks melden Start/Ziel (lon/lat) statt GetFeatureInfo. */
  enableRouting(on: boolean, onPoint: (p: LonLat) => void): void {
    this.routingMode = on;
    this.onRoutingPoint = on ? onPoint : undefined;
  }

  /** Start-/Ziel-Marker setzen; die berechnete Routenlinie bleibt erhalten. */
  setRouteMarkers(start: LonLat | null, end: LonLat | null): void {
    this.zone.runOutsideAngular(() => {
      const src = this.routingSource;
      if (!src) return;
      src.getFeatures()
        .filter((f) => f.get('kind') !== 'route')
        .forEach((f) => src.removeFeature(f));
      if (start) src.addFeature(this.markerFeature(start, 'start'));
      if (end) src.addFeature(this.markerFeature(end, 'end'));
    });
  }

  /** Berechnete Route (Stützpunkte [lon, lat]) zeichnen und in den Blick rücken. */
  drawRoute(coords: [number, number][]): void {
    this.zone.runOutsideAngular(() => {
      const src = this.routingSource;
      if (!src || coords.length === 0) return;
      src.getFeatures()
        .filter((f) => f.get('kind') === 'route')
        .forEach((f) => src.removeFeature(f));
      const geom = new LineString(coords.map((c) => fromLonLat(c)));
      const line = new Feature({ geometry: geom });
      line.set('kind', 'route');
      src.addFeature(line);
      this.map?.getView().fit(geom.getExtent(), {
        padding: [90, 90, 90, 90],
        duration: 500,
        maxZoom: 17,
      });
    });
  }

  /** Direktroute (ohne Meidung) als gestrichelte Vergleichslinie; null entfernt sie. */
  drawDirectRoute(coords: [number, number][] | null): void {
    this.zone.runOutsideAngular(() => {
      const src = this.routingSource;
      if (!src) return;
      src.getFeatures()
        .filter((f) => f.get('kind') === 'direct')
        .forEach((f) => src.removeFeature(f));
      if (!coords || coords.length === 0) return;
      const line = new Feature({ geometry: new LineString(coords.map((c) => fromLonLat(c))) });
      line.set('kind', 'direct');
      src.addFeature(line);
    });
  }

  /** Start, Ziel und Routenlinie entfernen. */
  clearRouting(): void {
    this.routingSource?.clear();
  }

  private markerFeature(p: LonLat, kind: 'start' | 'end'): Feature {
    const f = new Feature({ geometry: new Point(fromLonLat([p.lon, p.lat])) });
    f.set('kind', kind);
    return f;
  }

  private routingStyle(feature: FeatureLike): Style {
    if (feature.get('kind') === 'route') {
      return new Style({ stroke: new Stroke({ color: '#2E6B2E', width: 6 }), zIndex: 2 });
    }
    if (feature.get('kind') === 'direct') {
      // Vergleichslinie: gestrichelt und unter der Hauptroute, bewusst unauffällig.
      return new Style({
        stroke: new Stroke({ color: 'rgba(66, 66, 66, 0.8)', width: 4, lineDash: [10, 10] }),
        zIndex: 1,
      });
    }
    const isStart = feature.get('kind') === 'start';
    return new Style({
      image: new CircleStyle({
        radius: 8,
        fill: new Fill({ color: isStart ? '#2E6B2E' : '#FFFFFF' }),
        stroke: new Stroke({ color: '#2E6B2E', width: 3 }),
      }),
    });
  }

  hidePopup(): void {
    this.popupOverlay?.setPosition(undefined);
  }

  /** WMS GetFeatureInfo auf Bäume + Zonen am Klickpunkt (nutzt den aktiven CQL-Filter mit). */
  private async queryFeatureInfo(
    coordinate: Coordinate,
    onFeatureInfo: (data: PopupData | null) => void,
  ): Promise<void> {
    const view = this.map!.getView();
    const resolution = view.getResolution();
    if (resolution === undefined) return;

    const options = { INFO_FORMAT: 'application/json', FEATURE_COUNT: '6', BUFFER: '10' };
    const treesUrl = this.treesLayer!.getVisible()
      ? this.treesSource!.getFeatureInfoUrl(coordinate, resolution, 'EPSG:3857', options)
      : undefined;
    const zonesUrl = this.zonesLayer!.getVisible()
      ? this.zonesSource!.getFeatureInfoUrl(coordinate, resolution, 'EPSG:3857', options)
      : undefined;

    try {
      const [trees, zones] = await Promise.all([
        treesUrl ? this.fetchInfo(treesUrl) : Promise.resolve([]),
        zonesUrl ? this.fetchInfo(zonesUrl) : Promise.resolve([]),
      ]);

      const data: PopupData = {
        trees: trees.map(
          (p): TreeHit => ({
            genusDe: (p['genus_de'] as string) ?? 'Unbekannt',
            genusEn: (p['genus_en'] as string) ?? null,
            speciesDe: (p['species_de'] as string) ?? null,
            speciesEn: (p['species_en'] as string) ?? null,
          }),
        ),
        zones: zones.map(
          (p): ZoneHit => ({
            genusDe: (p['genus_de'] as string) ?? 'Unbekannt',
            treeCount: (p['tree_count'] as number) ?? 0,
            radiusM: (p['radius_m'] as number) ?? 0,
          }),
        ),
      };

      if (data.trees.length === 0 && data.zones.length === 0) {
        this.popupOverlay?.setPosition(undefined);
        onFeatureInfo(null);
      } else {
        this.popupOverlay?.setPosition(coordinate);
        onFeatureInfo(data);
      }
    } catch {
      this.popupOverlay?.setPosition(undefined);
      onFeatureInfo(null);
    }
  }

  private async fetchInfo(url: string): Promise<Record<string, unknown>[]> {
    const response = await fetch(url);
    if (!response.ok) return [];
    const fc = (await response.json()) as {
      features?: { properties: Record<string, unknown> }[];
    };
    return (fc.features ?? []).map((f) => f.properties);
  }

  private wmsSource(layer: string): TileWMS {
    return new TileWMS({
      url: GeoServerService.WMS_URL,
      params: { LAYERS: layer, TILED: true, VERSION: '1.3.0', CQL_FILTER: 'INCLUDE' },
      serverType: 'geoserver',
      transition: 0,
    });
  }
}

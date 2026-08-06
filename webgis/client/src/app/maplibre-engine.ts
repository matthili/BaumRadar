import { NgZone } from '@angular/core';
import { Map as MlMap, Popup, LngLat, GeoJSONSource, StyleSpecification, FilterSpecification } from 'maplibre-gl';
import type { Feature as GeoJsonFeature } from 'geojson';
import { MapCallbacks, MapEngine, ViewState } from './map-engine';
import { LonLat, PopupData, TreeHit, ZoneHit } from './models';

/**
 * Karten-Motor „lokal aufbereitet": MapLibre GL rendert Vektorkacheln aus dem
 * GeoServer-Kachel-Cache (GWC/TMS) auf der GPU des Clients; Filter sind reine
 * Stil-Filter ohne Server-Umlauf.
 *
 * Zoom-Leiter der Generalisierung (Zahlen in OL-Zählung, MapLibre = OL − 1):
 * Stadtpunkte < 8 → Rasterzellen (Bänder 8/11/13, gefärbt nach dominanter
 * Gattung) → Einzelbäume ab 14. Klick auf Stadtpunkt/Zelle zoomt hinein;
 * Popups gibt es wie im OL-Motor für Bäume und Zonen (Attribute stecken in
 * der Kachel — der Klick kostet keinen Server-Umlauf).
 */
export class MaplibreEngine implements MapEngine {
  /** MapLibre zählt Zoom eine Stufe niedriger als OpenLayers (512er- vs. 256er-Kachel). */
  private static readonly ZOOM_OFFSET = 1;

  /** Farben je dominanter Gattung (häufigste DACH-Straßenbäume); Rest fällt auf Grün. */
  private static readonly GENUS_COLORS: (string | string[])[] = [
    ['Ahorn'], '#c62828', ['Linde'], '#8e24aa', ['Eiche'], '#5d4037',
    ['Birke'], '#f9a825', ['Rosskastanie', 'Kastanie'], '#ef6c00',
    ['Platane'], '#00838f', ['Esche'], '#3949ab', ['Hainbuche'], '#33691e',
  ];

  private map?: MlMap;
  private popup?: Popup;
  private popupElement?: HTMLElement;
  private routingMode = false;
  private onRoutingPoint?: (p: LonLat) => void;
  private treesVisible = true;
  private zonesVisible = true;

  constructor(private readonly zone: NgZone) {}

  create(target: HTMLElement, popupElement: HTMLElement, cb: MapCallbacks, view: ViewState | null): void {
    this.popupElement = popupElement;
    this.zone.runOutsideAngular(() => {
      const map = new MlMap({
        container: target,
        style: this.buildStyle(),
        center: view?.center ?? [12.5, 49.4],
        zoom: (view?.zoom ?? 6) - MaplibreEngine.ZOOM_OFFSET,
        attributionControl: { compact: true },
      });
      this.map = map;

      map.on('click', (e) => {
        if (this.routingMode && this.onRoutingPoint) {
          this.onRoutingPoint({ lon: e.lngLat.lng, lat: e.lngLat.lat });
          return;
        }
        this.handleClick(e.point, e.lngLat, cb.onFeatureInfo);
      });
      for (const id of ['cities', 'cells8', 'cells11', 'cells13']) {
        map.on('mouseenter', id, () => (map.getCanvas().style.cursor = 'pointer'));
        map.on('mouseleave', id, () => (map.getCanvas().style.cursor = ''));
      }

      this.enableGpxDrop(target, cb.onRouteLoaded);
    });
  }

  destroy(): void {
    this.popup?.remove();
    this.map?.remove();
    this.map = undefined;
  }

  viewState(): ViewState | null {
    if (!this.map) return null;
    const c = this.map.getCenter();
    return { center: [c.lng, c.lat], zoom: this.map.getZoom() + MaplibreEngine.ZOOM_OFFSET };
  }

  /** Filter = Stil-Filter direkt auf der GPU-Ebene — ohne Server-Umlauf. */
  setFilter(genera: ReadonlySet<string>, cityId: string | null): void {
    const parts: FilterSpecification[] = [];
    if (genera.size > 0) parts.push(['in', ['get', 'genus_de'], ['literal', [...genera]]] as FilterSpecification);
    if (cityId) parts.push(['==', ['get', 'city_id'], cityId] as FilterSpecification);
    const combined: FilterSpecification | null =
      parts.length === 0 ? null : parts.length === 1 ? parts[0] : (['all', ...parts] as FilterSpecification);
    this.whenReady(() => {
      this.map!.setFilter('trees', combined);
      this.map!.setFilter('zones', combined);
      // Zellen kennen keine Einzel-Gattung — auf sie wirkt nur der Stadtfilter.
      const cityOnly: FilterSpecification | null = cityId ? ['==', ['get', 'city_id'], cityId] : null;
      for (const id of ['cells8', 'cells11', 'cells13']) this.map!.setFilter(id, cityOnly);
    });
  }

  setTreesVisible(visible: boolean): void {
    this.treesVisible = visible;
    this.whenReady(() =>
      ['trees', 'cities', 'cells8', 'cells11', 'cells13'].forEach((id) =>
        this.map!.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none')),
    );
  }

  setZonesVisible(visible: boolean): void {
    this.zonesVisible = visible;
    this.whenReady(() => this.map!.setLayoutProperty('zones', 'visibility', visible ? 'visible' : 'none'));
  }

  fitCity(boundingBox: [number, number, number, number]): void {
    const [minLat, minLon, maxLat, maxLon] = boundingBox;
    this.map?.fitBounds([[minLon, minLat], [maxLon, maxLat]], { padding: 70, duration: 700 });
  }

  clearRoute(): void {
    this.setGeoJson('gpx', []);
  }

  enableRouting(on: boolean, onPoint: (p: LonLat) => void): void {
    this.routingMode = on;
    this.onRoutingPoint = on ? onPoint : undefined;
  }

  setRouteMarkers(start: LonLat | null, end: LonLat | null): void {
    const features: GeoJsonFeature[] = [];
    if (start) features.push(this.pointFeature(start, 'start'));
    if (end) features.push(this.pointFeature(end, 'end'));
    this.setGeoJson('route-markers', features);
  }

  drawRoute(coords: [number, number][]): void {
    if (coords.length === 0) return;
    this.setGeoJson('route', [this.lineFeature(coords)]);
    const lons = coords.map((c) => c[0]);
    const lats = coords.map((c) => c[1]);
    this.map?.fitBounds(
      [[Math.min(...lons), Math.min(...lats)], [Math.max(...lons), Math.max(...lats)]],
      { padding: 90, duration: 500, maxZoom: 17 - MaplibreEngine.ZOOM_OFFSET },
    );
  }

  drawDirectRoute(coords: [number, number][] | null): void {
    this.setGeoJson('route-direct', coords && coords.length > 0 ? [this.lineFeature(coords)] : []);
  }

  clearRouting(): void {
    this.setGeoJson('route', []);
    this.setGeoJson('route-direct', []);
    this.setGeoJson('route-markers', []);
  }

  hidePopup(): void {
    this.popup?.remove();
    this.popup = undefined;
  }

  // --- intern ---------------------------------------------------------------

  /** Kachel-Quelle aus dem GeoServer-Kachel-Cache (TMS zählt y von unten). */
  private static tms(layer: string, minzoom: number, maxzoom: number) {
    return {
      type: 'vector' as const,
      tiles: [`${location.origin}/geoserver/gwc/service/tms/1.0.0/baumradar%3A${layer}@EPSG%3A900913@pbf/{z}/{x}/{y}.pbf`],
      scheme: 'tms' as const,
      minzoom,
      maxzoom,
    };
  }

  private buildStyle(): StyleSpecification {
    const genusColor = [
      'match', ['get', 'dominant_genus'],
      ...MaplibreEngine.GENUS_COLORS,
      '#2E6B2E',
    ];
    const cellRadius = ['interpolate', ['linear'], ['sqrt', ['get', 'tree_count']], 1, 4, 30, 16];
    const cellLayer = (id: string, layer: string, minzoom: number, maxzoom: number) => ({
      id, type: 'circle' as const, source: 'src-' + layer, 'source-layer': layer,
      minzoom, maxzoom,
      paint: {
        'circle-radius': cellRadius,
        'circle-color': genusColor,
        'circle-opacity': 0.6,
        'circle-stroke-color': '#ffffff',
        'circle-stroke-width': 1,
      },
    });
    const emptyGeoJson = () => ({
      type: 'geojson' as const,
      data: { type: 'FeatureCollection' as const, features: [] },
    });
    const Z = MaplibreEngine.ZOOM_OFFSET;
    return {
      version: 8,
      sources: {
        osm: {
          type: 'raster',
          tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
          tileSize: 256,
          attribution: '© OpenStreetMap-Mitwirkende',
        },
        'src-city_points': MaplibreEngine.tms('city_points', 0, 9),
        'src-tree_cells_z8': MaplibreEngine.tms('tree_cells_z8', 6, 10),
        'src-tree_cells_z11': MaplibreEngine.tms('tree_cells_z11', 9, 12),
        'src-tree_cells_z13': MaplibreEngine.tms('tree_cells_z13', 11, 13),
        'src-trees': MaplibreEngine.tms('trees', 12, 16),
        'src-allergy_zones': MaplibreEngine.tms('allergy_zones', 8, 15),
        gpx: emptyGeoJson(),
        'route-direct': emptyGeoJson(),
        route: emptyGeoJson(),
        'route-markers': emptyGeoJson(),
      },
      layers: [
        { id: 'osm', type: 'raster', source: 'osm' },
        {
          id: 'zones', type: 'fill', source: 'src-allergy_zones', 'source-layer': 'allergy_zones',
          minzoom: 9 - Z,
          paint: { 'fill-color': '#c62828', 'fill-opacity': 0.22, 'fill-outline-color': '#c62828' },
        },
        cellLayer('cells8', 'tree_cells_z8', 8 - Z, 11 - Z),
        cellLayer('cells11', 'tree_cells_z11', 11 - Z, 13 - Z),
        cellLayer('cells13', 'tree_cells_z13', 13 - Z, 14 - Z),
        {
          id: 'trees', type: 'circle', source: 'src-trees', 'source-layer': 'trees',
          minzoom: 14 - Z,
          paint: {
            'circle-radius': ['interpolate', ['linear'], ['zoom'], 12, 2.5, 16, 5],
            'circle-color': '#f9a825',
            'circle-stroke-color': '#6d4c00',
            'circle-stroke-width': 0.5,
          },
        },
        {
          id: 'cities', type: 'circle', source: 'src-city_points', 'source-layer': 'city_points',
          maxzoom: 8 - Z,
          paint: {
            'circle-radius': ['interpolate', ['linear'], ['sqrt', ['get', 'tree_count']], 30, 8, 700, 22],
            'circle-color': '#2E6B2E',
            'circle-opacity': 0.85,
            'circle-stroke-color': '#ffffff',
            'circle-stroke-width': 2,
          },
        },
        // Routen-Ebenen (GeoJSON-Quellen oben, anfangs leer) — Optik wie im OL-Motor.
        { id: 'gpx', type: 'line', source: 'gpx', paint: { 'line-color': 'rgba(46,107,46,0.9)', 'line-width': 5 } },
        {
          id: 'route-direct', type: 'line', source: 'route-direct',
          paint: { 'line-color': 'rgba(66,66,66,0.8)', 'line-width': 4, 'line-dasharray': [2, 2] },
        },
        { id: 'route', type: 'line', source: 'route', paint: { 'line-color': '#2E6B2E', 'line-width': 6 } },
        {
          id: 'route-markers', type: 'circle', source: 'route-markers',
          paint: {
            'circle-radius': 8,
            'circle-color': ['case', ['==', ['get', 'kind'], 'start'], '#2E6B2E', '#FFFFFF'],
            'circle-stroke-color': '#2E6B2E',
            'circle-stroke-width': 3,
          },
        },
      ] as unknown as StyleSpecification['layers'],
    };
  }

  /** Klick: Bäume/Zonen → Popup (lokal aus der Kachel); Stadt/Zelle → hineinzoomen. */
  private handleClick(
    point: { x: number; y: number },
    lngLat: LngLat,
    onFeatureInfo: (data: PopupData | null) => void,
  ): void {
    if (!this.map) return;
    const px: [number, number] = [point.x, point.y];
    const zoomTargets = this.map.queryRenderedFeatures(px, { layers: ['cities', 'cells8', 'cells11', 'cells13'] });
    if (zoomTargets.length > 0) {
      const current = this.map.getZoom();
      this.map.easeTo({ center: lngLat, zoom: current + 2.5, duration: 600 });
      return;
    }

    const layers = [
      ...(this.treesVisible ? ['trees'] : []),
      ...(this.zonesVisible ? ['zones'] : []),
    ];
    const hits = layers.length > 0 ? this.map.queryRenderedFeatures(px, { layers }) : [];
    const trees: TreeHit[] = [];
    const zones: ZoneHit[] = [];
    for (const f of hits.slice(0, 6)) {
      const p = f.properties as Record<string, unknown>;
      if (f.layer.id === 'trees') {
        trees.push({
          genusDe: (p['genus_de'] as string) ?? 'Unbekannt',
          genusEn: (p['genus_en'] as string) ?? null,
          speciesDe: (p['species_de'] as string) ?? null,
          speciesEn: (p['species_en'] as string) ?? null,
        });
      } else {
        zones.push({
          genusDe: (p['genus_de'] as string) ?? 'Unbekannt',
          treeCount: Number(p['tree_count'] ?? 0),
          radiusM: Number(p['radius_m'] ?? 0),
        });
      }
    }

    if (trees.length === 0 && zones.length === 0) {
      this.hidePopup();
      onFeatureInfo(null);
      return;
    }
    onFeatureInfo({ trees, zones });
    this.popup?.remove();
    this.popup = new Popup({ closeButton: false, offset: 14, maxWidth: 'none' })
      .setLngLat(lngLat)
      .setDOMContent(this.popupElement!)
      .addTo(this.map);
  }

  /** GPX-Drag&Drop wie im OL-Motor — hier per DOMParser (trkpt/rtept). */
  private enableGpxDrop(target: HTMLElement, onRouteLoaded: (fileName: string) => void): void {
    target.addEventListener('dragover', (e) => e.preventDefault());
    target.addEventListener('drop', (e) => {
      e.preventDefault();
      const file = e.dataTransfer?.files?.[0];
      if (!file || !file.name.toLowerCase().endsWith('.gpx')) return;
      void file.text().then((xml) => {
        const doc = new DOMParser().parseFromString(xml, 'application/xml');
        const pts = [...doc.querySelectorAll('trkpt, rtept')]
          .map((el) => [Number(el.getAttribute('lon')), Number(el.getAttribute('lat'))] as [number, number])
          .filter((c) => Number.isFinite(c[0]) && Number.isFinite(c[1]));
        if (pts.length < 2) return;
        this.setGeoJson('gpx', [this.lineFeature(pts)]);
        const lons = pts.map((c) => c[0]);
        const lats = pts.map((c) => c[1]);
        this.map?.fitBounds(
          [[Math.min(...lons), Math.min(...lats)], [Math.max(...lons), Math.max(...lats)]],
          { padding: 90, duration: 600 },
        );
        onRouteLoaded(file.name);
      });
    });
  }

  private setGeoJson(sourceId: string, features: GeoJsonFeature[]): void {
    this.whenReady(() => {
      const src = this.map!.getSource(sourceId) as GeoJSONSource | undefined;
      src?.setData({ type: 'FeatureCollection', features });
    });
  }

  /** Stil-Zugriffe erst nach dem Laden des Stils (Wechsel-Replay kommt früh). */
  private whenReady(fn: () => void): void {
    if (!this.map) return;
    if (this.map.isStyleLoaded()) fn();
    else this.map.once('load', fn);
  }

  private pointFeature(p: LonLat, kind: string): GeoJsonFeature {
    return { type: 'Feature', properties: { kind }, geometry: { type: 'Point', coordinates: [p.lon, p.lat] } };
  }

  private lineFeature(coords: [number, number][]): GeoJsonFeature {
    return { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } };
  }
}

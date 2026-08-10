# Glossar

Begriffe, Module, Dienste und Standards, die in der BaumRadar-Dokumentation immer
wieder auftauchen — jeweils allgemein erklärt **und** mit ihrer Rolle in diesem
Projekt. *(English version: [glossary_en.md](glossary_en.md))*

---

## BaumRadar-Bausteine

**Allergiezone (Geofence)** — Kreisförmige Zone (Mittelpunkt + Radius in Metern) um räumlich geclusterte Bäume *einer* Gattung. Das Backend berechnet sie über ein ~100-m-Raster. Die Android-App registriert sie als Android-Geofences für Sperrbildschirm-Warnungen; das WebGIS macht daraus per `ST_Buffer` meter-echte Polygone, die beim Routing gemieden werden.

**catalog.json (Katalog)** — Das zentrale Verzeichnis auf GitHub Pages und der Einstiegspunkt *aller* Konsumenten (Android-App, WebGIS-Loader, Photon-Container). Pro Stadt: Download-URLs, Signatur-URLs, BoundingBox und Versionen. Felder sind dokumentiert in [data_structure.md](data_structure.md).

**Chunk** — GitHub Pages mag keine Dateien über 50 MB, deshalb werden größere Artefakte in nummerierte Teile geschnitten (`berlin.db.gz.001`, `.002`, …). Wichtig: Chunks sind **Byte-Scheiben einer einzigen Datei** — erst binär aneinanderhängen, dann entpacken.

**data-processor** — Das Java-Backend des Projekts (Gradle-Modul neben der Android-App). Eine Batch-Pipeline: liest die offenen Baumkataster von 31 Städten (CSV, GeoJSON, WFS, XLSX, Esri-JSON), vereinheitlicht Namen und Koordinaten, clustert Allergiezonen, signiert alles und publiziert es als Stadt-Häppchen nach `docs/data/`.

**dataVersion / geocoderVersion** — Inhaltsbasierte Fingerprints (16 Hex-Zeichen) je Stadt im Katalog. Sie ändern sich nur, wenn sich die *Daten* wirklich ändern (ID- und reihenfolge-unabhängig berechnet) — so erkennen App und Loader Veraltetes und laden gezielt nur Geändertes nach.

**GeocoderCutter** — Backend-Werkzeug, das aus dem offiziellen Photon-Planet-Dump (~26 GB) die Geocoder-Daten pro Stadt herausschneidet: ein Streaming-Durchlauf, jeder Ort wird anhand seiner Koordinate den Stadt-BBoxen (+15 km Rand) zugeordnet. Jede Ausgabedatei bleibt ein eigenständig Photon-importierbarer Dump.

**graph-builder** — Einmal-Container im WebGIS-Stack: lädt die Länder-PBFs von Geofabrik (gecacht, abbruchsicher), schneidet die Stadt-BBoxen mit osmium heraus (eine Stadt pro Lauf — siehe Stolpersteine) und merged sie zum Insel-Graph.

**Harmonisierung** — Dreischichtige Bereinigung der Artnamen im Backend: Schicht 1 normalisiert deterministisch (Sorten-Schreibweisen, Mojibake, Latein-Kanonisierung), Schicht 2 vereinheitlicht deutsche Namen über eine kuratierte Alias-Tabelle, Schicht 3 schreibt einen Report als Arbeitsliste. Ergebnis: aus 18 Schreibweisen von *Acer platanoides* wird ein sauberes „Spitz-Ahorn".

**Insel-Graph (island.osm.pbf)** — Die 31 Stadt-Ausschnitte (+ Rand), zu **einer** OSM-Datei zusammengeführt. GraphHopper baut daraus seinen Routing-Graphen: Routing funktioniert innerhalb jeder Stadt; zwischen den Städten gibt es bewusst keine Verbindung — daher „Insel".

**loader (WebGIS)** — Der Java-25-Konsument im WebGIS-Stack: Katalog laden → Ed25519-Signaturen prüfen → Bäume und Zonen nach PostGIS importieren → GeoServer per REST provisionieren. Idempotent: unveränderte Städte (gleiche `dataVersion`) werden übersprungen.

**Runner (Runner-UI)** — Die lokale Web-Oberfläche des data-processors (`--args="--ui"`, Port 8420, null Zusatz-Abhängigkeiten). Städte lassen sich per Checkbox einzeln neu publizieren, Geocoder-Daten pro Stadt aktualisieren; Fortschritt kommt live per Server-Sent Events, und nach getaner Arbeit beendet sich der Runner selbst.

**Stadt-Häppchen** — Die Kern-Designidee des Projekts: Alle Daten werden **pro Stadt** publiziert (`<stadt>.db.gz` für Bäume/Zonen, `geocoder_<stadt>.jsonl.gz` für die Adresssuche), signiert und versioniert. Jeder Konsument lädt nur, was er braucht — 25 MB für Zugs Adresssuche statt 11 GB Länder-Index.

**web (Container)** — nginx, das den Angular-Client ausliefert und `/geoserver`, `/graphhopper` und `/photon` same-origin an die anderen Container durchreicht (kein CORS nötig). Der einzige Port des Stacks, der im LAN sichtbar ist.

---

## Dienste & Anwendungen

**basemap.at** — Die „Verwaltungsgrundkarte von Österreich": flächendeckende Hintergrundkarte aus amtlichen Geodaten, betrieben von den GIS-Stellen der neun Bundesländer (geoland.at) mit Städtebund, ÖVDAT/GIP.at und BEV, Projektleitung derzeit Stadt Wien; Lizenz CC-BY 4.0. Ausgeliefert klassisch als WMTS-Raster (EPSG:3857 und 31256) und zunehmend als Vektorkacheln samt Styles; der Relaunch 2026 stellt auf einen täglich aktualisierten Vektor-Workflow um. Im Projekt nicht eingesetzt (Hintergrund ist OSM) — aber das österreichische Anschauungsbeispiel für den Umstieg Raster → Vektor.

**Compose-Profil** — Docker-Compose-Mechanismus, um Service-Gruppen an- und abzuschalten. Im WebGIS: `routing` (graph-builder + GraphHopper) und `geocoding` (Photon). Das Startskript aktiviert beide standardmäßig; `-NoRouting`/`-NoGeocoding` schalten ab.

**Contraction Hierarchies (CH)** — Eine Vorverdichtung des Straßengraphen für extrem schnelle Anfragen. Der Haken: Die Kantengewichte werden beim Vorrechnen eingefroren — pro Anfrage veränderliche Custom Models sind damit unmöglich. Deshalb ist CH im Projekt bewusst deaktiviert (der kleine Insel-Graph ist auch ohne schnell genug).

**Custom Model (GraphHopper)** — Ein JSON-Regelwerk, das pro Routing-Anfrage mitgeschickt wird: Es kann Kanten-Prioritäten und -Geschwindigkeiten anpassen und **Areas** (Polygone) referenzieren. BaumRadar bündelt die Allergiezonen im Routen-Korridor zu einem MultiPolygon und wertet Kanten darin ab — der Faktor ist im Client wählbar („lieber queren als 5/10/20/50/100-facher Umweg", Standard 20-fach ≙ 0,05). *Weiche* Vermeidung: eine Route, die in einer Zone startet, funktioniert immer.

**Geofabrik** — Deutscher Anbieter täglich aktualisierter OSM-Auszüge (Kontinente, Länder, Bundesländer) als PBF. Quelle der Routing-Rohdaten (`germany-latest.osm.pbf` ~4 GB usw.).

**GeoServer** — Der verbreitetste Open-Source-Kartenserver (Java): publiziert Geodaten aus Datenbanken als standardkonforme OGC-Dienste. Im Projekt wird er komplett **per REST-API provisioniert** („Konfiguration als Code") — Workspace, Layer und Styles entstehen reproduzierbar ohne Klick-Arbeit.

**GML (Geography Markup Language)** — Das XML-Format der OGC für Geodaten und die *Standard*-Ausgabe jedes WFS; GeoJSON ist dort nur eine (verbreitete) Zusatzoption. Ausführlicher als GeoJSON, dafür ausdrucksstärker — es kann u. a. Mehrfach-Geometrien, an denen manche GeoJSON-Schreiber scheitern. Im Projekt liest `AbstractGmlProvider` es per StAX-Streaming (JDK-Bordmittel, keine neue Abhängigkeit); von einer Mehrfach-Geometrie zählt die erste Position, denn ein Baum ist ein Punkt.

**GraphHopper** — Open-Source-Routing-Engine auf OSM-Basis (Java). Im WebGIS läuft sie im *flexiblen Modus* mit den Profilen `foot` und `bike` und akzeptiert pro Anfrage ein Custom Model — die Grundlage der Allergiezonen-Vermeidung.

**KRZN (Niederrhein-Quelle)** — Das Kommunale Rechenzentrum Niederrhein betreibt einen offenen WFS, der **je Kommune eine eigene Baum-Ebene** führt (Krefeld, Moers, Viersen, Kleve, Emmerich, Xanten, Issum, Schwalmtal, Bedburg-Hau — zusammen ~184.000 Bäume). Lizenz: **Datenlizenz Deutschland – Zero 2.0** (gemeinfrei, Namensnennung nicht einmal verpflichtend). Weil alle Ebenen dasselbe Schema haben, bedient sie ein einziger Provider. Eigenheit: Viersens GeoJSON-Ausgabe scheitert an Mehrfach-Geometrien (`Could not export multi geometry`), daher läuft diese Stadt über [[GML]] — siehe `KrznGmlProvider`.

**MapLibre (GL JS / Native)** — Open-Source-Rendering-Bibliothek für Vektorkacheln, im Dezember 2020 als Fork von Mapbox GL JS entstanden, nachdem Mapbox proprietär wurde. Rendert per WebGL (der Browser-Schnittstelle zur Grafikkarte) direkt auf der GPU: stufenloses Zoomen, Rotieren, 3D, Globus; das Aussehen kommt aus einer Style-Spezifikation. „GL JS" läuft im Browser, „Native" auf Android/iOS/Desktop. Kein WFS, kein GetFeatureInfo, im Kern nur Web-Mercator. Seit der Zwei-Renderer-Demo ist MapLibre der **zweite Karten-Motor** des WebGIS (Schalter „Rendering: Browser (lokal)", Beta): Er liest Vektorkacheln aus dem GeoServer-Kachel-Cache und filtert Gattungen als Stil-Filter ohne Server-Umlauf. Standard bleibt OpenLayers — WFS+CQL, GetFeatureInfo und GPX sind dessen Heimspiel.

**Nominatim** — Der „amtliche" OSM-Geocoder (betreibt u. a. die Suche auf openstreetmap.org). Die Photon-Dumps werden aus Nominatim-Daten erzeugt, und die **Android-App** nutzt Nominatims öffentliche API für ihre Adresssuche.

**OpenLayers** — JavaScript-Kartenbibliothek im Web-Client: rendert die OSM-Grundkarte, die WMS-Layer, Marker und Routen und liefert Interaktionen (Klick, Drag&Drop). Wird bewusst ohne Angular-Wrapper genutzt; die Karte lebt außerhalb der Angular-Change-Detection. Rendert klassisch über Canvas 2D (mit wachsendem WebGL-Anteil) — das GPU-Gegenstück ist MapLibre.

**OpenSearch** — Volltext-Suchmaschine (Elasticsearch-Fork); Photon 1.x nutzt sie eingebettet als Index-Speicher. Für Betreiber unsichtbar — sie steckt im Photon-Prozess.

**osmium (osmium-tool)** — Das Schweizer Taschenmesser für OSM-Dateien: `extract` (Ausschneiden nach BBox), `merge`, `tags-filter`, `fileinfo`. Speicher-Eigenheit, die uns einen Stolperstein bescherte: `extract` hält pro Ausschnitt eine Bitmap über den *globalen* Node-ID-Raum — ~1,5 GB, egal wie klein die Stadt ist.

**OSRM** — Open Source Routing Machine, ein öffentlicher Routing-Dienst. Die **Android-App** holt sich dort bis zu drei Routen-Alternativen und prüft sie gegen die lokalen Zonen; das **WebGIS** routet dagegen komplett lokal mit GraphHopper (nur so ist Zonen-Vermeidung per Custom Model möglich).

**Photon** — Open-Source-Geocoder von Komoot (hier der Geocoder, nicht das Elementarteilchen): tippfehlertolerant, für Suche-während-des-Tippens gebaut, kennt Adressen *und* POIs. Im WebGIS wird er aus den Stadt-Häppchen des Katalogs gespeist; fällt er aus, weicht der Client automatisch auf die öffentliche Instanz photon.komoot.io aus.

**PostGIS** — Die Geo-Erweiterung von PostgreSQL: Geometrie-Datentypen, räumliche Indizes (GiST) und hunderte Funktionen. Für BaumRadar zentral: `ST_Buffer(…::geography, radius)` puffert in *echten Metern* auf dem Ellipsoid — ein Buffer in Grad wäre breitengradabhängig falsch.

---

## Karten-Standards (OGC & Co.)

**Capabilities (GetCapabilities)** — Die Selbstauskunft eines OGC-Dienstes: *ein* XML-Dokument beantwortet „wer betreibt mich, welche Ebenen habe ich, in welchen Koordinatensystemen und Formaten, welche Operationen kann ich, unter welchen Adressen". Das Gegenstück zu WSDL (SOAP) bzw. OpenAPI/Swagger (REST) — oder, bodenständiger, zur `--help`-Ausgabe eines Kommandozeilen-Werkzeugs, nur genormt und maschinenlesbar. Jeder Client beginnt damit; so wurden auch die neun KRZN-Kommunen entdeckt. Eine deutsche Übersetzung („Fähigkeitsbeschreibung") steht in Lehrbüchern, sagt aber niemand.

**CQL (Common Query Language)** — Die Filtersprache von GeoServer für WMS/WFS-Anfragen, z. B. `genus_de IN ('Birke') AND city_id = 'wien'`. Stolperstein: Bei `BBOX(…)` in EPSG:4326 erwartet GeoServer **lat,lon** — außer man nennt das CRS explizit mit.

**GetFeatureInfo** — Die dritte WMS-Grundoperation neben GetCapabilities/GetMap: „Was liegt an diesem Pixel?" — liefert die Attribute der getroffenen Features. Im WebGIS die Basis der Klick-Popups: Bäume und Zonen am Klickpunkt, unter Berücksichtigung des aktiven CQL-Filters.

**GPX (GPS Exchange Format)** — XML-Austauschformat für Routen und Tracks. Die Android-App exportiert geplante Routen als GPX (z. B. für Navigations-Apps); der Web-Client nimmt GPX-Dateien per Drag&Drop auf die Karte an.

**INSPIRE** — EU-Richtlinie (2007) für eine europäische Geodateninfrastruktur: verpflichtet Behörden, ihre Geodaten über harmonisierte Modelle und **OGC-basierte Dienste** bereitzustellen (Darstellung: WMS/WMTS; Download: WFS/Atom, zunehmend OGC API Features). Für BaumRadar als Privatprojekt keine Pflicht — aber der Grund, warum die OGC-Schiene im Behördenumfeld gesetzt ist. Wichtig ist die Zweiteilung: Die **Dienste-Konformität** (Pflichtangaben in den [[Capabilities]], erledigt eine GeoServer-Extension in Minuten) ist die leichtere Hälfte; die **Daten-Harmonisierung** — den eigenen Bestand in das INSPIRE-Datenmodell des jeweiligen Themas überführen — ist die Arbeit von Monaten. Für Statistik Austria besonders einschlägig: „Statistische Einheiten" und „Bevölkerungsverteilung" sind eigene INSPIRE-Themen, das Gitternetz ebenso.

**Metadaten (Dienst- und Datensatz-)** — Angaben *über* Daten, in zwei klar getrennten Sorten. **Dienst-Metadaten** beschreiben den Dienst selbst (Betreiber, Operationen, Ebenen) und stecken im Capabilities-Dokument. **Datensatz-Metadaten** beschreiben den Bestand: räumliche Ausdehnung, Erfassungszeitpunkt, Aktualisierungszyklus, Herkunft, Genauigkeit, verantwortliche Stelle, Lizenz, Schlagwörter — genormt nach ISO 19115 und über einen Katalogdienst (CSW) auffindbar. BaumRadars [`catalog.json`](data_structure.md) ist genau das im Kleinen: Name, BoundingBox, Download- und Signatur-URL sowie Version je Stadt — alles, was man wissen muss, *bevor* man die eigentlichen Daten anfasst.

**OGC** — Das *Open Geospatial Consortium*, das Standardisierungsgremium für Geo-Schnittstellen. „OGC-konform" heißt: Jeder Standard-Client (QGIS, ArcGIS, Web-Bibliotheken) kann die Dienste ohne Spezialwissen nutzen.

**OGC API Features** — Der moderne REST/JSON-Nachfolger des WFS (gleiche Daten, zeitgemäße Schnittstelle). In GeoServer erst ab 2.27 eine stabile Erweiterung — der Grund für unsere Versions-Untergrenze.

**SLD (Styled Layer Descriptor)** — XML-Format, das WMS-Layern ihr Aussehen gibt (Symbole, Farben, Maßstabsregeln). Die gelben Baum-Punkte und roten Zonen kommen aus zwei SLD-Dateien, die der Loader mit provisioniert.

**Style-Spezifikation (MapLibre/Mapbox Style Spec)** — JSON-Dokument, das einer Vektorkachel-Karte ihr Aussehen gibt: Quellen, Layer, datengetriebene Ausdrücke — ausgewertet erst im Client. Das clientseitige Gegenstück zum serverseitigen SLD. Im Projekt nutzt sie der MapLibre-Motor (Browser-Rendering); die Server-Ansicht styled weiter per SLD — optische Änderungen brauchen seither beide Welten.

**Vektorkacheln / MVT (Mapbox Vector Tiles)** — Kacheln, die statt fertiger Bilder **rohe Geometrien + Attribute** enthalten (kompakt als Protobuf); gerendert wird erst im Browser (MapLibre), gestylt per Style-Spezifikation. Offener De-facto-Industriestandard von Mapbox, aber **kein ratifizierter OGC-Standard** — die OGC deckt den Kachel-Teil über „OGC API – Tiles" ab, das MVT ausliefern kann. Vorteile: winzige Dateien, statisch/per CDN (Content Delivery Network) auslieferbar, Umstylen ohne neuen Kachel-Cache. Im Projekt seit der Zwei-Renderer-Demo im Einsatz: GeoServer erzeugt MVT per `vectortiles`-Extension, und der Loader rechnet Generalisierungs-Stufen vor (Stadtpunkte → Rasterzellen mit dominanter Gattung → Einzelbäume) — eine Wiener Übersichtskachel schrumpft damit von ~4,5 MB roh auf ~1,5 kB.

**WCS (Web Coverage Service)** — OGC-Standard für **Rasterdaten mit Messwerten** statt fertiger Bilder: Höhenmodelle, Satellitenszenen, Temperaturraster. Der Unterschied zum WMS ist der Punkt — WMS liefert ein Bild zum Ansehen, WCS die Zahlenwerte je Zelle zum Rechnen. Im Projekt nicht genutzt (BaumRadar kennt nur Punkte und Polygone); er steht hier, weil INSPIRE ihn neben WMS und WFS als dritten Diensttyp führt.

**WFS 2.0 (Web Feature Service)** — Liefert **rohe Features** (hier als GeoJSON) mit Filterung. Im Projekt die Grundlage für Statistiken, die Client-Suche und die Korridor-Zonen des Routings.

**WMS 1.3.0 (Web Map Service)** — Liefert **fertig gerenderte Kartenbilder** (Kacheln) statt Rohdaten. Dadurch bleibt die Darstellung von 2,8 Mio Bäumen flüssig: Der Server rendert, der Browser zeigt nur Bilder. Das Styling kommt aus SLD-Dateien.

**WMTS (Web Map Tile Service)** — OGC-Standard für **vorberechnete** Kachelpyramiden: Der Server rendert einmal, danach werden nur noch fertige Bilder ausgeliefert — ideal für Grundkarten (basemap.at, viele Behörden-Dienste). Abgrenzung zum WMS, der jede Kachel auf Anfrage frisch rendert (und darum dynamische CQL-Filter kann). Im Projekt selbst nicht genutzt: Die OSM-Grundkarte kommt im De-facto-„XYZ"-Schema, die Datenlayer als WMS.

---

## Datenformate & Geo-Grundbegriffe

**BBox (Bounding Box)** — Das umschließende Rechteck eines Gebiets, angegeben durch zwei Eckkoordinaten. Vorsicht Konventionen: Der BaumRadar-Katalog nutzt `[minLat, minLon, maxLat, maxLon]`, GeoJSON/Photon/GraphHopper dagegen lon-zuerst — Verwechslung ist *der* Klassiker unter den Geo-Bugs.

**Ed25519** — Modernes, schnelles Signaturverfahren (elliptische Kurven). Das Backend signiert jede publizierte Datei; App und Loader verifizieren gegen einen fest eingebauten Public Key — Manipulation oder Übertragungsfehler fliegen auf, bevor Daten verwendet werden.

**GeoJSON** — Geodaten als JSON (Punkte, Linien, Polygone + Attribute). Merksatz, der Fehler verhindert: Koordinaten stehen **immer als [Längengrad, Breitengrad]** — also [lon, lat], nicht umgekehrt.

**GitHub Pages / raw.githubusercontent** — Statisches Datei-Hosting direkt aus dem Repository. BaumRadars „Server ohne Server": Katalog und Stadt-Häppchen liegen dort, kein eigener Betrieb nötig. Eigenheit: aggressives Caching — deshalb hängen alle Konsumenten einen Zeitstempel-Parameter an (Cache-Buster).

**JSONL (JSON Lines)** — Ein JSON-Objekt pro Textzeile. Streambar ohne den Gesamtinhalt zu parsen — deshalb das Format der Photon-Dumps, die der GeocoderCutter zeilenweise durchströmt.

**OSM (OpenStreetMap)** — Die freie Weltkarte, gepflegt von Millionen Freiwilliger. Datenbasis für Grundkarte, Routing und Geocoding. Lizenz: ODbL — Nutzung frei, Attribution Pflicht.

**PBF (Protocolbuffer Binary Format)** — Das kompakte Binärformat für OSM-Daten (etwa halb so groß wie das XML-Äquivalent und viel schneller zu verarbeiten). Alle Routing-Rohdaten liegen als `.osm.pbf` vor.

**SQLite** — Datenbank in einer einzigen Datei, ohne Server. Format der Stadt-Baumdatenbanken: Das Backend erzeugt sie, die App importiert sie in ihre Room-Datenbank, der WebGIS-Loader liest sie per JDBC aus.

**WGS84 / EPSG:4326** — Das Koordinatensystem von GPS: Breite/Länge in Grad auf dem Ellipsoid. `EPSG:4326` ist seine Katalognummer. Verwandt: **EPSG:3857** („Web-Mercator"), die Projektion der Karten-Kacheln im Browser, und **UTM**, metrische Zonen-Systeme, in denen manche Kataster liefern (das Backend rechnet nach WGS84 um). Die Schweiz nutzt statt UTM ihr eigenes Landessystem **LV95 / EPSG:2056** (schiefachsige Mercator-Projektion, Ostwert ab 2.600.000 m) — dafür gibt es den `SwissConverter` mit den swisstopo-Näherungsformeln (~1 m genau, für Bäume mehr als ausreichend). Behörden arbeiten oft zusätzlich in amtlichen Landessystemen — in Österreich etwa **MGI** (z. B. EPSG:31256, „Gauß-Krüger Ost"), in dem u. a. basemap.at zusätzlich ausliefert; OpenLayers kann solche Projektionen darstellen, MapLibre praktisch nur Web-Mercator.

**Zstandard (zstd)** — Modernes Kompressionsformat (schnell bei hoher Rate). Der Photon-Planet-Dump kommt als `.jsonl.zst`; da das JDK zstd nicht nativ kann, nutzt der data-processor die Bibliothek `zstd-jni`.

---

*Zurück zur Übersicht: [README](../README.md) · [WebGIS-README](../webgis/README.md) · [WebGIS-Architektur](webgis_architecture.md)*

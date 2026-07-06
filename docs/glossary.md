# Glossar

Begriffe, Module, Dienste und Standards, die in der BaumRadar-Dokumentation immer
wieder auftauchen — jeweils allgemein erklärt **und** mit ihrer Rolle in diesem
Projekt. *(English version: [glossary_en.md](glossary_en.md))*

---

## BaumRadar-Bausteine

**data-processor** — Das Java-Backend des Projekts (Gradle-Modul neben der Android-App). Eine Batch-Pipeline: liest die offenen Baumkataster von 19 Städten (CSV, GeoJSON, WFS, XLSX, Esri-JSON), vereinheitlicht Namen und Koordinaten, clustert Allergiezonen, signiert alles und publiziert es als Stadt-Häppchen nach `docs/data/`.

**Runner (Runner-UI)** — Die lokale Web-Oberfläche des data-processors (`--args="--ui"`, Port 8420, null Zusatz-Abhängigkeiten). Städte lassen sich per Checkbox einzeln neu publizieren, Geocoder-Daten pro Stadt aktualisieren; Fortschritt kommt live per Server-Sent Events, und nach getaner Arbeit beendet sich der Runner selbst.

**catalog.json (Katalog)** — Das zentrale Verzeichnis auf GitHub Pages und der Einstiegspunkt *aller* Konsumenten (Android-App, WebGIS-Loader, Photon-Container). Pro Stadt: Download-URLs, Signatur-URLs, BoundingBox und Versionen. Felder sind dokumentiert in [data_structure.md](data_structure.md).

**dataVersion / geocoderVersion** — Inhaltsbasierte Fingerprints (16 Hex-Zeichen) je Stadt im Katalog. Sie ändern sich nur, wenn sich die *Daten* wirklich ändern (ID- und reihenfolge-unabhängig berechnet) — so erkennen App und Loader Veraltetes und laden gezielt nur Geändertes nach.

**Allergiezone (Geofence)** — Kreisförmige Zone (Mittelpunkt + Radius in Metern) um räumlich geclusterte Bäume *einer* Gattung. Das Backend berechnet sie über ein ~100-m-Raster. Die Android-App registriert sie als Android-Geofences für Sperrbildschirm-Warnungen; das WebGIS macht daraus per `ST_Buffer` meter-echte Polygone, die beim Routing gemieden werden.

**Stadt-Häppchen** — Die Kern-Designidee des Projekts: Alle Daten werden **pro Stadt** publiziert (`<stadt>.db.gz` für Bäume/Zonen, `geocoder_<stadt>.jsonl.gz` für die Adresssuche), signiert und versioniert. Jeder Konsument lädt nur, was er braucht — 25 MB für Zugs Adresssuche statt 11 GB Länder-Index.

**Chunk** — GitHub Pages mag keine Dateien über 50 MB, deshalb werden größere Artefakte in nummerierte Teile geschnitten (`berlin.db.gz.001`, `.002`, …). Wichtig: Chunks sind **Byte-Scheiben einer einzigen Datei** — erst binär aneinanderhängen, dann entpacken.

**Harmonisierung** — Dreischichtige Bereinigung der Artnamen im Backend: Schicht 1 normalisiert deterministisch (Sorten-Schreibweisen, Mojibake, Latein-Kanonisierung), Schicht 2 vereinheitlicht deutsche Namen über eine kuratierte Alias-Tabelle, Schicht 3 schreibt einen Report als Arbeitsliste. Ergebnis: aus 18 Schreibweisen von *Acer platanoides* wird ein sauberes „Spitz-Ahorn".

**GeocoderCutter** — Backend-Werkzeug, das aus dem offiziellen Photon-Planet-Dump (~26 GB) die Geocoder-Daten pro Stadt herausschneidet: ein Streaming-Durchlauf, jeder Ort wird anhand seiner Koordinate den Stadt-BBoxen (+15 km Rand) zugeordnet. Jede Ausgabedatei bleibt ein eigenständig Photon-importierbarer Dump.

**Insel-Graph (island.osm.pbf)** — Die 19 Stadt-Ausschnitte (+ Rand), zu **einer** OSM-Datei zusammengeführt. GraphHopper baut daraus seinen Routing-Graphen: Routing funktioniert innerhalb jeder Stadt; zwischen den Städten gibt es bewusst keine Verbindung — daher „Insel".

**graph-builder** — Einmal-Container im WebGIS-Stack: lädt die Länder-PBFs von Geofabrik (gecacht, abbruchsicher), schneidet die Stadt-BBoxen mit osmium heraus (eine Stadt pro Lauf — siehe Stolpersteine) und merged sie zum Insel-Graph.

**loader (WebGIS)** — Der Java-25-Konsument im WebGIS-Stack: Katalog laden → Ed25519-Signaturen prüfen → Bäume und Zonen nach PostGIS importieren → GeoServer per REST provisionieren. Idempotent: unveränderte Städte (gleiche `dataVersion`) werden übersprungen.

**web (Container)** — nginx, das den Angular-Client ausliefert und `/geoserver`, `/graphhopper` und `/photon` same-origin an die anderen Container durchreicht (kein CORS nötig). Der einzige Port des Stacks, der im LAN sichtbar ist.

---

## Dienste & Anwendungen

**GeoServer** — Der verbreitetste Open-Source-Kartenserver (Java): publiziert Geodaten aus Datenbanken als standardkonforme OGC-Dienste. Im Projekt wird er komplett **per REST-API provisioniert** („Konfiguration als Code") — Workspace, Layer und Styles entstehen reproduzierbar ohne Klick-Arbeit.

**PostGIS** — Die Geo-Erweiterung von PostgreSQL: Geometrie-Datentypen, räumliche Indizes (GiST) und hunderte Funktionen. Für BaumRadar zentral: `ST_Buffer(…::geography, radius)` puffert in *echten Metern* auf dem Ellipsoid — ein Buffer in Grad wäre breitengradabhängig falsch.

**GraphHopper** — Open-Source-Routing-Engine auf OSM-Basis (Java). Im WebGIS läuft sie im *flexiblen Modus* mit den Profilen `foot` und `bike` und akzeptiert pro Anfrage ein Custom Model — die Grundlage der Allergiezonen-Vermeidung.

**Custom Model (GraphHopper)** — Ein JSON-Regelwerk, das pro Routing-Anfrage mitgeschickt wird: Es kann Kanten-Prioritäten und -Geschwindigkeiten anpassen und **Areas** (Polygone) referenzieren. BaumRadar bündelt die Allergiezonen im Routen-Korridor zu einem MultiPolygon und wertet Kanten darin mit Faktor 0,05 ab — *weiche* Vermeidung: eine Route, die in einer Zone startet, funktioniert trotzdem.

**Contraction Hierarchies (CH)** — Eine Vorverdichtung des Straßengraphen für extrem schnelle Anfragen. Der Haken: Die Kantengewichte werden beim Vorrechnen eingefroren — pro Anfrage veränderliche Custom Models sind damit unmöglich. Deshalb ist CH im Projekt bewusst deaktiviert (der kleine Insel-Graph ist auch ohne schnell genug).

**Photon** — Open-Source-Geocoder von Komoot (hier der Geocoder, nicht das Elementarteilchen): tippfehlertolerant, für Suche-während-des-Tippens gebaut, kennt Adressen *und* POIs. Im WebGIS wird er aus den Stadt-Häppchen des Katalogs gespeist; fällt er aus, weicht der Client automatisch auf die öffentliche Instanz photon.komoot.io aus.

**OpenSearch** — Volltext-Suchmaschine (Elasticsearch-Fork); Photon 1.x nutzt sie eingebettet als Index-Speicher. Für Betreiber unsichtbar — sie steckt im Photon-Prozess.

**Nominatim** — Der „amtliche" OSM-Geocoder (betreibt u. a. die Suche auf openstreetmap.org). Die Photon-Dumps werden aus Nominatim-Daten erzeugt, und die **Android-App** nutzt Nominatims öffentliche API für ihre Adresssuche.

**OSRM** — Open Source Routing Machine, ein öffentlicher Routing-Dienst. Die **Android-App** holt sich dort bis zu drei Routen-Alternativen und prüft sie gegen die lokalen Zonen; das **WebGIS** routet dagegen komplett lokal mit GraphHopper (nur so ist Zonen-Vermeidung per Custom Model möglich).

**OpenLayers** — JavaScript-Kartenbibliothek im Web-Client: rendert die OSM-Grundkarte, die WMS-Layer, Marker und Routen und liefert Interaktionen (Klick, Drag&Drop). Wird bewusst ohne Angular-Wrapper genutzt; die Karte lebt außerhalb der Angular-Change-Detection.

**osmium (osmium-tool)** — Das Schweizer Taschenmesser für OSM-Dateien: `extract` (Ausschneiden nach BBox), `merge`, `tags-filter`, `fileinfo`. Speicher-Eigenheit, die uns einen Stolperstein bescherte: `extract` hält pro Ausschnitt eine Bitmap über den *globalen* Node-ID-Raum — ~1,5 GB, egal wie klein die Stadt ist.

**Geofabrik** — Deutscher Anbieter täglich aktualisierter OSM-Auszüge (Kontinente, Länder, Bundesländer) als PBF. Quelle der Routing-Rohdaten (`germany-latest.osm.pbf` ~4 GB usw.).

**Compose-Profil** — Docker-Compose-Mechanismus, um Service-Gruppen an- und abzuschalten. Im WebGIS: `routing` (graph-builder + GraphHopper) und `geocoding` (Photon). Das Startskript aktiviert beide standardmäßig; `-NoRouting`/`-NoGeocoding` schalten ab.

---

## Karten-Standards (OGC & Co.)

**OGC** — Das *Open Geospatial Consortium*, das Standardisierungsgremium für Geo-Schnittstellen. „OGC-konform" heißt: Jeder Standard-Client (QGIS, ArcGIS, Web-Bibliotheken) kann die Dienste ohne Spezialwissen nutzen.

**WMS 1.3.0 (Web Map Service)** — Liefert **fertig gerenderte Kartenbilder** (Kacheln) statt Rohdaten. Dadurch bleibt die Darstellung von 2,6 Mio Bäumen flüssig: Der Server rendert, der Browser zeigt nur Bilder. Das Styling kommt aus SLD-Dateien.

**WFS 2.0 (Web Feature Service)** — Liefert **rohe Features** (hier als GeoJSON) mit Filterung. Im Projekt die Grundlage für Statistiken, die Client-Suche und die Korridor-Zonen des Routings.

**OGC API Features** — Der moderne REST/JSON-Nachfolger des WFS (gleiche Daten, zeitgemäße Schnittstelle). In GeoServer erst ab 2.27 eine stabile Erweiterung — der Grund für unsere Versions-Untergrenze.

**CQL (Common Query Language)** — Die Filtersprache von GeoServer für WMS/WFS-Anfragen, z. B. `genus_de IN ('Birke') AND city_id = 'wien'`. Stolperstein: Bei `BBOX(…)` in EPSG:4326 erwartet GeoServer **lat,lon** — außer man nennt das CRS explizit mit.

**SLD (Styled Layer Descriptor)** — XML-Format, das WMS-Layern ihr Aussehen gibt (Symbole, Farben, Maßstabsregeln). Die gelben Baum-Punkte und roten Zonen kommen aus zwei SLD-Dateien, die der Loader mit provisioniert.

**GPX (GPS Exchange Format)** — XML-Austauschformat für Routen und Tracks. Die Android-App exportiert geplante Routen als GPX (z. B. für Navigations-Apps); der Web-Client nimmt GPX-Dateien per Drag&Drop auf die Karte an.

---

## Datenformate & Geo-Grundbegriffe

**OSM (OpenStreetMap)** — Die freie Weltkarte, gepflegt von Millionen Freiwilliger. Datenbasis für Grundkarte, Routing und Geocoding. Lizenz: ODbL — Nutzung frei, Attribution Pflicht.

**PBF (Protocolbuffer Binary Format)** — Das kompakte Binärformat für OSM-Daten (etwa halb so groß wie das XML-Äquivalent und viel schneller zu verarbeiten). Alle Routing-Rohdaten liegen als `.osm.pbf` vor.

**GeoJSON** — Geodaten als JSON (Punkte, Linien, Polygone + Attribute). Merksatz, der Fehler verhindert: Koordinaten stehen **immer als [Längengrad, Breitengrad]** — also [lon, lat], nicht umgekehrt.

**JSONL (JSON Lines)** — Ein JSON-Objekt pro Textzeile. Streambar ohne den Gesamtinhalt zu parsen — deshalb das Format der Photon-Dumps, die der GeocoderCutter zeilenweise durchströmt.

**Zstandard (zstd)** — Modernes Kompressionsformat (schnell bei hoher Rate). Der Photon-Planet-Dump kommt als `.jsonl.zst`; da das JDK zstd nicht nativ kann, nutzt der data-processor die Bibliothek `zstd-jni`.

**SQLite** — Datenbank in einer einzigen Datei, ohne Server. Format der Stadt-Baumdatenbanken: Das Backend erzeugt sie, die App importiert sie in ihre Room-Datenbank, der WebGIS-Loader liest sie per JDBC aus.

**BBox (Bounding Box)** — Das umschließende Rechteck eines Gebiets, angegeben durch zwei Eckkoordinaten. Vorsicht Konventionen: Der BaumRadar-Katalog nutzt `[minLat, minLon, maxLat, maxLon]`, GeoJSON/Photon/GraphHopper dagegen lon-zuerst — Verwechslung ist *der* Klassiker unter den Geo-Bugs.

**WGS84 / EPSG:4326** — Das Koordinatensystem von GPS: Breite/Länge in Grad auf dem Ellipsoid. `EPSG:4326` ist seine Katalognummer. Verwandt: **EPSG:3857** („Web-Mercator"), die Projektion der Karten-Kacheln im Browser, und **UTM**, metrische Zonen-Systeme, in denen manche Kataster liefern (das Backend rechnet nach WGS84 um).

**Ed25519** — Modernes, schnelles Signaturverfahren (elliptische Kurven). Das Backend signiert jede publizierte Datei; App und Loader verifizieren gegen einen fest eingebauten Public Key — Manipulation oder Übertragungsfehler fliegen auf, bevor Daten verwendet werden.

**GitHub Pages / raw.githubusercontent** — Statisches Datei-Hosting direkt aus dem Repository. BaumRadars „Server ohne Server": Katalog und Stadt-Häppchen liegen dort, kein eigener Betrieb nötig. Eigenheit: aggressives Caching — deshalb hängen alle Konsumenten einen Zeitstempel-Parameter an (Cache-Buster).

---

*Zurück zur Übersicht: [README](../README.md) · [WebGIS-README](../webgis/README.md) · [WebGIS-Architektur](webgis_architecture.md)*

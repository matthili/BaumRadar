# Backend Architektur (Data-Processor)

Das Backend von Baumradar ist ein **Java-Projekt** (`data-processor` Modul), das als Datenpipeline fungiert. Es aggregiert öffentliche Baumkataster-Daten (Open Data) von verschiedenen Städten, normalisiert sie, berechnet räumliche Geofence-Cluster und exportiert das Ergebnis als kryptografisch signierte SQLite-Datenbanken.

---

## Paketstruktur

```
at.mafue.baumradar.dataprocessor
├── Main.java                    # Einstiegspunkt: Orchestriert die gesamte Pipeline
├── models/
│   ├── TreeRecord.java          # Datenklasse: id, cityId, latitude, longitude, genusDe, genusEn, speciesDe, speciesEn
│   └── GeofenceRecord.java      # Datenklasse: id, cityId, latitude, longitude, radius, count, genusDe
├── providers/
│   ├── CityProvider.java        # Interface: getCityId(), getName(), getCountry(), getBoundingBox(), processData()
│   ├── AbstractGeoJsonProvider.java  # Basis für GeoJSON-basierte Städte (Streaming-Parser, Pagination, ZIP-Support)
│   ├── AbstractCsvProvider.java      # Basis für CSV-basierte Städte (Zeilenweises Parsing, Header-Mapping)
│   ├── austria/
│   │   ├── ViennaProvider.java       # Wien: CSV vom OGD-Portal
│   │   └── LinzProvider.java         # Linz: CSV vom OGD-Portal
│   ├── germany/
│   │   ├── BerlinProvider.java       # Berlin: GeoJSON vom GDI Berlin WFS
│   │   ├── HamburgProvider.java      # Hamburg: GeoJSON vom WFS, UTM32N → WGS84 Konvertierung
│   │   ├── FreiburgProvider.java     # Freiburg: GeoJSON
│   │   └── DortmundProvider.java     # Dortmund: GeoJSON
│   └── switzerland/
│       ├── ZurichProvider.java       # Zürich: GeoJSON vom städtischen WFS
│       └── BaselProvider.java        # Basel: GeoJSON
└── utils/
    ├── DatabaseExporter.java    # SQLite-Erzeugung: Tabellen anlegen, Batch-Inserts, Pragmas für Performance
    ├── CatalogBuilder.java      # Erzeugt catalog.json mit URLs, Chunks und BoundingBoxen
    ├── CryptoManager.java       # Ed25519-Schlüsselverwaltung (laden/generieren), Signatur-Erzeugung
    ├── Translator.java          # Übersetzungstabelle: Deutsche Gattungsnamen → Englisch (z.B. "Birke" → "Birch")
    └── UtmConverter.java        # UTM Zone 32N → WGS84 Koordinaten-Umrechnung (z.B. für Hamburg)
```

![Klassen-Diagramm Backend](architecture/05_backend_classes.png)

---

## Pipeline-Ablauf (Main.java)

![Daten-Ingestion Ablauf](architecture/02_data_ingestion.png)

Der Ablauf in `Main.main()`:

### Schritt 1: Kryptografisches Setup
```
CryptoManager.loadOrGenerateKeyPair(privFile, pubFile)
```
Falls bereits ein Ed25519-Schlüsselpaar auf der Festplatte liegt (`private_key.b64`, `public_key.b64`), wird es geladen. Andernfalls wird ein neues Paar generiert und gespeichert. Der Private Key verlässt **niemals** das Backend; nur der Public Key wird mit committet.

### Schritt 2: Parallele Stadt-Verarbeitung
Alle 8 registrierten `CityProvider` werden gleichzeitig via `ExecutorService` (Thread-Pool) verarbeitet. Pro Stadt:

1. **Daten herunterladen & parsen**: Je nach Provider-Typ:
   - `AbstractGeoJsonProvider`: Jackson Streaming-Parser (`JsonFactory`), optional mit Pagination (ArcGIS-APIs liefern z.B. max. 5000 Features pro Request) und ZIP-Entpackung.
   - `AbstractCsvProvider`: Zeilenweises `BufferedReader`-Parsing mit konfigurierbarem Delimiter (`getSplitRegex()`). Header werden via `processHeaders()` analysiert.
   
2. **Normalisierung**: 
   - `Translator.translateGenus()` und `translateSpecies()` vereinheitlichen die Namen (z.B. verschiedene Schreibweisen von "Hänge-Birke" → konsistenter Eintrag).
   - `UtmConverter.utm32NToWgs84()` wird für Städte wie Hamburg benötigt, deren Koordinaten nicht in WGS84 vorliegen.

3. **Geofence-Clustering**: Während des Parsens wird für jeden Baum ein **Grid-basierter Cluster** berechnet:
   - Grid-Schlüssel: `genusDe + "|" + lat (3 Dezimalen) + "|" + lon (3 Dezimalen)` → ca. 100m × 100m Zellen.
   - Bäume derselben Gattung in derselben Zelle werden zu einem `GeofenceRecord` zusammengefasst.
   - Radius: 50m für Einzelbäume, 100m für Baumgruppen (≥ 2 Bäume).
   - Zentrum: Arithmetisches Mittel aller Baum-Koordinaten im Cluster.

4. **SQLite-Export**: `DatabaseExporter` erzeugt eine SQLite-Datenbank mit optimierten Pragmas (`journal_mode=OFF`, `synchronous=0`, `cache_size=100000`) und schreibt die Daten in Batches von je 5000 Einträgen in die Tabellen `trees` und `geofences`.

5. **GZIP-Kompression**: Die fertige `.db` Datei wird als `.db.gz` komprimiert.

6. **Signierung**: `CryptoManager.signFile()` erzeugt eine Ed25519-Signatur (`.db.gz.sig`), die die gesamte komprimierte Datei abdeckt.

7. **Chunking** (falls > 50MB): Die `.db.gz` wird in 50MB-Chunks (`.001`, `.002`, ...) gesplittet. Die Original-Datei wird gelöscht, die Signatur bleibt – sie gilt für die zusammengesetzte Datei.

### Schritt 3: Katalog-Erzeugung
`CatalogBuilder.build()` erzeugt eine `catalog.json`, die für jede Stadt folgende Informationen enthält:
```json
{
  "id": "vienna",
  "name": "Wien",
  "country": "Österreich",
  "boundingBox": [48.12, 16.18, 48.32, 16.58],
  "dbUrl": "https://raw.githubusercontent.com/.../vienna.db.gz",
  "dbUrlChunks": ["...vienna.db.gz.001", "...vienna.db.gz.002"],
  "sigUrl": "https://raw.githubusercontent.com/.../vienna.db.gz.sig"
}
```

![System-Architektur Übersicht](architecture/01_system_architecture.png)

---

## Einen neuen Stadt-Provider hinzufügen

1. Erstelle eine neue Klasse (z.B. `MunichProvider`) im passenden Länder-Package, die `AbstractGeoJsonProvider` oder `AbstractCsvProvider` erweitert.
2. Implementiere die abstrakten Methoden: `getCityId()`, `getName()`, `getCountry()`, `getBoundingBox()`, und `mapFeatureToTree()` bzw. `mapRowToTree()`.
3. Registriere den Provider in `Main.java` in der `providers`-Liste.
4. Fertig – beim nächsten Lauf wird die Stadt automatisch verarbeitet, signiert und in den Katalog aufgenommen.

---

## Abhängigkeiten

| Bibliothek | Zweck |
|---|---|
| Jackson (Streaming + Databind) | GeoJSON-Parsing |
| SQLite JDBC | Datenbank-Erzeugung |
| BouncyCastle | Ed25519-Kryptografie |
| SLF4J + Logback | Logging |

[Zurück zur Startseite](../README.md) | [English Version](backend_architecture_en.md)

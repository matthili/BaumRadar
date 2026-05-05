# Datenstruktur & 3rd-Party Nutzung

Ein zentrales Ziel von Baumradar ist es, die mühsame Aufbereitung von Open Data für andere Entwickler zu übernehmen. Du kannst die vom Backend generierten, bereinigten SQLite-Datenbanken direkt in deinem eigenen Projekt (z.B. iOS-App, Web-App, Datenanalyse, Schulprojekt) verwenden.

---

## Schritt 1: Die Daten finden

Alle Daten sind über eine zentrale JSON-Datei erreichbar:

```
https://raw.githubusercontent.com/matthili/BaumRadar/master/docs/data/catalog.json
```

Diese Datei hat folgende Struktur:

```json
{
  "version": 1,
  "cities": [
    {
      "id": "vienna",
      "name": "Wien",
      "country": "Österreich",
      "boundingBox": [48.12, 16.18, 48.32, 16.58],
      "dbUrl": "https://raw.githubusercontent.com/.../vienna.db.gz",
      "sigUrl": "https://raw.githubusercontent.com/.../vienna.db.gz.sig"
    },
    {
      "id": "berlin",
      "name": "Berlin",
      "country": "Deutschland",
      "boundingBox": [52.33, 13.08, 52.68, 13.76],
      "dbUrl": "https://raw.githubusercontent.com/.../berlin.db.gz",
      "dbUrlChunks": [
        "https://...berlin.db.gz.001",
        "https://...berlin.db.gz.002"
      ],
      "sigUrl": "https://raw.githubusercontent.com/.../berlin.db.gz.sig"
    }
  ]
}
```

**Felder erklärt:**
- `id`: Eindeutige Kennung der Stadt.
- `boundingBox`: `[minLat, minLon, maxLat, maxLon]` – der geografische Bereich der Stadt.
- `dbUrl`: Download-URL für die komprimierte Datenbank. **Achtung:** Bei Städten mit `dbUrlChunks` musst du die Chunks verwenden (die `dbUrl` zeigt dann auf eine nicht existierende Datei).
- `dbUrlChunks`: Falls die Datenbank > 50MB ist (z.B. Berlin), ist sie in mehrere Teile aufgesplittet. Diese müssen in der richtigen Reihenfolge binär zusammengesetzt werden.
- `sigUrl`: URL der Ed25519-Signatur.

---

## Schritt 2: Download & Entpacken

### Für Dateien ohne Chunks:
1. Lade `dbUrl` herunter → z.B. `vienna.db.gz`
2. Entpacke die GZIP-Datei → `vienna.db`

### Für Dateien mit Chunks (z.B. Berlin):
1. Lade alle Chunk-URLs der Reihe nach herunter (`.001`, `.002`, ...)
2. Hänge die Dateien binär aneinander → `berlin.db.gz`
3. Entpacke die GZIP-Datei → `berlin.db`

---

## Schritt 3: Signatur verifizieren (optional, aber empfohlen)

Damit du dir sicher sein kannst, dass die Daten authentisch sind und nicht manipuliert wurden, wird jede Datenbank vom Backend mit Ed25519 signiert.

1. Lade den **Ed25519 Public Key** aus dem Repository: `docs/data/public_key.b64` (Base64-codiert, X.509-Format).
2. Lade die Signatur-Datei herunter (z.B. `vienna.db.gz.sig`).
3. Verifiziere die `.db.gz` Datei (oder die zusammengesetzten Chunks, **vor** dem Entpacken) gegen die Signatur.

**Achtung bei Chunks:** Die Signatur gilt für die zusammengesetzte `.db.gz` Datei, nicht für einzelne Chunks. Du musst zuerst alle Chunks zusammensetzen und dann die Gesamtdatei gegen die Signatur prüfen.

---

## Die SQLite-Datenbank

Die entpackte `.db`-Datei ist eine gewöhnliche SQLite-Datenbank, die mit jeder SQLite-Bibliothek geöffnet werden kann. Sie enthält zwei Tabellen:

### Tabelle `trees`

Enthält jeden einzelnen Baum als eigene Zeile.

| Spalte | Typ | Beschreibung |
|---|---|---|
| `id` | TEXT (PK) | Eindeutige ID des Baumes |
| `city_id` | TEXT | ID der Stadt (z.B. `"vienna"`) |
| `lat` | REAL | WGS84 Breitengrad |
| `lon` | REAL | WGS84 Längengrad |
| `genus_de` | TEXT | Deutscher Gattungsname (z.B. `"Birke"`) |
| `genus_en` | TEXT | Englischer Gattungsname (z.B. `"Birch"`) |
| `species_de` | TEXT | Deutsche Artbezeichnung (z.B. `"Hänge-Birke"`), kann NULL sein |
| `species_en` | TEXT | Englische Artbezeichnung (z.B. `"Silver Birch"`), kann NULL sein |

### Tabelle `geofences`

Vorberechnete Zonen für Baumgruppen derselben Gattung. Ein Geofence fasst mehrere nahe beieinander stehende Bäume zu einem Kreis zusammen. Das spart immens Rechenzeit bei Routen-Kollisionsprüfungen.

| Spalte | Typ | Beschreibung |
|---|---|---|
| `id` | TEXT (PK) | Eindeutige Zonen-ID (UUID) |
| `lat` | REAL | WGS84 Breitengrad des Zonen-Zentrums |
| `lon` | REAL | WGS84 Längengrad des Zonen-Zentrums |
| `radius` | INTEGER | Radius in Metern (50m für Einzelbäume, 100m für Gruppen) |
| `count` | INTEGER | Anzahl der Bäume in diesem Cluster |
| `genus_de` | TEXT | **Die Relation zu `trees`!** Deutscher Gattungsname – entspricht `trees.genus_de` |

**Wie hängen die Tabellen zusammen?**
Die Spalte `geofences.genus_de` enthält exakt denselben Wert wie `trees.genus_de`. Wenn ein Nutzer eine Allergie gegen `"Birke"` auswählt, kannst du sowohl die einzelnen Bäume (`SELECT * FROM trees WHERE genus_de = 'Birke'`) als auch die vorberechneten Zonen (`SELECT * FROM geofences WHERE genus_de = 'Birke'`) abfragen.

---

## Code-Beispiele

### Python: Alle Birken in Wien finden

```python
import sqlite3
import gzip
import urllib.request

# 1. Datenbank herunterladen und entpacken
urllib.request.urlretrieve(
    "https://raw.githubusercontent.com/matthili/BaumRadar/master/docs/data/vienna.db.gz",
    "vienna.db.gz"
)

with gzip.open("vienna.db.gz", "rb") as f_in:
    with open("vienna.db", "wb") as f_out:
        f_out.write(f_in.read())

# 2. Datenbank öffnen und abfragen
conn = sqlite3.connect("vienna.db")
cursor = conn.cursor()

# Alle Birken in Wien
cursor.execute("""
    SELECT id, lat, lon, genus_de, species_de 
    FROM trees 
    WHERE genus_de = 'Birke'
""")

birken = cursor.fetchall()
print(f"Anzahl Birken in Wien: {len(birken)}")

for baum in birken[:5]:
    print(f"  ID: {baum[0]}, Position: ({baum[1]:.5f}, {baum[2]:.5f}), Art: {baum[4] or 'unbekannt'}")

# Geofence-Zonen für Birken
cursor.execute("""
    SELECT lat, lon, radius, count 
    FROM geofences 
    WHERE genus_de = 'Birke'
""")

zonen = cursor.fetchall()
print(f"\nAnzahl Birken-Hotspots: {len(zonen)}")

for zone in zonen[:3]:
    print(f"  Zentrum: ({zone[0]:.5f}, {zone[1]:.5f}), Radius: {zone[2]}m, Bäume: {zone[3]}")

conn.close()
```

### Python: Alle Baumarten auflisten

```python
import sqlite3

conn = sqlite3.connect("vienna.db")
cursor = conn.cursor()

cursor.execute("""
    SELECT genus_de, genus_en, COUNT(*) as anzahl 
    FROM trees 
    WHERE genus_de IS NOT NULL AND genus_de != '' 
    GROUP BY genus_de 
    ORDER BY anzahl DESC
""")

print("Baumarten in Wien:")
print(f"{'Deutsch':<20} {'Englisch':<20} {'Anzahl':>8}")
print("-" * 50)

for row in cursor.fetchall():
    print(f"{row[0]:<20} {row[1] or '?':<20} {row[2]:>8}")

conn.close()
```

### Kotlin (Android): Bäume in der Nähe finden

```kotlin
// Falls du die Datenbank direkt in einer Android-App verwenden möchtest:
val db = SQLiteDatabase.openDatabase("pfad/zur/vienna.db", null, SQLiteDatabase.OPEN_READONLY)

val cursor = db.rawQuery("""
    SELECT id, lat, lon, genus_de, species_de 
    FROM trees 
    WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
""", arrayOf(
    (meinBreitengrad - 0.005).toString(),  // ca. 500m südlich
    (meinBreitengrad + 0.005).toString(),  // ca. 500m nördlich
    (meinLaengengrad - 0.007).toString(),  // ca. 500m westlich
    (meinLaengengrad + 0.007).toString()   // ca. 500m östlich
))

while (cursor.moveToNext()) {
    val name = cursor.getString(3)   // genus_de
    val lat  = cursor.getDouble(1)
    val lon  = cursor.getDouble(2)
    println("$name bei ($lat, $lon)")
}
cursor.close()
db.close()
```

### JavaScript (Node.js): Datenbank analysieren

```javascript
// npm install better-sqlite3
const Database = require('better-sqlite3');
const db = new Database('vienna.db', { readonly: true });

// Top 10 häufigste Baumarten
const rows = db.prepare(`
    SELECT genus_de, genus_en, COUNT(*) as count 
    FROM trees 
    WHERE genus_de IS NOT NULL 
    GROUP BY genus_de 
    ORDER BY count DESC 
    LIMIT 10
`).all();

console.log('Top 10 Baumarten in Wien:');
rows.forEach(row => {
    console.log(`  ${row.genus_de} (${row.genus_en}): ${row.count} Bäume`);
});

// Geofence-Zonen für Allergiker
const allergen = 'Hasel';
const zones = db.prepare(`
    SELECT lat, lon, radius, count 
    FROM geofences 
    WHERE genus_de = ?
`).all(allergen);

console.log(`\n${allergen}-Hotspots: ${zones.length} Zonen`);
db.close();
```

---

## Nutzung

1. Hole dir die `catalog.json` (siehe Schritt 1).
2. Lade die gewünschte Stadt-Datenbank herunter und entpacke sie (Schritt 2).
3. Optional: Verifiziere die Signatur (Schritt 3).
4. Öffne die `.db` Datei mit einer beliebigen SQLite-Bibliothek deiner Wahl.
5. Abfragen wie in den Code-Beispielen oben gezeigt.

Die Daten stehen unter der **MIT License** – du darfst sie frei verwenden, auch in kommerziellen Projekten.

[Zurück zur Startseite](../README.md) | [English Version](data_structure_en.md)

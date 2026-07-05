#!/usr/bin/env bash
# Baut den Photon-Suchindex aus den pro-Stadt-Geocoder-Dateien des BaumRadar-Katalogs
# (CITY_FILTER wie beim Loader; leer = alle Städte mit Geocoder-Daten) und startet
# dann den Suchdienst. Der Index liegt im Volume und wird nur neu gebaut, wenn sich
# die geocoderVersion einer gewählten Stadt geändert hat.
set -euo pipefail

DATA_DIR=/photon
JAR=/photon-app/photon.jar
CATALOG_URL="${CATALOG_URL:?CATALOG_URL fehlt}"
CITY_FILTER="${CITY_FILTER:-}"

mkdir -p "$DATA_DIR"
echo "Katalog laden: $CATALOG_URL"
CAT="$(curl -fsSL "$CATALOG_URL")"

# Zeilen: id<TAB>version<TAB>url1 url2 …  (Chunks in Reihenfolge, sonst die Einzeldatei)
mapfile -t ROWS < <(jq -r --arg f "$CITY_FILTER" '
  ($f | split(",") | map(select(length > 0))) as $ids
  | .cities[]
  | select(.geocoderUrl != null)
  | .id as $cid
  | select(($ids | length) == 0 or ($ids | index($cid)))
  | [$cid, (.geocoderVersion // ""), ((.geocoderUrlChunks // [.geocoderUrl]) | join(" "))]
  | @tsv' <<<"$CAT")

if [ "${#ROWS[@]}" -eq 0 ]; then
  echo "FEHLER: keine Geocoder-Daten im Katalog gefunden (CITY_FILTER='$CITY_FILTER')."
  exit 1
fi

WANT="$(printf '%s\n' "${ROWS[@]}" | awk -F'\t' '{print $1"="$2}' | sort | paste -sd';' -)"
MARKER="$DATA_DIR/imported_versions"

if [ -d "$DATA_DIR/photon_data" ] && [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$WANT" ]; then
  echo "Photon-Index ist aktuell — Import übersprungen."
else
  echo "Baue Photon-Index für: $(printf '%s\n' "${ROWS[@]}" | cut -f1 | paste -sd', ' -)"
  rm -rf "$DATA_DIR/photon_data"
  TMP="$(mktemp -d)"
  # Photon 1.x: EIN `import` pro Datenbank (jeder Aufruf verwirft bestehende Inhalte).
  # Deshalb alle Stadt-Dateien zu EINEM Dump mergen: erste Datei komplett, bei den
  # weiteren die 2 Präambel-Zeilen (Kopf + CountryInfo) überspringen.
  first=1
  for row in "${ROWS[@]}"; do
    id="$(cut -f1 <<<"$row")"
    urls="$(cut -f3 <<<"$row")"
    for u in $urls; do
      echo "  [$id] lade $(basename "$u") …"
      curl -fsSL "$u" >> "$TMP/$id.jsonl.gz"
    done
    if [ "$first" = "1" ]; then
      gunzip -c "$TMP/$id.jsonl.gz" >> "$TMP/merged.jsonl"
      first=0
    else
      gunzip -c "$TMP/$id.jsonl.gz" | tail -n +3 >> "$TMP/merged.jsonl"
    fi
    rm -f "$TMP/$id.jsonl.gz"
  done
  echo "  Importiere $(du -m "$TMP/merged.jsonl" | cut -f1) MB (unkomprimiert) …"
  java ${IMPORT_JAVA_OPTS:--Xmx2g} -jar "$JAR" import -data-dir "$DATA_DIR" \
    -import-file "$TMP/merged.jsonl"
  rm -rf "$TMP"
  echo "$WANT" > "$MARKER"
  echo "Import abgeschlossen."
fi

echo "Starte Photon auf :2322 …"
exec java ${JAVA_OPTS:--Xmx1g} -jar "$JAR" serve -data-dir "$DATA_DIR" \
  -listen-ip 0.0.0.0 -listen-port 2322

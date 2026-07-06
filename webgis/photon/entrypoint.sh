#!/usr/bin/env bash
# Baut den Photon-Suchindex aus den pro-Stadt-Geocoder-Dateien des BaumRadar-Katalogs
# (CITY_FILTER wie beim Loader; leer = alle Städte mit Geocoder-Daten) und startet
# dann den Suchdienst. Der Index liegt im Volume und wird nur neu gebaut, wenn sich
# die geocoderVersion einer gewählten Stadt geändert hat.
#
# Datenquellen-Vorrang: Liegt das Repo lokal vor (Mount /local-data = docs/data),
# werden Katalog und Häppchen DIREKT von dort gelesen — kein GitHub-Download.
#
# Lade-Fortschritt wird nach /status/photon.json gespiegelt (geteiltes Volume),
# damit der Web-Client den Zustand anzeigen kann.
set -euo pipefail

DATA_DIR=/photon
JAR=/photon-app/photon.jar
CATALOG_URL="${CATALOG_URL:?CATALOG_URL fehlt}"
CITY_FILTER="${CITY_FILTER:-}"
LOCAL_DATA=/local-data

# Phase + Detail für die Status-Anzeige des Web-Clients (Best-Effort).
status() {
  [ -d /status ] || return 0
  printf '{"phase":"%s","detail":"%s","updatedAt":"%s"}\n' \
    "$1" "${2:-}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > /status/photon.json 2>/dev/null || true
}

mkdir -p "$DATA_DIR"
status "startet"

if [ -f "$LOCAL_DATA/catalog.json" ]; then
  echo "Katalog: nutze lokales Repo ($LOCAL_DATA/catalog.json)"
  CAT="$(cat "$LOCAL_DATA/catalog.json")"
else
  echo "Katalog laden: $CATALOG_URL"
  CAT="$(curl -fsSL "$CATALOG_URL")"
fi

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
  status "Fehler" "keine Geocoder-Daten im Katalog (CITY_FILTER=$CITY_FILTER)"
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
  total="${#ROWS[@]}"
  n=0
  first=1
  for row in "${ROWS[@]}"; do
    id="$(cut -f1 <<<"$row")"
    urls="$(cut -f3 <<<"$row")"
    n=$((n + 1))
    status "lädt Stadt-Daten" "$n/$total: $id"
    if [ -f "$LOCAL_DATA/geocoder_$id.jsonl.gz" ]; then
      echo "  [$id] nutze lokale Datei"
      cat "$LOCAL_DATA/geocoder_$id.jsonl.gz" > "$TMP/$id.jsonl.gz"
    elif [ -f "$LOCAL_DATA/geocoder_$id.jsonl.gz.001" ]; then
      echo "  [$id] nutze lokale Chunks"
      cat "$LOCAL_DATA/geocoder_$id.jsonl.gz."0* > "$TMP/$id.jsonl.gz"
    else
      for u in $urls; do
        echo "  [$id] lade $(basename "$u") …"
        curl -fsSL "$u" >> "$TMP/$id.jsonl.gz"
      done
    fi
    if [ "$first" = "1" ]; then
      gunzip -c "$TMP/$id.jsonl.gz" >> "$TMP/merged.jsonl"
      first=0
    else
      gunzip -c "$TMP/$id.jsonl.gz" | tail -n +3 >> "$TMP/merged.jsonl"
    fi
    rm -f "$TMP/$id.jsonl.gz"
  done
  status "baut Suchindex" "$(du -m "$TMP/merged.jsonl" | cut -f1) MB Rohdaten"
  echo "  Importiere $(du -m "$TMP/merged.jsonl" | cut -f1) MB (unkomprimiert) …"
  java ${IMPORT_JAVA_OPTS:--Xmx2g} -jar "$JAR" import -data-dir "$DATA_DIR" \
    -import-file "$TMP/merged.jsonl"
  rm -rf "$TMP"
  echo "$WANT" > "$MARKER"
  echo "Import abgeschlossen."
fi

status "startet Suchdienst"
echo "Starte Photon auf :2322 …"
exec java ${JAVA_OPTS:--Xmx1g} -jar "$JAR" serve -data-dir "$DATA_DIR" \
  -listen-ip 0.0.0.0 -listen-port 2322
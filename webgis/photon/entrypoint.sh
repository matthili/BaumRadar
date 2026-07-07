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
  # Absturz-Wächter: `restart: unless-stopped` würde einen crashenden Import ENDLOS
  # wiederholen (~18 GB Schreiblast pro Runde auf die SSD). Nach 3 Fehlversuchen
  # ehrlich stehen bleiben. Der Zähler merkt sich die Java-Optionen der Fehlversuche:
  # GEÄNDERTE Optionen (= der Nutzer hat reagiert) setzen ihn automatisch zurück —
  # sonst wäre die dokumentierte Abhilfe (Heap erhöhen, neu starten) wirkungslos.
  ATTEMPTS_F="$DATA_DIR/import_attempts"
  OPTS_NOW="${IMPORT_JAVA_OPTS:--Xmx4g}"
  ATTEMPTS=0
  if [ -f "$ATTEMPTS_F" ]; then
    PREV="$(cat "$ATTEMPTS_F")"   # Format: <anzahl>|<java-optionen>
    if [ "${PREV#*|}" = "$OPTS_NOW" ]; then ATTEMPTS="${PREV%%|*}"; fi
    case "$ATTEMPTS" in ''|*[!0-9]*) ATTEMPTS=0 ;; esac
  fi
  if [ "$ATTEMPTS" -ge 3 ]; then
    rm -rf "$DATA_DIR/tmp"
    status "Fehler" "Import ${ATTEMPTS}x abgestürzt (mit $OPTS_NOW) — meist zu wenig Speicher: PHOTON_IMPORT_JAVA_OPTS in .env erhöhen (z. B. -Xmx6g), dann Container neu starten. Details: docker logs baumradar-photon"
    echo "FEHLER: Import bereits ${ATTEMPTS}x abgestürzt (mit $OPTS_NOW) — halte an, statt endlos neu zu versuchen."
    echo "Abhilfe: PHOTON_IMPORT_JAVA_OPTS in .env erhöhen und Container neu starten — geänderte Einstellungen setzen den Zähler automatisch zurück."
    sleep infinity
  fi
  printf '%s|%s' "$((ATTEMPTS + 1))" "$OPTS_NOW" > "$ATTEMPTS_F"

  echo "Baue Photon-Index für: $(printf '%s\n' "${ROWS[@]}" | cut -f1 | paste -sd', ' -)"
  rm -rf "$DATA_DIR/photon_data"
  # Arbeitsdateien INS VOLUME legen, nicht nach /tmp (= Container-Layer): der
  # gemergte Dump erreicht bei 19 Städten >20 GB — im Layer ist das ein unsicht-
  # barer Plattenfresser und bleibt nach abgebrochenen Importen liegen. Im Volume
  # ist er sichtbar (docker system df), wird hier von Resten befreit und von
  # `down -v` mit entsorgt.
  TMP="$DATA_DIR/tmp"
  rm -rf "$TMP"
  mkdir -p "$TMP"
  # Photon 1.x: EIN `import` pro Datenbank (jeder Aufruf verwirft bestehende Inhalte).
  # Deshalb alle Stadt-Dateien zu EINEM Dump mergen: erste Datei komplett, bei den
  # weiteren die 2 Präambel-Zeilen (Kopf + CountryInfo) überspringen.
  # Merge + DEDUPLIZIERUNG in einem Strom. Die Stadt-Ränder überlappen sich bewusst
  # (Ruhrgebiet!), Photon importiert aber mit op_type=create: ein doppelter place_id
  # ist dort ein 409-Fehler und lässt den GESAMTEN Import platzen ("N failed items
  # in bulk"). Deshalb: jq zerlegt Batch-Zeilen (content[] kann mehrere Orte tragen)
  # in Ein-Ort-Zeilen, awk lässt je place_id nur die erste Fundstelle durch (Reihen-
  # folge und damit die Länder-Sortierung bleiben erhalten; das Duplikat trägt
  # dieselben Daten). RAM: einige hundert MB für Millionen gesehener IDs.
  # Fortschritts-Ausgaben gehen auf stderr — stdout IST hier der Datenstrom.
  DUPF="$TMP/duplicate_count"
  total="${#ROWS[@]}"
  n=0
  first=1
  {
    for row in "${ROWS[@]}"; do
      id="$(cut -f1 <<<"$row")"
      urls="$(cut -f3 <<<"$row")"
      n=$((n + 1))
      status "lädt Stadt-Daten" "$n/$total: $id"
      # Lokale Einzeldatei direkt aus dem Repo-Mount lesen (keine Kopie); Chunks und
      # Downloads landen erst vollständig in $TMP — nur komplette Dateien werden gemerged.
      if [ -f "$LOCAL_DATA/geocoder_$id.jsonl.gz" ]; then
        echo "  [$id] nutze lokale Datei" >&2
        SRC="$LOCAL_DATA/geocoder_$id.jsonl.gz"
      elif [ -f "$LOCAL_DATA/geocoder_$id.jsonl.gz.001" ]; then
        echo "  [$id] nutze lokale Chunks" >&2
        cat "$LOCAL_DATA/geocoder_$id.jsonl.gz."0* > "$TMP/$id.jsonl.gz"
        SRC="$TMP/$id.jsonl.gz"
      else
        for u in $urls; do
          echo "  [$id] lade $(basename "$u") …" >&2
          curl -fsSL "$u" >> "$TMP/$id.jsonl.gz"
        done
        SRC="$TMP/$id.jsonl.gz"
      fi
      if [ "$first" = "1" ]; then
        gunzip -c "$SRC"
        first=0
      else
        gunzip -c "$SRC" | tail -n +3
      fi
      rm -f "$TMP/$id.jsonl.gz"
    done
  } \
  | jq -c 'if .type == "Place" then (.content[] | {type: "Place", content: [.]}) else . end' \
  | awk -v dupf="$DUPF" '
      /"type":"Place"/ {
        i = index($0, "\"place_id\":\"")
        if (i > 0) {
          r = substr($0, i + 12); q = index(r, "\""); id = substr(r, 1, q - 1)
          if (id in seen) { dup++; next }
          seen[id] = 1
        }
      }
      { print }
      END { print dup + 0 > dupf }
    ' > "$TMP/merged.jsonl"
  DUPS="$(cat "$DUPF" 2>/dev/null || echo 0)"
  echo "  Duplikate aus Rand-Überlappungen übersprungen: $DUPS"
  MB="$(du -m "$TMP/merged.jsonl" | cut -f1)"
  status "baut Suchindex" "$MB MB Rohdaten"
  echo "  Importiere $MB MB (unkomprimiert) …"
  # Herzschlag: bei vielen Städten läuft der Import stundenlang in EINEM Java-Aufruf.
  # Ohne frische Stempel meldet das Status-Overlay nach 15 min fälschlich "hängt evtl.".
  S0=$SECONDS
  ( while sleep 60; do status "baut Suchindex" "$MB MB Rohdaten, läuft seit $(( (SECONDS - S0) / 60 )) min (Versuch $((ATTEMPTS + 1)))"; done ) &
  HEARTBEAT=$!
  if java ${IMPORT_JAVA_OPTS:--Xmx4g} -jar "$JAR" import -data-dir "$DATA_DIR" \
       -import-file "$TMP/merged.jsonl"; then
    kill "$HEARTBEAT" 2>/dev/null || true
  else
    kill "$HEARTBEAT" 2>/dev/null || true
    rm -rf "$TMP"
    status "Fehler" "Import abgestürzt (Versuch $((ATTEMPTS + 1)) von 3) — docker logs baumradar-photon"
    echo "FEHLER: Photon-Import abgestürzt (Versuch $((ATTEMPTS + 1)) von 3)."
    exit 1
  fi
  rm -rf "$TMP"
  rm -f "$ATTEMPTS_F"
  echo "$WANT" > "$MARKER"
  echo "Import abgeschlossen."
fi

status "startet Suchdienst"
echo "Starte Photon auf :2322 …"
exec java ${JAVA_OPTS:--Xmx1g} -jar "$JAR" serve -data-dir "$DATA_DIR" \
  -listen-ip 0.0.0.0 -listen-port 2322
#!/usr/bin/env bash
# Baut aus den DE/AT/CH-Geofabrik-PBFs einen "Insel"-Graph nur für die Städte:
# je Stadt die (leicht gepufferte) Katalog-BBox per osmium extrahieren, dann alles
# zu /data/island.osm.pbf mergen. Länder-PBFs werden in /osm gecacht (Volume).
set -euo pipefail

CATALOG_URL="${CATALOG_URL:?CATALOG_URL fehlt}"
MARGIN="${BBOX_MARGIN_DEG:-0.03}"     # Grad Puffer je Seite, damit Rand-Routen gehen
CITY_FILTER="${CITY_FILTER:-}"        # kommagetrennt; leer = alle Städte
OUT="/data/island.osm.pbf"
mkdir -p /osm /data

# Phase für die Status-Anzeige des Web-Clients (Best-Effort).
status() {
  [ -d /status ] || return 0
  printf '{"phase":"%s","detail":"%s","updatedAt":"%s"}\n' \
    "$1" "${2:-}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > /status/graph-builder.json 2>/dev/null || true
}
status "startet"

# Je Land: Primärquelle Geofabrik + verifizierter Ausweich-Mirror (Reihenfolge = Priorität).
# Geofabrik kann ausfallen/drosseln (2026-07-06 live erlebt: Timeouts + kaputte 302);
# GWDG spiegelt FLACH (kein europe/-Unterordner!), osm.fr schneidet eigene Extrakte.
declare -A URLS=(
  [Deutschland]="https://download.geofabrik.de/europe/germany-latest.osm.pbf https://ftp5.gwdg.de/pub/misc/openstreetmap/download.geofabrik.de/germany-latest.osm.pbf"
  [Österreich]="https://download.geofabrik.de/europe/austria-latest.osm.pbf https://download.openstreetmap.fr/extracts/europe/austria.osm.pbf"
  [Schweiz]="https://download.geofabrik.de/europe/switzerland-latest.osm.pbf https://download.openstreetmap.fr/extracts/europe/switzerland.osm.pbf"
)
declare -A PBF=(
  [Deutschland]="/osm/germany.osm.pbf"
  [Österreich]="/osm/austria.osm.pbf"
  [Schweiz]="/osm/switzerland.osm.pbf"
)

# Ein Land laden: Quellen der Reihe nach, bis zu 3 Runden. Statt eines Gesamt-Timeouts
# (Erstdownloads dürfen lange dauern) bricht --speed-limit/--speed-time nur STEHENDE
# Transfers ab (<10 KiB/s für 60 s — auch "verbunden, aber keine Antwort").
# Abbruchsicher: erst als .part laden, dann atomar umbenennen. Das .part wird nur bei
# DERSELBEN Quelle fortgesetzt (Merkzettel .part.src) — Mirrors tragen nicht byte-
# identische Dateien, ein quellgemischtes .part wäre still korrupt.
fetch_country() {
  local country="$1" dest="$2"; shift 2
  local round url host rc hb n=0
  for round in 1 2 3; do
    for url in "$@"; do
      n=$((n+1)); host="${url#*//}"; host="${host%%/*}"
      echo "$country: lade von $host (Anlauf $n) — $url"
      status "lädt Länder-Daten" "$country von $host (einmalig, mehrere GB; Anlauf $n)"
      if [ -f "$dest.part.src" ] && [ "$(cat "$dest.part.src")" != "$url" ]; then rm -f "$dest.part"; fi
      printf '%s' "$url" > "$dest.part.src"
      # Herzschlag mit Fortschritt: große Downloads (DE ~5 GB) dauern >15 min — ohne
      # frische Stempel meldet das Status-Overlay fälschlich "hängt evtl.".
      ( while sleep 60; do
          mb="$(du -m "$dest.part" 2>/dev/null | cut -f1)"
          status "lädt Länder-Daten" "$country von $host: ${mb:-0} MB geladen"
        done ) &
      hb=$!
      if curl -fSL --connect-timeout 20 --speed-limit 10240 --speed-time 60 -C - "$url" -o "$dest.part"; then
        kill "$hb" 2>/dev/null || true
        mv "$dest.part" "$dest"; rm -f "$dest.part.src"; return 0
      else
        rc=$?
        kill "$hb" 2>/dev/null || true
        echo "$country: $host liefert nicht (curl-Exit $rc) — probiere weiter …"
      fi
    done
    if [ "$round" -lt 3 ]; then
      echo "$country: alle Quellen fehlgeschlagen — 30 s Pause, dann Runde $((round+1)) …"
      status "lädt Länder-Daten" "$country: Quellen antworten nicht, neuer Versuch in 30 s"
      sleep 30
    fi
  done
  status "Fehler" "$country-Download scheitert an allen Quellen"
  echo "$country: Download endgültig fehlgeschlagen (Quellen: $*)." >&2
  return 1
}

echo "Katalog laden: $CATALOG_URL"
CAT="$(curl -fsSL "$CATALOG_URL")"

# Extract-Configs je Land VORAB berechnen — daraus entsteht auch der "Bauplan" (SPEC):
# gewählte Städte, ihre BBoxen und der Rand. Ist der Bauplan unverändert und die Insel
# vorhanden, gibt es nichts zu tun (gleiche Idee wie Photons imported_versions):
# erneute Starts kosten Sekunden statt einer kompletten Extraktion, und GraphHoppers
# Graph-Cache bleibt gültig, weil die Insel unangetastet bleibt.
declare -A EXMAP NMAP
SPEC="margin=$MARGIN"
for COUNTRY in Deutschland Österreich Schweiz; do
  # osmium-Extract-Config aus den Katalog-BBoxen. Katalog-BBox = [minLat,minLon,maxLat,maxLon];
  # osmium erwartet bbox = [left,bottom,right,top] = [minLon,minLat,maxLon,maxLat] (+ Puffer).
  EXMAP[$COUNTRY]="$(jq -c --arg c "$COUNTRY" --argjson m "$MARGIN" --arg f "$CITY_FILTER" '
    ($f | split(",") | map(select(length>0))) as $ids
    | .cities[]
    | select(.country==$c)
    | (.id) as $cid
    | select(($ids|length)==0 or ($ids|index($cid)))
    | {output:("city_"+.id+".osm.pbf"),
       bbox:[(.boundingBox[1]-$m),(.boundingBox[0]-$m),(.boundingBox[3]+$m),(.boundingBox[2]+$m)]}
  ' <<<"$CAT" | jq -sc '.')"
  NMAP[$COUNTRY]="$(jq 'length' <<<"${EXMAP[$COUNTRY]}")"
  if [ "${NMAP[$COUNTRY]}" -gt 0 ]; then SPEC="$SPEC|$COUNTRY:${EXMAP[$COUNTRY]}"; fi
done

MARKER="/data/island.marker"
if [ -f "$OUT" ] && [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$SPEC" ]; then
  echo "Insel-PBF ist aktuell (Städte, BBoxen und Rand unverändert) — überspringe Extraktion."
  echo "Erzwungener Neubau (z. B. für frischere OSM-Daten): Volume 'routingdata' löschen."
  status "fertig" "Insel aktuell — übersprungen"
  exit 0
fi
# Marker erst NACH erfolgreichem Merge schreiben — und vor dem Bau entfernen, damit
# ein abgebrochener Lauf nie eine halbe Insel als "aktuell" hinterlässt.
rm -f "$MARKER"

ALL=()
for COUNTRY in Deutschland Österreich Schweiz; do
  EX="${EXMAP[$COUNTRY]}"
  N="${NMAP[$COUNTRY]}"
  if [ "$N" -eq 0 ]; then echo "$COUNTRY: keine (passenden) Städte, überspringe."; continue; fi

  if [ ! -f "${PBF[$COUNTRY]}" ]; then
    echo "$COUNTRY-PBF laden (groß, wird danach gecacht)…"
    fetch_country "$COUNTRY" "${PBF[$COUNTRY]}" ${URLS[$COUNTRY]}
  else
    echo "$COUNTRY: nutze gecachtes ${PBF[$COUNTRY]}"
  fi

  # RAM-schonend in Batches extrahieren: osmium hält pro Extrakt eine Bitmap über
  # den GLOBALEN Node-ID-Raum (~1,5 GB — unabhängig von der Stadtgröße!). 12 Städte
  # in einem Lauf ≈ 18 GB → OOM-Kill (exit 137) auf normalen Docker-VMs. Default 1
  # (gemessen: läuft ab ~3 GB VM-RAM); auf großen Maschinen per EXTRACT_BATCH erhöhen
  # (~1,5 GB je Stadt im Batch). Die Randeffekte der "simple"-Strategie fängt der
  # ohnehin vorhandene BBox-Rand (BBOX_MARGIN_DEG) ab.
  BATCH="${EXTRACT_BATCH:-1}"
  i=0
  while [ "$i" -lt "$N" ]; do
    CFG="$(mktemp)"
    jq -n --argjson ex "$EX" --argjson i "$i" --argjson n "$BATCH" \
       '{directory:"/osm", extracts: $ex[$i:$i+$n]}' > "$CFG"
    upper=$(( i + BATCH > N ? N : i + BATCH ))
    echo "$COUNTRY: extrahiere Stadt-BBox $((i+1))–$upper von $N …"
    status "schneidet Stadt-Ausschnitte" "$COUNTRY $((i+1))-$upper/$N"
    osmium extract -s simple -c "$CFG" --overwrite "${PBF[$COUNTRY]}"
    rm -f "$CFG"
    i=$(( i + BATCH ))
  done
  while IFS= read -r o; do ALL+=("/osm/$o"); done < <(jq -r '.[].output' <<<"$EX")
done

if [ "${#ALL[@]}" -eq 0 ]; then status "Fehler" "keine Extrakte erzeugt"; echo "Keine Extrakte erzeugt — nichts zu tun."; exit 1; fi
echo "Merge ${#ALL[@]} Stadt-Extrakt(e) → $OUT"
status "führt Ausschnitte zusammen" "${#ALL[@]} Städte"
osmium merge --overwrite "${ALL[@]}" -o "$OUT"
printf '%s' "$SPEC" > "$MARKER"
status "fertig"
echo "Insel-PBF fertig:"
osmium fileinfo "$OUT" | grep -iE "size|nodes|ways|relations|box" || true

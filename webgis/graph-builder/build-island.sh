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

declare -A URL=(
  [Deutschland]="https://download.geofabrik.de/europe/germany-latest.osm.pbf"
  [Österreich]="https://download.geofabrik.de/europe/austria-latest.osm.pbf"
  [Schweiz]="https://download.geofabrik.de/europe/switzerland-latest.osm.pbf"
)
declare -A PBF=(
  [Deutschland]="/osm/germany.osm.pbf"
  [Österreich]="/osm/austria.osm.pbf"
  [Schweiz]="/osm/switzerland.osm.pbf"
)

echo "Katalog laden: $CATALOG_URL"
CAT="$(curl -fsSL "$CATALOG_URL")"

ALL=()
for COUNTRY in Deutschland Österreich Schweiz; do
  # osmium-Extract-Config aus den Katalog-BBoxen. Katalog-BBox = [minLat,minLon,maxLat,maxLon];
  # osmium erwartet bbox = [left,bottom,right,top] = [minLon,minLat,maxLon,maxLat] (+ Puffer).
  EX="$(jq -c --arg c "$COUNTRY" --argjson m "$MARGIN" --arg f "$CITY_FILTER" '
    ($f | split(",") | map(select(length>0))) as $ids
    | .cities[]
    | select(.country==$c)
    | (.id) as $cid
    | select(($ids|length)==0 or ($ids|index($cid)))
    | {output:("city_"+.id+".osm.pbf"),
       bbox:[(.boundingBox[1]-$m),(.boundingBox[0]-$m),(.boundingBox[3]+$m),(.boundingBox[2]+$m)]}
  ' <<<"$CAT" | jq -s '.')"
  N="$(jq 'length' <<<"$EX")"
  if [ "$N" -eq 0 ]; then echo "$COUNTRY: keine (passenden) Städte, überspringe."; continue; fi

  if [ ! -f "${PBF[$COUNTRY]}" ]; then
    echo "$COUNTRY-PBF laden (groß, wird danach gecacht)…"
    # Abbruchsicher: erst als .part laden (mit Resume, falls ein Vorlauf abbrach),
    # dann atomar umbenennen — der finale Name existiert nur vollständig.
    curl -fSL -C - "${URL[$COUNTRY]}" -o "${PBF[$COUNTRY]}.part"
    mv "${PBF[$COUNTRY]}.part" "${PBF[$COUNTRY]}"
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
    osmium extract -s simple -c "$CFG" --overwrite "${PBF[$COUNTRY]}"
    rm -f "$CFG"
    i=$(( i + BATCH ))
  done
  while IFS= read -r o; do ALL+=("/osm/$o"); done < <(jq -r '.[].output' <<<"$EX")
done

if [ "${#ALL[@]}" -eq 0 ]; then echo "Keine Extrakte erzeugt — nichts zu tun."; exit 1; fi
echo "Merge ${#ALL[@]} Stadt-Extrakt(e) → $OUT"
osmium merge --overwrite "${ALL[@]}" -o "$OUT"
echo "Insel-PBF fertig:"
osmium fileinfo "$OUT" | grep -iE "size|nodes|ways|relations|box" || true

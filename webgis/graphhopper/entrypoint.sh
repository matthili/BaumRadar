#!/usr/bin/env bash
set -euo pipefail
PBF=/data/island.osm.pbf
CACHE=/data/graph-cache

if [ ! -f "$PBF" ]; then
  echo "FEHLT: $PBF — bitte zuerst den graph-builder laufen lassen."
  exit 1
fi

# Graph neu bauen, wenn das Insel-PBF neuer ist als der Cache (frische Städte).
if [ -d "$CACHE" ] && [ "$PBF" -nt "$CACHE" ]; then
  echo "Insel-PBF ist neuer als der Graph-Cache — verwerfe den Cache."
  rm -rf "$CACHE"
fi

exec java ${JAVA_OPTS:--Xmx2g -Xms1g} -jar graphhopper-web.jar server config.yml

#!/usr/bin/env bash
set -euo pipefail
PBF=/data/island.osm.pbf
CACHE=/data/graph-cache

# Phase für die Status-Anzeige des Web-Clients (Best-Effort).
status() {
  [ -d /status ] || return 0
  printf '{"phase":"%s","detail":"%s","updatedAt":"%s"}\n' \
    "$1" "${2:-}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > /status/graphhopper.json 2>/dev/null || true
}

if [ ! -f "$PBF" ]; then
  status "Fehler" "island.osm.pbf fehlt - graph-builder zuerst"
  echo "FEHLT: $PBF — bitte zuerst den graph-builder laufen lassen."
  exit 1
fi

# Graph neu bauen, wenn das Insel-PBF neuer ist als der Cache (frische Städte).
if [ -d "$CACHE" ] && [ "$PBF" -nt "$CACHE" ]; then
  echo "Insel-PBF ist neuer als der Graph-Cache — verwerfe den Cache."
  rm -rf "$CACHE"
fi

if [ -d "$CACHE" ]; then
  status "startet Routing-Dienst"
else
  MB="$(du -m "$PBF" | cut -f1)"
  status "baut Routing-Graph" "$MB MB OSM-Daten"
  # Herzschlag, bis der Dienst antwortet: der Graph-Bau läuft komplett im exec'ten
  # Java-Prozess — ohne frische Stempel meldet das Overlay nach 15 min "hängt evtl.".
  ( S0=$SECONDS
    while sleep 60; do
      curl -sf -o /dev/null http://127.0.0.1:8989/info && break || true
      status "baut Routing-Graph" "$MB MB OSM-Daten, läuft seit $(( (SECONDS - S0) / 60 )) min"
    done ) &
fi

exec java ${JAVA_OPTS:--Xmx2g -Xms1g} -jar graphhopper-web.jar server config.yml

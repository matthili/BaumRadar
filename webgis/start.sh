#!/usr/bin/env bash
# BaumRadar WebGIS - Ein-Befehl-Start (Linux/macOS). Routing + Adresssuche sind STANDARD.
#   ./start.sh                               Voll-Stack: Karte + Routing + Adresssuche
#   ./start.sh --cities zug,wien             nur bestimmte Städte (schneller Erststart)
#   ./start.sh --no-routing --no-geocoding   nur die Karte (kleinster Download)
#   ./start.sh --down                        alles stoppen
# Beim ersten Lauf wird .env aus .env.example erzeugt - mit ZUFÄLLIGEN Passwörtern.
set -euo pipefail
cd "$(dirname "$0")"

command -v docker >/dev/null || { echo "FEHLER: Docker ist nicht installiert."; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "FEHLER: Docker Compose v2 fehlt."; exit 1; }

# Lokal ist der Sinn der Sache: Routing + Adresssuche laufen standardmäßig mit.
ROUTING=1
GEOCODING=1
CITIES=""
DOWN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --no-routing)   ROUTING=0 ;;
    --no-geocoding) GEOCODING=0 ;;
    --cities)       CITIES="${2:-}"; shift ;;
    --down)         DOWN=1 ;;
    *) echo "Unbekannte Option: $1"; exit 2 ;;
  esac
  shift
done
PROFILES=()
[ "$ROUTING" = "1" ]   && PROFILES+=(--profile routing)
[ "$GEOCODING" = "1" ] && PROFILES+=(--profile geocoding)

if [ "$DOWN" = "1" ]; then
  docker compose --profile routing --profile geocoding down
  echo "Gestoppt. Daten-Volumes bleiben erhalten (kompletter Reset: docker compose down -v)."
  exit 0
fi

if [ ! -f .env ]; then
  secret() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24; }
  PG_PW="$(secret)"; GS_PW="$(secret)"
  sed -e "s/^PG_PASSWORD=.*/PG_PASSWORD=${PG_PW}/" \
      -e "s/^GEOSERVER_PASSWORD=.*/GEOSERVER_PASSWORD=${GS_PW}/" \
      .env.example > .env
  echo ".env angelegt — mit zufälligen Passwörtern (einsehbar in webgis/.env)."
fi

[ -n "$CITIES" ] && export CITY_FILTER="$CITIES"
# Dem Web-Client mitteilen, was dieser Stack laden SOLL (für die Status-Anzeige).
export STACK_ROUTING="$ROUTING"
export STACK_GEOCODING="$GEOCODING"

echo "Baue und starte Container (erster Lauf lädt Basis-Images und Stadtdaten) ..."
docker compose "${PROFILES[@]}" up -d --build

WEB_PORT="$(grep -E '^WEB_PORT=' .env | cut -d= -f2)"; WEB_PORT="${WEB_PORT:-8082}"
GS_PORT="$(grep -E '^GEOSERVER_PORT=' .env | cut -d= -f2)"; GS_PORT="${GS_PORT:-8081}"

echo
echo "BaumRadar WebGIS läuft:"
echo "  Karte:              http://localhost:${WEB_PORT}"
echo "  GeoServer-Admin:    http://localhost:${GS_PORT}/geoserver  (Zugang: siehe .env; nur localhost)"
echo
echo "Der Datenimport läuft im Hintergrund — Fortschritt:  docker logs -f baumradar-loader"

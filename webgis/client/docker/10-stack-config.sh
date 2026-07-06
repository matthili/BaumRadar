#!/bin/sh
# Wird vom offiziellen nginx-Image beim Containerstart ausgeführt (docker-entrypoint.d).
# Schreibt die Stack-Konfiguration als stack.json ins Web-Root: der Client erfährt so,
# welche Module dieser Stack überhaupt laden SOLL (Statusanzeige: "lädt" vs. "deaktiviert").
set -e
routing="${STACK_ROUTING:-1}"
geocoding="${STACK_GEOCODING:-1}"
cities="${CITY_FILTER:-}"
[ "$routing" = "0" ] && routing=false || routing=true
[ "$geocoding" = "0" ] && geocoding=false || geocoding=true
printf '{"routing":%s,"geocoding":%s,"cityFilter":"%s"}\n' \
    "$routing" "$geocoding" "$cities" > /usr/share/nginx/html/stack.json

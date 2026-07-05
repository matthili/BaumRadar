# BaumRadar WebGIS - Ein-Befehl-Start.
#   .\start.cmd                        Basis-Stack (Karte, alle 19 Städte)
#   .\start.cmd -Cities zug,wien       nur bestimmte Städte (schneller Erststart)
#   .\start.cmd -Routing -Geocoding    zusätzlich Routing und/oder Adresssuche
#   .\start.cmd -Down                  alles stoppen
# Beim ersten Lauf wird .env aus .env.example erzeugt - mit ZUFÄLLIGEN Passwörtern.
param(
    [string]$Cities = "",
    [switch]$Routing,
    [switch]$Geocoding,
    [switch]$Down
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# Docker verfügbar?
try { docker compose version | Out-Null } catch {
    Write-Host "FEHLER: Docker (mit Compose v2) ist nicht verfügbar. Bitte Docker Desktop installieren/starten." -ForegroundColor Red
    exit 1
}

$profiles = @()
if ($Routing)   { $profiles += @("--profile", "routing") }
if ($Geocoding) { $profiles += @("--profile", "geocoding") }

if ($Down) {
    docker compose --profile routing --profile geocoding down
    Write-Host "Gestoppt. Daten-Volumes bleiben erhalten (kompletter Reset: docker compose down -v)."
    exit 0
}

# .env beim ersten Start anlegen - mit zufälligen Zugangsdaten statt der Demo-Defaults.
if (-not (Test-Path ".env")) {
    function New-Secret { -join ((48..57) + (97..122) + (65..90) | Get-Random -Count 24 | ForEach-Object { [char]$_ }) }
    $pgPw = New-Secret
    $gsPw = New-Secret
    (Get-Content ".env.example" -Raw) `
        -replace "(?m)^PG_PASSWORD=.*$", "PG_PASSWORD=$pgPw" `
        -replace "(?m)^GEOSERVER_PASSWORD=.*$", "GEOSERVER_PASSWORD=$gsPw" |
        Set-Content ".env" -Encoding utf8 -NoNewline
    Write-Host ".env angelegt - mit zufälligen Passwörtern (einsehbar in webgis\.env)." -ForegroundColor Green
}

if ($Cities) { $env:CITY_FILTER = $Cities }

Write-Host "Baue und starte Container (erster Lauf lädt Basis-Images und Stadtdaten) ..." -ForegroundColor Cyan
docker compose @profiles up -d --build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$envMap = @{}
Get-Content ".env" | Where-Object { $_ -match "^\s*([^#=]+)=(.*)$" } | ForEach-Object {
    $envMap[$Matches[1].Trim()] = $Matches[2].Trim()
}
$webPort = if ($envMap["WEB_PORT"]) { $envMap["WEB_PORT"] } else { "8082" }
$gsPort  = if ($envMap["GEOSERVER_PORT"]) { $envMap["GEOSERVER_PORT"] } else { "8081" }

Write-Host ""
Write-Host "BaumRadar WebGIS läuft:" -ForegroundColor Green
Write-Host "  Karte:              http://localhost:$webPort"
Write-Host "  GeoServer-Admin:    http://localhost:$gsPort/geoserver  (Zugang: siehe .env; nur localhost)"
Write-Host ""
Write-Host "Der Datenimport läuft im Hintergrund - Fortschritt:  docker logs -f baumradar-loader"
if ($Routing)   { Write-Host "Routing: erster Start lädt Länder-PBFs (mehrere GB) - docker logs -f baumradar-graph-builder" }
if ($Geocoding) { Write-Host "Adresssuche: erster Start lädt Stadt-Häppchen + baut den Index - docker logs -f baumradar-photon" }

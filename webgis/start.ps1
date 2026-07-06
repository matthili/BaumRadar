# BaumRadar WebGIS - Ein-Befehl-Start. Routing + Adresssuche sind STANDARD (lokal!).
#   .\start.cmd                           Voll-Stack: Karte + Routing + Adresssuche, alle 19 Städte
#   .\start.cmd -Cities zug,wien          nur bestimmte Städte (schneller Erststart, kleine Downloads)
#   .\start.cmd -NoRouting -NoGeocoding   nur die Karte (kleinster Download)
#   .\start.cmd -Down                     alles stoppen
# Beim ersten Lauf wird .env aus .env.example erzeugt - mit ZUFÄLLIGEN Passwörtern.
param(
    [string]$Cities = "",
    [switch]$NoRouting,
    [switch]$NoGeocoding,
    [switch]$Down
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# Docker verfügbar?
try { docker compose version | Out-Null } catch {
    Write-Host "FEHLER: Docker (mit Compose v2) ist nicht verfügbar. Bitte Docker Desktop installieren/starten." -ForegroundColor Red
    exit 1
}

# Lokal ist der Sinn der Sache: Routing + Adresssuche laufen standardmäßig mit.
$profiles = @()
if (-not $NoRouting)   { $profiles += @("--profile", "routing") }
if (-not $NoGeocoding) { $profiles += @("--profile", "geocoding") }

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
    # Explizite .NET-UTF-8-APIs statt Get-Content/Set-Content: Windows PowerShell 5.1
    # liest BOM-lose UTF-8-Dateien sonst als ANSI und brennt Umlaut-Salat in die .env.
    $tpl = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot ".env.example"), [System.Text.Encoding]::UTF8)
    $tpl = $tpl -replace "(?m)^PG_PASSWORD=.*$", "PG_PASSWORD=$pgPw" `
                -replace "(?m)^GEOSERVER_PASSWORD=.*$", "GEOSERVER_PASSWORD=$gsPw"
    [System.IO.File]::WriteAllText((Join-Path $PSScriptRoot ".env"), $tpl, [System.Text.UTF8Encoding]::new($false))
    Write-Host ".env angelegt - mit zufälligen Passwörtern (einsehbar in webgis\.env)." -ForegroundColor Green
}

if ($Cities) { $env:CITY_FILTER = $Cities }
# Dem Web-Client mitteilen, was dieser Stack laden SOLL (fuer die Status-Anzeige).
$env:STACK_ROUTING   = if ($NoRouting)   { "0" } else { "1" }
$env:STACK_GEOCODING = if ($NoGeocoding) { "0" } else { "1" }

Write-Host "Baue und starte Container (erster Lauf lädt Basis-Images und Stadtdaten) ..." -ForegroundColor Cyan
docker compose @profiles up -d --build
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Docker hat das Warten abgebrochen (z. B. '500 Internal Server Error' von Docker Desktop)." -ForegroundColor Yellow
    Write-Host "Die bereits gestarteten Container laufen im Hintergrund weiter - einfach .\start.cmd" -ForegroundColor Yellow
    Write-Host "erneut ausfuehren: der Start ist idempotent und setzt fort, wo er aufgehoert hat." -ForegroundColor Yellow
    exit $LASTEXITCODE
}

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
Write-Host "ACHTUNG: Dieser Datenimport benötigt mehrere Minuten"
if (-not $NoRouting)   { Write-Host "Routing: erster Start lädt Länder-PBFs (DE ~4 GB, AT/CH ~0,5 GB - nur benötigte Länder) und baut den Graph - docker logs -f baumradar-graphhopper" }
if (-not $NoGeocoding) { Write-Host "Adresssuche: erster Start lädt Stadt-Häppchen + baut den Index (einige Minuten) - docker logs -f baumradar-photon" }

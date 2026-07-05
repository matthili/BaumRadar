@echo off
rem BaumRadar WebGIS - Startskript (Windows). Argumente werden an start.ps1 gereicht,
rem z. B.:  start.cmd -Cities zug,wien -Routing -Geocoding   |   start.cmd -Down
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*

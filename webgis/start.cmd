@echo off
rem BaumRadar WebGIS - Startskript (Windows). Argumente werden an start.ps1 gereicht,
rem z. B.:  start.cmd -Cities zug,wien   |   start.cmd -NoRouting   |   start.cmd -Down
rem Bevorzugt PowerShell 7 (pwsh), faellt sonst auf Windows PowerShell 5.1 zurueck.
where /q pwsh
if %ERRORLEVEL%==0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
)

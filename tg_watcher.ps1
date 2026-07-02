<#
.SYNOPSIS
    Servicio 24/7 que escucha Telegram (Telethon) y dispara el pipeline.

.DESCRIPTION
    Corre tg_watcher.py con el python del venv y lo reinicia si se cae.
    Requisitos previos (una sola vez):
      1. Crear app en https://my.telegram.org -> API development tools -> api_id / api_hash.
      2. Completar .tg_config.json con esos valores (ver .tg_config.example.json).
      3. python tg_login.py   (login interactivo, pide el código de Telegram).
    Después, dejar esto corriendo:  .\tg_watcher.ps1
#>
$ErrorActionPreference = "Continue"
Set-Location -Path $PSScriptRoot

$python =
    if ($env:VIRTUAL_ENV -and (Test-Path (Join-Path $env:VIRTUAL_ENV "Scripts\python.exe"))) {
        Join-Path $env:VIRTUAL_ENV "Scripts\python.exe"
    } elseif (Test-Path (Join-Path $env:USERPROFILE "venv\Scripts\python.exe")) {
        Join-Path $env:USERPROFILE "venv\Scripts\python.exe"
    } else { "python" }

if (-not (Test-Path (Join-Path $PSScriptRoot ".tg_config.json"))) {
    Write-Error "Falta .tg_config.json (copiá .tg_config.example.json y completá api_id/api_hash)."
    exit 1
}

# Comando de Claude para la sesión headless (se resuelve solo si no está seteado)
if (-not $env:CLAUDE_CMD) {
    $cl = (Get-Command claude -ErrorAction SilentlyContinue).Source
    if (-not $cl) { $cl = Join-Path $env:USERPROFILE ".local\bin\claude.exe" }
    $env:CLAUDE_CMD = $cl
}
Write-Host "CLAUDE_CMD = $env:CLAUDE_CMD" -ForegroundColor DarkGray

Write-Host "Watcher de Telegram corriendo (Ctrl+C para frenar)..." -ForegroundColor Green
while ($true) {
    & $python (Join-Path $PSScriptRoot "tg_watcher.py")
    Write-Warning "El watcher terminó (código $LASTEXITCODE). Reinicio en 5s..."
    Start-Sleep -Seconds 5
}

<#
.SYNOPSIS
    Crea un proyecto de transcripción AISLADO para un audio y lo deja listo
    para asignar los nombres de los hablantes.

.DESCRIPTION
    Pensado para usarse con cada audio que adjuntes en Claude Code.
    Por cada archivo:
      1. Crea una carpeta propia en .\proyectos\<nombre>\  (sin pisar proyectos
         anteriores: si ya existe, agrega una marca de fecha-hora).
      2. Copia el audio dentro de esa carpeta.
      3. Ejecuta SOLO la transcripción (faster-whisper, GPU si hay) y se detiene.
      4. Deja <nombre>.txt / <nombre>.srt listos para la revisión.

    El resto del flujo lo orquesta Claude Code (ver CLAUDE.md):
      - Pasada 1 de correcciones sobre la transcripción (contexto de la reunión).
      - wav -> diarización -> fusión por hablante.
      - Identificación de hablantes (te pregunta los nombres con frases de cada uno).
      - Pasada 2 de correcciones sobre la conversación unificada por hablante.
      - Generación del PDF final.

    Acepta varios audios a la vez: cada uno genera su propio proyecto.

.PARAMETER Audio
    Una o más rutas a audios (m4a, mp3, wav, ...).

.PARAMETER Modelo
    Modelo whisper (tiny, base, small, medium, large-v3). Def: large-v3

.PARAMETER Device
    auto | cuda | cpu. Def: auto (usa GPU si está disponible).

.EXAMPLE
    .\procesar.ps1 "C:\Users\Fer\Downloads\reunion.m4a"
.EXAMPLE
    .\procesar.ps1 audio1.m4a audio2.mp3 -Modelo medium
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Audio,
    [string]$Modelo = "large-v3",
    [ValidateSet("auto", "cuda", "cpu")]
    [string]$Device = "auto"
)

$ErrorActionPreference = "Continue"
$binDir = $PSScriptRoot
$root = Split-Path -Parent $PSScriptRoot
$proyectosDir = Join-Path $root "proyectos"
$null = New-Item -ItemType Directory -Force -Path $proyectosDir | Out-Null

$proyectos = @()

foreach ($a in $Audio) {
    if (-not (Test-Path -LiteralPath $a)) {
        Write-Warning "No existe, se omite: $a"
        continue
    }
    $src  = (Resolve-Path -LiteralPath $a).Path
    $bname = [System.IO.Path]::GetFileNameWithoutExtension($src)
    $ext   = [System.IO.Path]::GetExtension($src)

    # carpeta de proyecto aislada, sin pisar otras
    $destDir = Join-Path $proyectosDir $bname
    if (Test-Path -LiteralPath $destDir) {
        $stamp   = Get-Date -Format "yyyyMMdd-HHmmss"
        $destDir = Join-Path $proyectosDir ("{0}_{1}" -f $bname, $stamp)
    }
    $null = New-Item -ItemType Directory -Force -Path $destDir | Out-Null

    # copiar el audio dentro del proyecto
    $destAudio = Join-Path $destDir ($bname + $ext)
    Copy-Item -LiteralPath $src -Destination $destAudio

    Write-Host "`n==================================================" -ForegroundColor Green
    Write-Host " Proyecto nuevo: $destDir" -ForegroundColor Green
    Write-Host "==================================================" -ForegroundColor Green

    # solo transcripción: el resto lo orquesta Claude (correcciones, diarización, nombres, PDF)
    & (Join-Path $binDir "ejecutar.ps1") -Audio $destAudio -Modelo $Modelo -Device $Device -Hasta transcribir

    $srt = Join-Path $destDir ($bname + ".srt")
    if (Test-Path -LiteralPath $srt) {
        $proyectos += [pscustomobject]@{ Carpeta = $destDir; Audio = $destAudio; Srt = $srt }
    } else {
        Write-Warning "El pipeline no generó $srt"
    }
}

Write-Host "`n===== TRANSCRIPCIÓN LISTA (falta el resto del flujo) =====" -ForegroundColor Cyan
if ($proyectos.Count -eq 0) {
    Write-Warning "Ningún proyecto quedó listo."
    exit 1
}
foreach ($p in $proyectos) {
    Write-Host "  Carpeta: $($p.Carpeta)" -ForegroundColor White
    Write-Host "  Audio  : $($p.Audio)" -ForegroundColor White
}
Write-Host "`nSiguiente: Claude hace la pasada 1 de correcciones y continúa el flujo (ver CLAUDE.md)." -ForegroundColor DarkGray

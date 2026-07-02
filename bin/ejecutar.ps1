<#
.SYNOPSIS
    Pipeline de transcripción + diarización de un audio.

.DESCRIPTION
    Ejecuta los pasos en orden, usando GPU (CUDA) si está disponible:
      1. Transcribir el audio        -> <nombre>.txt / <nombre>.srt   (faster-whisper)
      2. Convertir a WAV mono 16k    -> <nombre>.wav                  (ffmpeg)
      3. Diarizar (quién habla)      -> <nombre>_turnos.json          (pyannote)
      4. Fusionar transcripción+turnos -> <nombre>_hablantes.txt/.srt
      5. Generar PDF                 -> <nombre>_hablantes.pdf        (solo si -Hasta pdf)

    Cada paso se valida por su ARCHIVO DE SALIDA, no por el código de salida del
    proceso (en Windows las librerías CUDA a veces devuelven código != 0 al cerrar
    la GPU aunque el trabajo se completó bien).

    Reusa los archivos intermedios que ya existan; pasa -Force para regenerarlos.

.PARAMETER Audio
    Ruta al audio de entrada (m4a, mp3, wav, ...). Por defecto: Voz.m4a

.PARAMETER Titulo
    Título que aparece en la portada del PDF.

.PARAMETER Modelo
    Modelo de whisper (tiny, base, small, medium, large-v3). Def: large-v3

.PARAMETER Device
    auto | cuda | cpu. Por defecto auto (usa GPU si hay).

.PARAMETER Hasta
    fusionar | pdf. 'fusionar' se detiene antes del PDF para asignar nombres de
    hablante; 'pdf' (def) genera el PDF con las etiquetas "Hablante N".

.PARAMETER Force
    Regenera todos los pasos aunque ya existan los archivos de salida.

.EXAMPLE
    .\ejecutar.ps1
.EXAMPLE
    .\ejecutar.ps1 -Audio reunion.m4a -Titulo "Reunión de equipo" -Modelo medium
#>
[CmdletBinding()]
param(
    [string]$Audio  = "Voz.m4a",
    [string]$Titulo = "Transcripción de la reunión",
    [string]$Modelo = "large-v3",
    [ValidateSet("auto", "cuda", "cpu")]
    [string]$Device = "auto",
    [ValidateSet("transcribir", "fusionar", "pdf")]
    [string]$Hasta  = "pdf",
    [switch]$Force
)

# Continue (no Stop): en PS 5.1 los warnings que Python/ffmpeg mandan a stderr
# se convertirían en error terminante con Stop. Validamos por archivo de salida.
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$src  = Join-Path $root "src"
Set-Location -Path $root

# Resolver el python del venv (evita el alias de Microsoft Store que rompe imports)
$python =
    if ($env:VIRTUAL_ENV -and (Test-Path (Join-Path $env:VIRTUAL_ENV "Scripts\python.exe"))) {
        Join-Path $env:VIRTUAL_ENV "Scripts\python.exe"
    } elseif (Test-Path (Join-Path $env:USERPROFILE "venv\Scripts\python.exe")) {
        Join-Path $env:USERPROFILE "venv\Scripts\python.exe"
    } else { "python" }

if (-not (Test-Path -LiteralPath $Audio)) {
    Write-Error "No existe el audio: $Audio"
    exit 1
}

$audioFull = (Resolve-Path -LiteralPath $Audio).Path
$base = [System.IO.Path]::GetFileNameWithoutExtension($audioFull)
$dir  = Split-Path -Parent $audioFull
$srt    = Join-Path $dir "$base.srt"
$wav    = Join-Path $dir "$base.wav"
$turnos = Join-Path $dir "${base}_turnos.json"
$habTxt = Join-Path $dir "${base}_hablantes.txt"
$pdf    = Join-Path $dir "Conversacion_desktop.pdf"

function Existe-NoVacio($ruta) {
    return (Test-Path -LiteralPath $ruta) -and ((Get-Item -LiteralPath $ruta).Length -gt 0)
}

# Ejecuta un paso y lo valida por su archivo de salida.
function Paso {
    param(
        [string]$Etiqueta,
        [string]$Salida,
        [scriptblock]$Accion,
        [switch]$SiempreRegenera
    )
    if (-not $Force -and -not $SiempreRegenera -and (Existe-NoVacio $Salida)) {
        Write-Host "$Etiqueta — ya existe, se omite." -ForegroundColor DarkGray
        return
    }
    Write-Host "`n$Etiqueta ..." -ForegroundColor Cyan
    & $Accion
    $code = $LASTEXITCODE
    if (-not (Existe-NoVacio $Salida)) {
        Write-Error "${Etiqueta}: no se generó la salida esperada ($Salida). (código=$code)"
        exit 1
    }
    if ($code -ne 0) {
        Write-Warning "${Etiqueta}: el proceso devolvió código $code pero la salida existe; continúo."
    }
}

# --- 1. Transcribir ---
Paso "[1/5] Transcribiendo $base" $srt {
    & $python (Join-Path $src "transcribir.py") $audioFull --modelo $Modelo --device $Device
}

if ($Hasta -eq "transcribir") {
    Write-Host "`nTranscripción lista para la revisión de correcciones (pasada 1, Claude)." -ForegroundColor Green
    Write-Host "  SRT: $srt" -ForegroundColor Green
    Write-Host "  TXT: $(Join-Path $dir "$base.txt")" -ForegroundColor Green
    return
}

# --- 2. Convertir a WAV mono 16k ---
Paso "[2/5] Convirtiendo a WAV mono 16k (ffmpeg)" $wav {
    & ffmpeg -y -i $audioFull -ac 1 -ar 16000 -c:a pcm_s16le $wav
}

# --- 3. Diarizar ---
Paso "[3/5] Diarizando (quién habla)" $turnos {
    & $python (Join-Path $src "diarizar.py") $wav --device $Device
}

# --- 4. Fusionar ---
Paso "[4/5] Fusionando transcripción + turnos" $habTxt {
    & $python (Join-Path $src "fusionar.py") $srt $turnos
}

if ($Hasta -eq "fusionar") {
    Write-Host "`nListo hasta la fusión. Asigná los nombres de hablante y luego generá el PDF." -ForegroundColor Green
    Write-Host "  Hablantes: $habTxt" -ForegroundColor Green
    return
}

# --- 5. PDF de conversación: 2 versiones (desktop + celu) ---
Paso "[5/5] Generando Conversacion (desktop + celu)" $pdf -SiempreRegenera {
    & $python (Join-Path $src "gen_pdf.py") $habTxt $Titulo --formato desktop --out $pdf
    & $python (Join-Path $src "gen_pdf.py") $habTxt $Titulo --formato celu --out (Join-Path $dir "Conversacion_celu.pdf")
}

Write-Host "`nListo. Resultados generados en: $dir" -ForegroundColor Green
Write-Host "  PDF: $pdf" -ForegroundColor Green

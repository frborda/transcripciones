<#
.SYNOPSIS
    Aplica los nombres reales de los hablantes y genera el PDF final.

.DESCRIPTION
    Toma el <base>_hablantes.txt de un proyecto, reemplaza "Hablante N" por los
    nombres reales (renombrar.py) y genera el PDF (gen_pdf.py).

.PARAMETER HablantesTxt
    Ruta al archivo <base>_hablantes.txt del proyecto.

.PARAMETER Nombres
    Pares "Hablante N=Nombre real" (uno por hablante). Cada par entre comillas.

.PARAMETER Titulo
    Título del PDF. Si se omite, usa el nombre base del proyecto.

.EXAMPLE
    .\finalizar.ps1 ".\proyectos\reunion\reunion_hablantes.txt" `
        "Hablante 1=Juan Pérez" "Hablante 2=María López" -Titulo "Reunión de equipo"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$HablantesTxt,
    [Parameter(Mandatory = $true, Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Nombres,
    [string]$Titulo
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$src  = Join-Path $root "src"
$python =
    if ($env:VIRTUAL_ENV -and (Test-Path (Join-Path $env:VIRTUAL_ENV "Scripts\python.exe"))) {
        Join-Path $env:VIRTUAL_ENV "Scripts\python.exe"
    } elseif (Test-Path (Join-Path $env:USERPROFILE "venv\Scripts\python.exe")) {
        Join-Path $env:USERPROFILE "venv\Scripts\python.exe"
    } else { "python" }

if (-not (Test-Path -LiteralPath $HablantesTxt)) {
    Write-Error "No existe: $HablantesTxt"
    exit 1
}
$txtFull = (Resolve-Path -LiteralPath $HablantesTxt).Path
$base = [System.IO.Path]::GetFileNameWithoutExtension($txtFull) -replace "_hablantes$", ""
if (-not $Titulo) { $Titulo = $base }
$dirOut = Split-Path -Parent $txtFull
$pdf = Join-Path $dirOut "Conversacion_desktop.pdf"

Write-Host "Aplicando nombres ..." -ForegroundColor Cyan
& $python (Join-Path $src "renombrar.py") $txtFull @Nombres
if (-not $?) { Write-Error "Falló renombrar.py"; exit 1 }

Write-Host "`nGenerando Conversacion (desktop + celu) ..." -ForegroundColor Cyan
& $python (Join-Path $src "gen_pdf.py") $txtFull $Titulo --formato desktop --out $pdf
& $python (Join-Path $src "gen_pdf.py") $txtFull $Titulo --formato celu --out (Join-Path $dirOut "Conversacion_celu.pdf")

if ((Test-Path -LiteralPath $pdf) -and ((Get-Item -LiteralPath $pdf).Length -gt 0)) {
    Write-Host "`nConversacion (desktop+celu) generado en: $dirOut" -ForegroundColor Green
    Write-Host "Nota: la Minuta (desktop+celu) la genera Claude (análisis)." -ForegroundColor DarkGray
} else {
    Write-Error "No se generó el PDF esperado: $pdf"
    exit 1
}

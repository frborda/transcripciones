# Transcripción de reuniones → 4 PDFs

Convierte el audio de una reunión en **4 entregables** (cada uno en 2 formatos, PC y
celular → **8 PDFs**):

1. **Conversación** — transcripción corregida y separada por hablante.
2. **Minuta** — resumen, temas, decisiones, tareas por persona y pasos a seguir.
3. **Diagrama** — diagrama de flujo del tema tratado.
4. **Preguntas** — temas que quedaron poco claros, para aclarar después.

El pipeline mecánico (transcribir, diarizar, unir, maquetar PDFs) lo hacen scripts de
Python; el análisis (correcciones por contexto e identificación de hablantes y la
redacción de los documentos) lo hace **Claude Code**, que además orquesta todo el flujo.

Se puede usar de dos formas: como **servicio de Telegram 24/7** (mandás el audio a un
chat y te llegan los PDFs) o **manualmente** desde la consola.

---

## Estructura del proyecto

```
transcripciones/
├── src/              # pipeline en Python (transcribir, diarizar, fusionar, PDFs, Telegram)
├── bin/              # PowerShell: puntos de entrada (procesar, ejecutar, finalizar, tg_watcher)
├── clients/          # apps de grabación
│   ├── grabador/     #   escritorio (Go, Windows)
│   └── apk/          #   Android
├── .github/workflows # CI que publica los binarios como Releases
├── requirements.txt
├── README.md
├── .tg_config.json   # credenciales de Telegram (no se versiona; ver .example)
├── .hf_token         # token de Hugging Face (no se versiona; ver .example)
├── incoming/         # trabajo temporal del watcher (no se versiona)
└── proyectos/        # salida por reunión: transcripciones y PDFs (no se versiona)
```

## 1. Requisitos

- **Windows 10/11** con **PowerShell 5.1** (los `.ps1` van en UTF-8 con BOM).
- **Python 3.13** en un **entorno virtual (venv)**.
- **GPU NVIDIA con CUDA 12.8** (recomendado; sin GPU corre en CPU, más lento).
- **ffmpeg** en el `PATH` (p. ej. `C:\ffmpeg\bin`).
- **Claude Code CLI** (`claude`) instalado y autenticado — es quien corrige y redacta.
- Cuenta de **Hugging Face** con un token de acceso (para los modelos de pyannote).
- Solo para el modo Telegram: una app de **api.telegram** (my.telegram.org).

## 2. Instalación

```powershell
# 1) Clonar el repo y entrar
git clone <URL-del-repo> trascripciones
cd trascripciones

# 2) Crear y activar el venv (ejemplo en %USERPROFILE%\venv)
python -m venv $env:USERPROFILE\venv
& $env:USERPROFILE\venv\Scripts\Activate.ps1

# 3) Instalar PyTorch con CUDA 12.8 (APARTE, antes que el resto)
pip install torch==2.8.0 torchvision==0.23.0 torchaudio==2.8.0 `
    --index-url https://download.pytorch.org/whl/cu128

# 4) Instalar el resto de dependencias
pip install -r requirements.txt
```

> Los scripts `.ps1` resuelven el venv solos (buscan `%USERPROFILE%\venv` o
> `$env:VIRTUAL_ENV`). En comandos sueltos conviene usar la ruta completa del python
> del venv, p. ej. `C:\Users\<vos>\venv\Scripts\python.exe`, para no caer en el Python
> de la Microsoft Store.

### Token de Hugging Face (pyannote)

1. Creá un token en <https://huggingface.co/settings/tokens>.
2. Aceptá las condiciones de uso de los modelos
   `pyannote/speaker-diarization-community-1` y `pyannote/segmentation-3.0` en su
   página de Hugging Face (si no, la diarización falla).
3. Copiá `.hf_token.example` a `.hf_token` y pegá tu token dentro (una sola línea):

```powershell
copy .hf_token.example .hf_token
notepad .hf_token
```

## 3. Configurar el modo Telegram (servicio 24/7)

1. Entrá a <https://my.telegram.org> → **API development tools** → obtené tu
   `api_id` y `api_hash`.
2. Copiá `.tg_config.example.json` a `.tg_config.json` y completá los datos:

```powershell
copy .tg_config.example.json .tg_config.json
notepad .tg_config.json
```

```json
{
  "api_id": 12345678,
  "api_hash": "tu_api_hash",
  "chat": ["me", -1001234567890],
  "phone": "+549..."
}
```

   - `chat`: dónde escucha el watcher. `"me"` = tus **Mensajes Guardados**. Podés
     agregar un grupo o el chat de un bot (p. ej. `["me", "@TuBot"]`).

3. Login interactivo de Telegram (una sola vez; pide el código que te llega):

```powershell
python src\tg_login.py
```

4. Dejá el servicio corriendo:

```powershell
.\bin\tg_watcher.ps1
```

## 4. Uso

### a) Por Telegram (recomendado)

Con el watcher corriendo, en el chat configurado:

- **Audio suelto** → se procesa entero y te llegan los 8 PDFs.
- **Modo incremental** (ir mandando la reunión por partes mientras se graba):
  - `inicio` → abre la sesión.
  - mandás audios (cada uno es una parte, se transcribe en el momento).
  - `fin` → une todo, diariza y te entrega los 8 PDFs.
- **Renombrar hablantes** (después de recibir los PDFs):
  `renombrar 1=Juan, 2=María, 3=...` → regenera y reenvía los PDFs con los nombres.

### b) Manual / local (sin Telegram)

```powershell
# 1) Crear el proyecto y transcribir (se detiene para la corrección)
.\bin\procesar.ps1 "C:\ruta\al\audio.m4a"

#    -> Claude Code corrige el .srt/.txt por contexto (pasada 1)

# 2) WAV + diarización + fusión sobre el .srt corregido
.\bin\ejecutar.ps1 -Audio "proyectos\<nombre>\<nombre>.m4a" -Hasta fusionar

#    -> identificar hablantes (src\renombrar.py) y pasada 2 de corrección (Claude)

# 3) Generar los PDFs (ejemplo: conversación en ambos formatos)
$py = "$env:USERPROFILE\venv\Scripts\python.exe"
& $py src\gen_pdf.py "proyectos\<n>\<n>_hablantes.txt" "Título" --formato desktop --out "proyectos\<n>\Conversacion_desktop.pdf"
& $py src\gen_pdf.py "proyectos\<n>\<n>_hablantes.txt" "Título" --formato celu    --out "proyectos\<n>\Conversacion_celu.pdf"
# análogo con src\gen_minuta.py (Minuta.md / Preguntas.md) y src\gen_diagrama.py (diagrama.json)
```

## 5. Salida

Todo queda aislado en `proyectos\<nombre>\`, con los 8 PDFs de nombres fijos:
`Conversacion_{desktop,celu}.pdf`, `Minuta_{desktop,celu}.pdf`,
`Diagrama_{desktop,celu}.pdf`, `Preguntas_{desktop,celu}.pdf`.

## 6. Scripts (referencia)

Los scripts de Python están en `src/`; los de PowerShell (puntos de entrada), en `bin/`.

| Script | Qué hace |
|--------|----------|
| `tg_watcher.ps1` / `tg_watcher.py` | Servicio 24/7: escucha Telegram, descarga el audio y orquesta el flujo. |
| `tg_login.py` | Login interactivo de Telethon (una vez). |
| `tg.py` | I/O de Telegram para la sesión headless (`send-message` / `send-document` / `wait-reply` / `drain`). |
| `procesar.ps1` | Crea el proyecto aislado y transcribe (se detiene para la corrección). |
| `ejecutar.ps1` | Orquesta el pipeline (`-Hasta transcribir\|fusionar\|pdf`); valida por archivo de salida. |
| `transcribir.py` | Transcribe con faster-whisper (large-v3), con tiempos por palabra y anti-alucinación. |
| `diarizar.py` | Diariza con pyannote (`speaker-diarization-community-1`, fallback `3.1`). |
| `diarizar_service.py` | Servicio pre-warm: carga el modelo una vez y atiende pedidos (lo lanza el watcher). |
| `unir_chunks.py` | Une las partes de una sesión incremental (offsets exactos por muestras). |
| `fusionar.py` | Asigna hablante por palabra sobre el `.srt` corregido. |
| `frases.py` | Muestra las frases más identificativas de cada hablante. |
| `renombrar.py` | Aplica los nombres reales a los hablantes. |
| `gen_pdf.py` | PDF de la conversación por hablante. |
| `gen_minuta.py` | Markdown → PDF formal (Minuta y Preguntas). |
| `gen_diagrama.py` | Diagrama de flujo (reportlab, sin graphviz). |
| `finalizar.ps1` | Atajo: renombrar + PDF de conversación. |

## 7. Notas

- **Validación por archivo de salida:** en Windows, las librerías CUDA a veces
  devuelven un código de salida ≠ 0 al liberar la GPU aunque el trabajo terminó bien;
  por eso cada paso se valida por la existencia de su archivo de salida, no por el
  exit code.
- **No se re-ejecutan** pasos pesados si su salida ya existe (salvo `-Force`).
- Modelo por defecto `large-v3`. Para pruebas rápidas: `--modelo tiny`.
- El watcher lanza Claude con **Opus 4.8** y effort **xhigh** por defecto; se puede
  cambiar con las variables de entorno `CLAUDE_MODEL` y `CLAUDE_EFFORT`.

## 8. Clientes de grabación (opcionales)

Graban la reunión y la mandan solos (`inicio`/partes/`fin`) por Telegram:

- `clients/grabador/` — grabador de escritorio para Windows (Go).
- `clients/apk/` — app de grabación para Android.

**Binarios ya compilados:** se publican automáticamente en la página de
[**Releases**](https://github.com/frborda/transcripciones/releases) —
`GrabadorReuniones.exe` (Windows) y `GrabadorReuniones.apk` (Android). Los genera
GitHub Actions (`.github/workflows/release.yml`) cada vez que se pushea un tag `v*`.
Cada carpeta tiene además su propio `README` con instrucciones para compilar a mano.

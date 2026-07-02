# Grabador Reuniones (.exe GUI) — equivalente de escritorio del APK

App de **ventana Windows** (no consola) que graba por partes y las sube a Telegram,
integrada con el modo incremental del watcher de la PC. Mismo flujo que la app
Android, pero para notebook/PC.

## Qué hace

- **Ventana nativa** con minimizado a la **bandeja del sistema** (junto al reloj):
  el botón "Minimizar a la bandeja" o la X esconden la ventana; el ícono queda
  abajo a la derecha y con **un clic** se restaura. Menú del ícono (clic derecho):
  Restaurar / Cortar y enviar / Finalizar / Salir.
- **Dos fuentes de audio** (elegibles):
  1. **Micrófono** — con desplegable para elegir cuál (lista los micrófonos del
     sistema).
  2. **Escritorio** — captura **todo lo que suena en Windows** (WASAPI loopback,
     sin instalar nada ni enrutar cables).
  - **Ambos** — micrófono + escritorio mezclados (ideal para una llamada: tu voz
     + la del otro lado).
- **▶ Iniciar** → manda `inicio` al chat y graba. **✂ Cortar y enviar** (manual o
  automático cada `hh:mm:ss`) cierra el segmento y lo sube **sin frenar la
  grabación**. El corte automático **espera la primera pausa (silencio) después
  del intervalo** para no cortar en medio de una frase (tope de 30 s si no hay
  pausa). **⏹ Finalizar** (pide confirmación) sube lo último y manda `fin`:
  la PC une todo, asigna hablantes sola y entrega los PDFs.
- Cola de subidas FIFO con reintentos (aguanta cortes de red; el `fin` siempre va
  después de la última parte) y **retome automático** si el proceso se cae a mitad
  de reunión (estado en `grabador.state.json`).

## Requisitos

- **ffmpeg** en el PATH (acá está en `C:\ffmpeg\bin`). El micrófono se captura por
  DirectShow; el escritorio por WASAPI loopback (incluido en el binario).
- Nada que instalar: el `.exe` es autocontenido.

## Uso

1. Ejecutá `GrabadorReuniones.exe`.
2. Completá en la ventana: **token** del bot (@BotFather) y **chat id** (viene por
   defecto el grupo `-5418589182`), elegí la **fuente** y el **micrófono**, el
   **intervalo** (`hh:mm:ss`) y si querés el auto-corte.
3. **Iniciar**. Minimizá a la bandeja y seguí tu reunión. Cortá manual cuando
   quieras (ventana o menú de la bandeja) o dejá el automático. Al terminar,
   **Finalizar**.

La config queda en `grabador.config.json` junto al exe; los segmentos en
`grabaciones\` (los ya subidos con prefijo `ok_`).

## Compilar (si cambia el código)

Necesita Go. Como el GOPROXY del sistema está vacío, pasalo en línea la 1ª vez para
bajar dependencias (walk, go-wca); luego compila offline:

```
cd grabador
GOPROXY=https://proxy.golang.org,direct go mod download
go build -ldflags "-H windowsgui -s -w" -o GrabadorReuniones.exe .
```

El `rsrc.syso` (manifest de Common Controls, necesario para la GUI) ya está
generado; si se borra: `go run github.com/akavel/rsrc@latest -manifest app.manifest -o rsrc.syso`.

## Notas

- Cada corte parte el audio "en seco"; la pasada de corrección de la PC cose los
  bordes para que se lea como una sola conversación.
- Tope de 45 min por parte (≈31 MB) aunque el auto esté apagado, por el límite de
  50 MB por archivo de la Bot API.
- Para que el watcher procese lo que manda este exe, su `chat` en `.tg_config.json`
  ya incluye el grupo `-5418589182`.

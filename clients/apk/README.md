# Grabador Reuniones (APK)

App Android que graba reuniones con la pantalla bloqueada y va subiendo el audio
**por partes** a Telegram, integrada con el modo incremental del watcher de la PC:

- **▶ INICIAR**: manda el texto `inicio` al chat (la PC abre la sesión incremental)
  y empieza a grabar. La grabación sigue con la pantalla apagada (servicio en
  primer plano tipo micrófono + wake lock).
- **✂ CORTAR Y ENVIAR**: cierra el segmento actual y lo sube; la grabación sigue
  sin frenarse (hueco de ~0,2 s entre segmentos). También se hace solo con el
  envío automático, configurable en formato **hh:mm:ss** (def. 00:01:00).
- **Tecla BAJAR VOLUMEN** (incluso con la pantalla bloqueada): corta y envía, con
  una vibración como confirmación. Subir volumen no hace nada mientras se graba
  (la app captura las teclas de volumen vía MediaSession durante la grabación).
- **⏹ FINALIZAR**: sube el último segmento y manda `fin`: la PC une las partes,
  diariza, pregunta los hablantes por Telegram y entrega los 4 PDFs en el chat.
- Las subidas van en cola con reintentos (aguanta cortes de conexión); el `fin`
  siempre sale después de la última parte.

## Compilar

Requisitos: JDK 17 y Android SDK (lo más fácil: tener Android Studio instalado).

```
cd apk
.\gradlew.bat assembleDebug
```

APK resultante: `app\build\outputs\apk\debug\app-debug.apk` → copiarlo al teléfono
e instalarlo (permitir "orígenes desconocidos"). El wrapper baja Gradle 8.7 solo;
si falta el SDK, definir `ANDROID_HOME` o crear `local.properties` con
`sdk.dir=C:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk`.

## Configuración (una vez)

1. **Crear el bot**: en Telegram hablar con `@BotFather` → `/newbot` → copiar el
   **token**.
2. **Abrir chat con el bot**: buscarlo por su @nombre y mandarle `hola` (sin esto
   el bot no puede escribirte).
3. En la app: pegar el token, tocar **Detectar** (completa el chat id solo),
   elegir intervalo y modo automático.
4. Tocar **"Permitir en segundo plano (batería)"** y aceptar (si no, Android puede
   matar la grabación a los minutos).
5. **En la PC**: editar `.tg_config.json` para que el watcher también escuche el
   chat del bot y reiniciarlo:

   ```json
   { "api_id": ..., "api_hash": "...", "chat": ["me", "@TuBotDeGrabacion"] }
   ```

   Con eso los `inicio`/partes/`fin` que manda la app disparan el pipeline, la
   pregunta de hablantes llega a ese mismo chat y los PDFs vuelven ahí.

## Notas

- Cada corte parte el audio "en seco": puede caer en medio de una palabra. La
  pasada de corrección de la PC lo tiene en cuenta, pero si podés, cortá en pausas.
- Límite de la Bot API: 50 MB por archivo → con 96 kbps mono entran ~70 min por
  parte; con envío automático cada pocos minutos no es problema.
- Los segmentos quedan también en el teléfono en
  `Android/data/com.fer.grabador/files/grabaciones/` (los subidos quedan con
  prefijo `ok_`), por si hay que recuperar algo.

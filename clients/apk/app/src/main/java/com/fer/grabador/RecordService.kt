package com.fer.grabador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * Servicio en primer plano (tipo micrófono): graba con la pantalla apagada/bloqueada.
 *
 * - INICIAR: manda "inicio" por el bot (abre la sesión incremental en la PC) y
 *   arranca a grabar el primer segmento.
 * - CORTAR (manual o automático cada N min): cierra el segmento actual, arranca
 *   el siguiente al instante (~0,2 s de hueco) y sube el cerrado por Telegram.
 * - FINALIZAR: cierra y sube el último segmento, manda "fin" (la PC une todo,
 *   pregunta hablantes y entrega los PDFs) y apaga el servicio cuando la cola
 *   de subidas queda vacía.
 *
 * Las subidas van en una cola FIFO con reintentos: el "fin" siempre sale
 * después de la última parte.
 */
class RecordService : Service() {

    companion object {
        const val ACTION_START = "com.fer.grabador.START"
        const val ACTION_CUT = "com.fer.grabador.CUT"
        const val ACTION_FINISH = "com.fer.grabador.FINISH"
        const val ACTION_SHOW = "com.fer.grabador.SHOW"    // notificación persistente (reposo)
        const val ACTION_EXIT = "com.fer.grabador.EXIT"    // salir: cierra app y notificación
        const val BC_SALIR = "com.fer.grabador.BC_SALIR"   // broadcast para cerrar la Activity
        const val CANAL = "grabacion"
        const val UMBRAL_SILENCIO = 1800   // amplitud (0..32767) por debajo = "silencio"
        const val MAX_ESPERA_MS = 30_000L  // si no hay pausa tras el intervalo, cortar igual
        const val TOPE_PARTE_MS = 45 * 60 * 1000  // tope duro por parte (evita el 413 de Telegram)

        @Volatile var corriendo = false
        @Volatile var estado = "detenido"

        // estado en vivo para la pantalla (la Activity lo lee cada segundo)
        @Volatile var sesionId = ""
        @Volatile var parteN = 0
        @Volatile var pendientesN = 0
        @Volatile var tParte = 0L
        @Volatile var tTotal = 0L
        @Volatile var subiendoAhora = ""
        @Volatile var grabandoArchivo = ""
    }

    private sealed class Trabajo {
        class Audio(val f: File) : Trabajo()
        class Texto(val t: String) : Trabajo()
        object Fin : Trabajo()
    }

    private var recorder: MediaRecorder? = null
    private var archivoActual: File? = null
    @Volatile private var parte = 0            // lo lee el uploader: volátil por visibilidad
    private var sesion = ""
    @Volatile private var finalizando = false  // idem: lo lee el uploader
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null
    private var ultimoCorteVol = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val cola = LinkedBlockingQueue<Trabajo>()
    private var uploader: Thread? = null
    @Volatile private var uploaderActivo = false

    // Al cumplirse el intervalo no cortamos de una: "armamos" y esperamos la primera
    // pausa (silencio) para no cortar en medio de una frase.
    private val corteAuto = Runnable {
        if (recorder != null && !finalizando) {
            esperaSilencio.bajos = 0
            esperaSilencio.desde = SystemClock.elapsedRealtime()
            try { recorder?.maxAmplitude } catch (e: Exception) {} // resetea el medidor
            handler.post(esperaSilencio)
        }
    }

    // Espera a que el nivel quede bajo ~0,5 s seguidos (silencio) y corta ahí; si no
    // aparece una pausa en MAX_ESPERA_MS, corta igual para no demorar la entrega.
    private val esperaSilencio = object : Runnable {
        var bajos = 0
        var desde = 0L
        override fun run() {
            val r = recorder
            if (r == null || finalizando) return
            val amp = try { r.maxAmplitude } catch (e: Exception) { 0 }
            // incluir amp==0 (silencio digital total): antes lo tomaba como "no silencio"
            // y reseteaba el contador, así una pausa muda nunca disparaba el corte.
            if (amp < UMBRAL_SILENCIO) bajos++ else bajos = 0
            val venció = SystemClock.elapsedRealtime() - desde > MAX_ESPERA_MS
            if (bajos >= 3 || venció) { // 3 ventanas de 150 ms ≈ pausa de ~0,45 s
                rotar()
                reprogramarAuto()
            } else {
                handler.postDelayed(this, 150)
            }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> iniciar()
            ACTION_CUT -> if (recorder != null && !finalizando) {
                rotar()
                reprogramarAuto()
            }
            ACTION_FINISH -> finalizar()
            ACTION_SHOW -> if (!corriendo) mostrarIdle()
            ACTION_EXIT -> salir()
            // intent null = el sistema reinició el servicio (START_STICKY) tras matarlo:
            // si había una grabación en curso, retomarla; si no, quedarse en reposo
            // (la notificación persistente solo se va con Salir)
            else -> if (!corriendo && Prefs.sesionActiva(this).isNotEmpty()) iniciar()
                    else if (!corriendo) mostrarIdle()
        }
        return START_STICKY
    }

    /** Notificación persistente en reposo (sin grabar): acciones Iniciar / Salir.
     *  Usa el tipo dataSync porque el tipo micrófono exige el permiso de grabar,
     *  que puede no estar dado todavía la primera vez. */
    private fun mostrarIdle() {
        crearCanal()
        if (estado == "detenido") estado = "listo para grabar"
        val n = notif(estado)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
    }

    /** Salir: cierra la Activity (broadcast), saca la notificación y apaga el servicio.
     *  Grabando o subiendo no se ofrece; si igual llega, no se sale (no perder nada). */
    private fun salir() {
        if (corriendo) {
            estado = if (finalizando) "subiendo lo pendiente: esperá para salir"
                     else "grabación en curso: finalizá antes de salir"
            notificar(estado)
            return
        }
        sendBroadcast(Intent(BC_SALIR).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- ciclo de vida de la grabación ----------

    private fun iniciar() {
        if (corriendo) return
        // desde la acción Iniciar de la notificación puede no haber permiso todavía
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            estado = "falta permiso de micrófono: abrí la app"
            mostrarIdle()
            return
        }
        corriendo = true
        finalizando = false
        crearCanal()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notif("Grabando..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif("Grabando..."))
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grabador:rec")
            .apply { acquire(12 * 3600 * 1000L) }
        arrancarUploader()
        armarBotonVolumen()

        // si quedó una grabación sin terminar (la app murió a mitad de reunión) y es
        // reciente, RETOMARLA: misma sesión, sigue la numeración y reenvía lo que no
        // llegó a subirse, en orden. Si no, sesión nueva.
        val previa = Prefs.sesionActiva(this)
        val reciente = previa.isNotEmpty() &&
                ultimaActividad(previa) > System.currentTimeMillis() - 6 * 3600_000L

        // la app murió DESPUÉS de tocar Finalizar: NO grabar más (el usuario ya salió
        // de la reunión), solo reenviar lo pendiente y mandar "fin".
        if (reciente && Prefs.estaFinalizando(this)) {
            sesion = previa
            parte = Prefs.parteActual(this)
            sesionId = sesion
            tTotal = System.currentTimeMillis()
            finalizando = true
            reencolarPendientes(sesion)
            cola.put(Trabajo.Texto("fin"))
            cola.put(Trabajo.Fin)
            pendientesN = pendientes()
            estado = "reanudando cierre: subiendo lo pendiente..."
            notificar(estado)
            return
        }

        if (reciente) {
            sesion = previa
            parte = Prefs.parteActual(this)
            reencolarPendientes(sesion)
            estado = "grabación retomada (parte ${parte + 1})"
        } else {
            archivarHuerfanas()
            sesion = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            parte = 0
            cola.put(Trabajo.Texto("inicio"))
            estado = "grabando (parte 1)"
        }
        sesionId = sesion
        tTotal = System.currentTimeMillis()
        empezarSegmento()
        programarAuto()
    }

    private fun ultimaActividad(ses: String): Long =
        dirGrab().listFiles { f -> f.name.contains(ses) }
            ?.maxOfOrNull { it.lastModified() } ?: 0L

    /** Reenvía (en orden) las partes de la sesión que quedaron sin subir. */
    private fun reencolarPendientes(ses: String) {
        dirGrab().listFiles { f -> f.name.startsWith("reunion_${ses}_p") && f.name.endsWith(".m4a") }
            ?.sortedBy { it.name }
            ?.forEach { cola.put(Trabajo.Audio(it)) }
    }

    /** Partes sin subir de sesiones viejas: se apartan (no van a una reunión nueva). */
    private fun archivarHuerfanas() {
        val huer = File(dirGrab(), "huerfanas").apply { mkdirs() }
        dirGrab().listFiles { f -> f.isFile && f.name.startsWith("reunion_") }
            ?.forEach { it.renameTo(File(huer, it.name)) }
    }

    /**
     * MediaSession con volumen "remoto": mientras se graba, las teclas de volumen
     * llegan a onAdjustVolume aunque la pantalla esté bloqueada. Bajar volumen =
     * cortar y enviar (con vibración como confirmación). Subir volumen se ignora.
     */
    private fun armarBotonVolumen() {
        val ms = MediaSessionCompat(this, "grabador")
        ms.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                .build())
        ms.setPlaybackToRemote(object :
            VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                if (direction >= 0) return
                val ahora = SystemClock.elapsedRealtime()
                if (ahora - ultimoCorteVol < 3000) return   // anti-rebote (auto-repeat)
                ultimoCorteVol = ahora
                handler.post {
                    if (recorder != null && !finalizando) {
                        rotar()
                        reprogramarAuto()
                    }
                }
            }
        })
        ms.isActive = true
        mediaSession = ms
    }

    private fun vibrar(ms: Long = 150) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    private fun dirGrab() = File(getExternalFilesDir(null), "grabaciones").apply { mkdirs() }

    private fun empezarSegmento() {
        parte++
        val f = File(dirGrab(), "reunion_${sesion}_p" + "%03d".format(parte) + ".m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96000)
            r.setOutputFile(f.absolutePath)
            // tope duro por parte: si el intervalo automático está apagado, evita que
            // un segmento crezca sin límite y Telegram lo rechace con 413.
            r.setMaxDuration(TOPE_PARTE_MS)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    handler.post {
                        if (recorder != null && !finalizando) { rotar(); reprogramarAuto() }
                    }
                }
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            // mic ocupado por otra app / permiso revocado: NO crashear (evita el bucle
            // de reinicio de START_STICKY). Mostrar el error y reintentar en unos segundos.
            try { r.release() } catch (_: Exception) {}
            recorder = null
            parte--   // el intento fallido no consume número de parte
            estado = "ERROR micrófono: ${e.message ?: "no disponible"} (reintentando)"
            notificar(estado)
            if (!finalizando) handler.postDelayed({
                if (corriendo && recorder == null && !finalizando) empezarSegmento()
            }, 4000)
            return
        }
        recorder = r
        archivoActual = f
        parteN = parte
        tParte = System.currentTimeMillis()
        grabandoArchivo = f.name
        Prefs.marcarSesion(this, sesion, parte)
    }

    private fun pararSegmento(): File? {
        val r = recorder ?: return null
        val f = archivoActual
        recorder = null
        archivoActual = null
        grabandoArchivo = ""
        try { r.stop() } catch (e: Exception) { } finally { r.release() }
        if (f == null) return null
        // stop() puede fallar tanto por un segmento vacío/corrupto como porque el tope
        // de duración ya cerró el archivo. Distinguir por tamaño: si tiene contenido
        // real, conservarlo; si no, BORRARLO para que reencolarPendientes no lo resuba.
        if (f.length() > 2000) return f
        f.delete()
        return null
    }

    private fun rotar() {
        val f = pararSegmento()
        if (!finalizando) empezarSegmento()
        if (f != null && f.length() > 0) cola.put(Trabajo.Audio(f))
        pendientesN = pendientes()
        vibrar()
        estado = "grabando (parte $parte)"
        notificar("Grabando parte $parte — $pendientesN subida(s) pendiente(s)")
    }

    private fun finalizar() {
        if (!corriendo || finalizando) return
        finalizando = true
        Prefs.marcarFinalizando(this, true)  // sobrevive a que el SO mate el proceso a mitad
        handler.removeCallbacks(corteAuto)
        handler.removeCallbacks(esperaSilencio)
        val f = pararSegmento()
        if (f != null && f.length() > 0) cola.put(Trabajo.Audio(f))
        cola.put(Trabajo.Texto("fin"))
        cola.put(Trabajo.Fin)
        pendientesN = pendientes()
        estado = "subiendo lo pendiente ($pendientesN restante(s))..."
        notificar(estado)
    }

    private fun pendientes() = cola.count { it is Trabajo.Audio }

    // ---------- cola de subidas (FIFO, con reintentos) ----------

    // un 4xx (salvo 429 rate-limit) no se arregla reintentando: token/chat malos o
    // archivo demasiado grande. Reintentar eterno solo bloquea la cola y el "fin".
    private fun esPermanente(code: Int) = code in 400..499 && code != 429

    private fun arrancarUploader() {
        if (uploaderActivo) return   // no arrancar dos uploaders sobre la misma cola
        uploaderActivo = true
        uploader = Thread {
            val token = Prefs.token(this)
            val chat = Prefs.chatId(this)
            while (uploaderActivo) {
                val t = try { cola.take() } catch (e: InterruptedException) { break }
                when (t) {
                    is Trabajo.Audio -> {
                        subiendoAhora = t.f.name
                        var intento = 0
                        var permanente = false
                        while (true) {
                            val code = TelegramApi.sendDocument(token, chat, t.f)
                            if (code == 200) break
                            if (esPermanente(code)) { permanente = true; break }
                            intento++
                            estado = "sin conexión, reintento ${intento} (${t.f.name})"
                            Thread.sleep(if (intento > 12) 60_000L else 5_000L * intento)
                        }
                        if (permanente) {
                            // apartar el archivo y seguir la cola (no bloquear el "fin")
                            t.f.renameTo(File(t.f.parentFile, "fallo_" + t.f.name))
                            estado = "ERROR al subir ${t.f.name}: revisá token, chat id o tamaño"
                            notificar(estado)
                        } else {
                            t.f.renameTo(File(t.f.parentFile, "ok_" + t.f.name))
                        }
                        subiendoAhora = ""
                        pendientesN = pendientes()
                        if (!permanente) {
                            if (!finalizando) {
                                estado = "grabando (parte $parte)"
                            } else {
                                val resta = pendientes()
                                estado = if (resta > 0) "subiendo lo pendiente ($resta restante(s))..."
                                         else "última parte enviada, avisando a la PC..."
                                notificar(estado)
                            }
                        }
                    }
                    is Trabajo.Texto -> {
                        var intento = 0
                        while (true) {
                            val code = TelegramApi.sendMessage(token, chat, t.t)
                            if (code == 200 || esPermanente(code)) break
                            intento++
                            Thread.sleep(if (intento > 12) 60_000L else 5_000L * intento)
                        }
                    }
                    is Trabajo.Fin -> {
                        handler.post { terminarServicio() }
                        return@Thread
                    }
                }
            }
        }.apply { isDaemon = true }
        uploader!!.start()
    }

    private fun terminarServicio() {
        estado = "listo: todo enviado, la PC está procesando"
        corriendo = false
        uploaderActivo = false
        Prefs.limpiarSesion(this)
        mediaSession?.release()
        mediaSession = null
        vibrar(400)
        try { wakeLock?.release() } catch (e: Exception) { }
        // la notificación persiste (con Iniciar/Salir): solo Salir la quita
        mostrarIdle()
    }

    // ---------- timer automático y notificación ----------

    private fun programarAuto() {
        if (Prefs.auto(this)) {
            handler.postDelayed(corteAuto, Prefs.intervaloSeg(this) * 1000L)
        }
    }

    private fun reprogramarAuto() {
        handler.removeCallbacks(corteAuto)
        handler.removeCallbacks(esperaSilencio)
        programarAuto()
    }

    private fun crearCanal() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Grabación", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notif(texto: String): Notification {
        val piApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CANAL)
            .setContentTitle("Grabador de reuniones")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(piApp)
        if (corriendo) {
            // grabando: Finalizar (abre la app con confirmación) + Cortar
            val piFin = PendingIntent.getActivity(
                this, 2,
                Intent(this, MainActivity::class.java)
                    .putExtra("confirmar_fin", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val piCortar = PendingIntent.getService(
                this, 1, Intent(this, RecordService::class.java).setAction(ACTION_CUT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, "⏹ Finalizar", piFin)
            b.addAction(0, "✂ Cortar", piCortar)
        } else {
            // reposo (sin iniciar o ya finalizada): Iniciar + Salir
            val piIniciar = PendingIntent.getService(
                this, 3, Intent(this, RecordService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // salir pide confirmación: abre la app con el diálogo (igual que Finalizar)
            val piSalir = PendingIntent.getActivity(
                this, 4,
                Intent(this, MainActivity::class.java)
                    .putExtra("confirmar_salir", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, "▶ Iniciar", piIniciar)
            b.addAction(0, "✖ Salir", piSalir)
        }
        return b.build()
    }

    private fun notificar(t: String) {
        getSystemService(NotificationManager::class.java).notify(1, notif(t))
    }

    override fun onDestroy() {
        handler.removeCallbacks(corteAuto)
        handler.removeCallbacks(esperaSilencio)
        uploaderActivo = false        // frenar el hilo de subidas (evita hilos huérfanos)
        uploader?.interrupt()
        pararSegmento()
        mediaSession?.release()
        mediaSession = null
        try { wakeLock?.release() } catch (e: Exception) { }
        corriendo = false
        super.onDestroy()
    }
}

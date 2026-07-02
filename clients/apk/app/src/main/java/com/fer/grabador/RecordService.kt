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
        const val CANAL = "grabacion"
        const val UMBRAL_SILENCIO = 1800   // amplitud (0..32767) por debajo = "silencio"
        const val MAX_ESPERA_MS = 30_000L  // si no hay pausa tras el intervalo, cortar igual

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
    private var parte = 0
    private var sesion = ""
    private var finalizando = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null
    private var ultimoCorteVol = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val cola = LinkedBlockingQueue<Trabajo>()

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
            if (amp in 1 until UMBRAL_SILENCIO) bajos++ else bajos = 0
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
            // intent null = el sistema reinició el servicio (START_STICKY) tras matarlo:
            // si había una grabación en curso, retomarla; si no, apagarse
            else -> if (!corriendo && Prefs.sesionActiva(this).isNotEmpty()) iniciar() else if (!corriendo) stopSelf()
        }
        return START_STICKY
    }

    // ---------- ciclo de vida de la grabación ----------

    private fun iniciar() {
        if (corriendo) return
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
        val retomar = previa.isNotEmpty() &&
                ultimaActividad(previa) > System.currentTimeMillis() - 6 * 3600_000L
        if (retomar) {
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
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(44100)
        r.setAudioEncodingBitRate(96000)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
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
        return try {
            r.stop()
            f
        } catch (e: Exception) {
            null   // segmento demasiado corto o sin datos: se descarta
        } finally {
            r.release()
        }
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

    private fun arrancarUploader() {
        Thread {
            val token = Prefs.token(this)
            val chat = Prefs.chatId(this)
            while (true) {
                when (val t = cola.take()) {
                    is Trabajo.Audio -> {
                        subiendoAhora = t.f.name
                        var intento = 0
                        while (!TelegramApi.sendDocument(token, chat, t.f)) {
                            intento++
                            estado = "sin conexión, reintento ${intento} (${t.f.name})"
                            Thread.sleep(if (intento > 12) 60_000L else 5_000L * intento)
                        }
                        t.f.renameTo(File(t.f.parentFile, "ok_" + t.f.name))
                        subiendoAhora = ""
                        pendientesN = pendientes()
                        if (!finalizando) {
                            estado = "grabando (parte $parte)"
                        } else {
                            val resta = pendientes()
                            estado = if (resta > 0) "subiendo lo pendiente ($resta restante(s))..."
                                     else "última parte enviada, avisando a la PC..."
                            notificar(estado)
                        }
                    }
                    is Trabajo.Texto -> {
                        var intento = 0
                        while (!TelegramApi.sendMessage(token, chat, t.t)) {
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
        }.apply { isDaemon = true }.start()
    }

    private fun terminarServicio() {
        estado = "listo: todo enviado, la PC está procesando"
        corriendo = false
        Prefs.limpiarSesion(this)
        mediaSession?.release()
        mediaSession = null
        vibrar(400)
        try { wakeLock?.release() } catch (e: Exception) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        // cortar directo desde la notificación (funciona con pantalla bloqueada)
        val piCortar = PendingIntent.getService(
            this, 1, Intent(this, RecordService::class.java).setAction(ACTION_CUT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // finalizar abre la app con el diálogo de confirmación (evita finales por error)
        val piFin = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .putExtra("confirmar_fin", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CANAL)
            .setContentTitle("Grabador de reuniones")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(piApp)
            .addAction(0, "✂ Cortar y enviar", piCortar)
            .addAction(0, "⏹ Finalizar", piFin)
            .build()
    }

    private fun notificar(t: String) {
        getSystemService(NotificationManager::class.java).notify(1, notif(t))
    }

    override fun onDestroy() {
        handler.removeCallbacks(corteAuto)
        handler.removeCallbacks(esperaSilencio)
        pararSegmento()
        mediaSession?.release()
        mediaSession = null
        try { wakeLock?.release() } catch (e: Exception) { }
        corriendo = false
        super.onDestroy()
    }
}

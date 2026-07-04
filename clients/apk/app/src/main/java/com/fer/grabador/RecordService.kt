package com.fer.grabador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
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
 * Servicio en primer plano: graba con la pantalla apagada/bloqueada.
 *
 * La captura la hace CapturaAudio (AudioRecord + encoder AAC propio): las partes
 * rotan SIN perder audio (solo cambia el archivo de salida) y el corte automático
 * lo decide un VAD neuronal (Silero) que detecta el FIN de una frase hablada, no
 * una simple caída de energía. Una parte sin habla no se sube (se aparta).
 *
 * - INICIAR: manda "inicio" por el bot y arranca la captura.
 * - CORTAR (manual, por volumen o automático en la primera pausa tras el
 *   intervalo): cierra la parte y sigue grabando en la siguiente, sin hueco.
 * - FINALIZAR: cierra y sube lo pendiente, manda "fin" y vuelve al reposo.
 *
 * Las subidas van en una cola FIFO con reintentos: el "fin" siempre sale
 * después de la última parte.
 */
class RecordService : Service(), CapturaAudio.Listener {

    companion object {
        const val ACTION_START = "com.fer.grabador.START"
        const val ACTION_CUT = "com.fer.grabador.CUT"
        const val ACTION_FINISH = "com.fer.grabador.FINISH"
        const val ACTION_SHOW = "com.fer.grabador.SHOW"    // notificación persistente (reposo)
        const val ACTION_EXIT = "com.fer.grabador.EXIT"    // salir: cierra app y notificación
        const val ACTION_TEST = "com.fer.grabador.TEST"    // prueba de mic (no graba ni sube)
        const val BC_SALIR = "com.fer.grabador.BC_SALIR"   // broadcast para cerrar la Activity
        const val CANAL = "grabacion"

        const val PAUSA_CORTE_MS = 700     // sin habla seguida (VAD+energía) para cortar
        const val MAX_ESPERA_MS = 30_000L  // si no hay pausa tras el intervalo, cortar igual
        const val TOPE_PARTE_MS = 45 * 60 * 1000L  // tope duro por parte (evita el 413 de Telegram)
        const val HABLA_MIN_MS = 1500L     // una parte con menos habla que esto NO se sube

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
        @Volatile var nivelN = 0           // nivel SNR 0..100 (señal sobre el ruido)
        @Volatile var hablaN = false       // el VAD detecta habla ahora
        @Volatile var vadN = false         // true = VAD neuronal activo (no energía)
        @Volatile var probando = false     // modo prueba de micrófono (no graba nada)
    }

    private sealed class Trabajo {
        class Audio(val f: File) : Trabajo()
        class Texto(val t: String) : Trabajo()
        object Fin : Trabajo()
    }

    private var captura: CapturaAudio? = null
    @Volatile private var parte = 0            // lo leen uploader y el hilo de captura
    private var sesion = ""
    @Volatile private var finalizando = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null
    private var ultimoCorteVol = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val cola = LinkedBlockingQueue<Trabajo>()
    private var uploader: Thread? = null
    @Volatile private var uploaderActivo = false

    // --- máquina de corte (la alimenta onFrame, en el hilo de captura) ---
    @Volatile private var armado = false       // el intervalo venció: cortar en la próxima pausa
    @Volatile private var armadoDesde = 0L
    private var bajosMs = 0                    // ms seguidos sin habla (solo hilo de captura)
    private var tapadoMs = 0
    private var satMs = 0
    @Volatile private var avisoDado = false    // un aviso (tapado/saturado) por parte
    @Volatile private var cortePedido = false  // evita duplicar el post de un corte

    // al vencer el intervalo no se corta de una: se ARMA y el VAD elige la pausa
    private val corteAuto = Runnable {
        if (corriendo && !finalizando) {
            bajosMs = 0
            armadoDesde = SystemClock.elapsedRealtime()
            armado = true
            estado = "grabando parte $parte (corta en la próxima pausa)"
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> iniciar()
            ACTION_CUT -> if (corriendo && !finalizando) {
                rotar()
                reprogramarAuto()
            }
            ACTION_FINISH -> finalizar()
            ACTION_SHOW -> if (!corriendo && !probando) mostrarIdle()
            ACTION_EXIT -> salir()
            ACTION_TEST -> if (probando) detenerTest(null)
                           else if (!corriendo) empezarTest()
            // intent null = el sistema reinició el servicio (START_STICKY) tras matarlo:
            // si había una grabación en curso, retomarla; si no, quedarse en reposo
            else -> if (!corriendo && Prefs.sesionActiva(this).isNotEmpty()) iniciar()
                    else if (!corriendo) mostrarIdle()
        }
        return START_STICKY
    }

    /** Notificación persistente en reposo (sin grabar): acciones Iniciar / Salir. */
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

    /** Salir: cierra la Activity (broadcast), saca la notificación y apaga el servicio. */
    private fun salir() {
        if (probando) {  // cerrar la prueba primero, después salir
            detenerTest { salir() }
            return
        }
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

    // ---------- modo prueba: barra y detección en vivo, sin grabar ni subir ----------

    private val testTimeout = Runnable { if (probando) detenerTest(null) }

    private fun empezarTest() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            estado = "falta permiso de micrófono: tocá Iniciar una vez para pedirlo"
            mostrarIdle()
            return
        }
        probando = true
        crearCanal()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notif("Prueba de micrófono"),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif("Prueba de micrófono"))
        }
        val vad = try { VadSilero(this) } catch (e: Exception) { null }
        estado = if (vad != null) "🎙 prueba: hablá y mirá la barra (VAD activo)"
                 else "🎙 prueba: hablá y mirá la barra (VAD no cargó: energía)"
        captura = CapturaAudio(fuenteElegida(), vad, this)
            .also { it.iniciar(File(cacheDir, "test.m4a")) }
        handler.postDelayed(testTimeout, 120_000)  // apagado solo a los 2 min
    }

    private fun detenerTest(continuar: (() -> Unit)?) {
        probando = false
        handler.removeCallbacks(testTimeout)
        Thread {
            try { captura?.detener() } catch (_: Exception) {}
            captura = null
            File(cacheDir, "test.m4a").delete()
            nivelN = 0
            hablaN = false
            handler.post {
                if (continuar != null) continuar()
                else { estado = "listo para grabar"; mostrarIdle() }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Fuente de audio según preferencias y soporte del equipo. */
    private fun fuenteElegida(): Int {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val soportaCruda =
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (Prefs.cruda(this) && soportaCruda) MediaRecorder.AudioSource.UNPROCESSED
               else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    // ---------- ciclo de vida de la grabación ----------

    private fun iniciar() {
        if (corriendo) return
        if (probando) {  // cerrar la prueba primero y arrancar de verdad después
            detenerTest { iniciar() }
            return
        }
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

        val previa = Prefs.sesionActiva(this)
        val reciente = previa.isNotEmpty() &&
                ultimaActividad(previa) > System.currentTimeMillis() - 6 * 3600_000L

        // la app murió DESPUÉS de tocar Finalizar: NO grabar más, solo cerrar
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
        empezarCaptura()
        programarAuto()
    }

    /** Crea el motor de captura (fuente + VAD) y arranca la primera parte. */
    private fun empezarCaptura() {
        parte++
        val f = File(dirGrab(), "reunion_${sesion}_p" + "%03d".format(parte) + ".m4a")
        // VAD neuronal; si no carga, CapturaAudio cae al detector de energía
        val vad = try { VadSilero(this) } catch (e: Exception) { null }
        if (vad == null) estado = "grabando (corte por energía: VAD no disponible)"

        armado = false
        cortePedido = false
        avisoDado = false
        bajosMs = 0; tapadoMs = 0; satMs = 0
        captura = CapturaAudio(fuenteElegida(), vad, this).also { it.iniciar(f) }
        parteN = parte
        tParte = System.currentTimeMillis()
        grabandoArchivo = f.name
        Prefs.marcarSesion(this, sesion, parte)
    }

    /** Cierra la parte actual y sigue en la siguiente, sin hueco de audio. */
    private fun rotar() {
        val cap = captura ?: return
        parte++
        val f = File(dirGrab(), "reunion_${sesion}_p" + "%03d".format(parte) + ".m4a")
        armado = false
        cortePedido = false
        avisoDado = false
        cap.rotar(f)
        parteN = parte
        tParte = System.currentTimeMillis()
        grabandoArchivo = f.name
        Prefs.marcarSesion(this, sesion, parte)
        vibrar()
        estado = "grabando (parte $parte)"
        notificar("Grabando parte $parte — ${pendientes()} subida(s) pendiente(s)")
    }

    private fun finalizar() {
        if (!corriendo || finalizando) return
        finalizando = true
        Prefs.marcarFinalizando(this, true)  // sobrevive a que el SO mate el proceso
        handler.removeCallbacks(corteAuto)
        armado = false
        estado = "cerrando la grabación..."
        notificar(estado)
        // detener() espera al hilo de captura: fuera del main thread
        Thread {
            try { captura?.detener() } catch (_: Exception) {}
            captura = null
            grabandoArchivo = ""
            cola.put(Trabajo.Texto("fin"))
            cola.put(Trabajo.Fin)
            pendientesN = pendientes()
            estado = "subiendo lo pendiente (${pendientes()} restante(s))..."
            notificar(estado)
        }.apply { isDaemon = true }.start()
    }

    // ---------- callbacks del motor de captura (hilo de captura) ----------

    override fun onFrame(nivel: Int, esVoz: Boolean, silencioso: Boolean, saturado: Boolean) {
        nivelN = nivel
        hablaN = esVoz
        vadN = captura?.usandoVad ?: false
        if (!corriendo || finalizando) return

        // tope duro por parte
        if (!cortePedido && System.currentTimeMillis() - tParte >= TOPE_PARTE_MS) {
            cortePedido = true
            handler.post { if (corriendo && !finalizando) { rotar(); reprogramarAuto() } }
            return
        }
        // corte armado: SOLO cuando VAD y energía COINCIDEN en que es una pausa
        // (si cualquiera de los dos cree que hay voz, no se corta), con tope de espera
        if (armado && !cortePedido) {
            if (esVoz || !silencioso) bajosMs = 0 else bajosMs += 32
            val vencio = SystemClock.elapsedRealtime() - armadoDesde > MAX_ESPERA_MS
            if (bajosMs >= PAUSA_CORTE_MS || vencio) {
                cortePedido = true
                handler.post { if (corriendo && !finalizando) { rotar(); reprogramarAuto() } }
            }
        }
        // diagnóstico con el PCM real: mic tapado / saturación (un aviso por parte)
        if (nivel <= 1) tapadoMs += 32 else tapadoMs = 0
        if (saturado) satMs += 32 else if (satMs > 0) satMs -= 8
        if (!avisoDado) {
            if (tapadoMs >= 10_000) {
                avisoDado = true
                estado = "⚠ no entra audio: ¿micrófono tapado?"
                handler.post { notificar(estado) }
            } else if (satMs >= 2_000) {
                avisoDado = true
                estado = "⚠ el audio satura: alejá el teléfono de la fuente"
                handler.post { notificar(estado) }
            }
        }
    }

    override fun onParteCerrada(parte: CapturaAudio.ParteCerrada) {
        if (probando) {  // modo prueba: nada se conserva ni se sube
            parte.file.delete()
            return
        }
        // una parte casi sin habla no se sube: se aparta (menos datos y menos GPU).
        // DOBLE condición: sin habla según el VAD *y* nivel medio realmente bajo —
        // si el detector quedara ciego (señal baja, modelo caído), la parte se sube
        // igual: perder audio real es mucho peor que subir un silencio.
        if (parte.hablaMs < HABLA_MIN_MS && parte.durMs > 10_000 && parte.nivelMedio < 12) {
            val destino = File(parte.file.parentFile, "silencio_" + parte.file.name)
            parte.file.renameTo(destino)
            pendientesN = pendientes()
            return
        }
        if (parte.file.length() > 2000) {
            cola.put(Trabajo.Audio(parte.file))
        } else {
            parte.file.delete()  // vacía/corrupta: que no la reencole una retoma
        }
        pendientesN = pendientes()
    }

    override fun onError(msg: String) {
        if (probando) {
            estado = "ERROR en la prueba: $msg"
            handler.post { detenerTest(null) }
            return
        }
        estado = "ERROR captura: $msg (reintentando)"
        handler.post {
            notificar(estado)
            captura = null
            parte--   // el intento fallido no consume número de parte
            if (corriendo && !finalizando) {
                handler.postDelayed({
                    if (corriendo && captura == null && !finalizando) empezarCaptura()
                }, 4000)
            }
        }
    }

    // ---------- helpers de sesión / archivos ----------

    private fun ultimaActividad(ses: String): Long =
        dirGrab().listFiles { f -> f.name.contains(ses) }
            ?.maxOfOrNull { it.lastModified() } ?: 0L

    /** Reenvía (en orden) las partes de la sesión que quedaron sin subir. */
    private fun reencolarPendientes(ses: String) {
        dirGrab().listFiles { f -> f.name.startsWith("reunion_${ses}_p") && f.name.endsWith(".m4a") }
            ?.sortedBy { it.name }
            ?.forEach { cola.put(Trabajo.Audio(it)) }
    }

    /** Partes de sesiones viejas: se apartan (no van a una reunión nueva). */
    private fun archivarHuerfanas() {
        val huer = File(dirGrab(), "huerfanas").apply { mkdirs() }
        dirGrab().listFiles { f ->
            f.isFile && (f.name.startsWith("reunion_") || f.name.startsWith("silencio_"))
        }?.forEach { it.renameTo(File(huer, it.name)) }
    }

    private fun dirGrab() = File(getExternalFilesDir(null), "grabaciones").apply { mkdirs() }

    /**
     * MediaSession con volumen "remoto": mientras se graba, BAJAR VOLUMEN con la
     * pantalla bloqueada = cortar y enviar (vibra como confirmación).
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
                    if (corriendo && !finalizando) {
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
        nivelN = 0
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
            // reposo (sin iniciar o ya finalizada): Iniciar + Salir (con confirmación)
            val piIniciar = PendingIntent.getService(
                this, 3, Intent(this, RecordService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
        handler.removeCallbacks(testTimeout)
        probando = false
        uploaderActivo = false        // frenar el hilo de subidas (evita hilos huérfanos)
        uploader?.interrupt()
        captura?.abortar()
        captura = null
        mediaSession?.release()
        mediaSession = null
        try { wakeLock?.release() } catch (e: Exception) { }
        corriendo = false
        super.onDestroy()
    }
}

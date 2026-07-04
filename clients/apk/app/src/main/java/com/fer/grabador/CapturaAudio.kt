package com.fer.grabador

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.os.Build
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Motor de captura: AudioRecord (PCM real) + encoder AAC propio + VAD por frame.
 *
 * Ventajas sobre MediaRecorder:
 *  - El encoder vive toda la sesión y las PARTES son solo un cambio de MediaMuxer:
 *    rotar no pierde audio (antes había ~200 ms de hueco por reinicio).
 *  - PCM accesible: nivel RMS fiel, detección de saturación/mic tapado, y VAD
 *    neuronal (Silero) que distingue HABLA de "hay energía".
 *  - Fuente configurable (VOICE_RECOGNITION / UNPROCESSED).
 *
 * Todo corre en un hilo propio; los callbacks del listener llegan desde ese hilo.
 */
class CapturaAudio(
    private val fuente: Int,
    private val vad: VadSilero?,          // null → detector de energía adaptativo
    private val supresionRuido: Boolean,  // NoiseSuppressor del DSP del teléfono
    private val ecoSala: Boolean,         // sala con eco: captación direccional
    private val listener: Listener,
) {
    interface Listener {
        /** Cada 32 ms. nivel: SNR 0..100 (cuánto sobresale la señal del ruido de
         *  fondo). esVoz: el VAD detecta habla. silencioso: la energía cruda está
         *  al nivel del piso de ruido (para cortar se exigen AMBAS señales). */
        fun onFrame(nivel: Int, esVoz: Boolean, silencioso: Boolean, saturado: Boolean)
        /** Una parte quedó cerrada en disco (por rotación o al detener). */
        fun onParteCerrada(parte: ParteCerrada)
        fun onError(msg: String)
    }

    data class ParteCerrada(val file: File, val durMs: Long, val hablaMs: Long,
                            val nivelMedio: Int)

    companion object {
        const val SR = 48000
        const val FRAME = 1536          // 32 ms a 48 kHz (→ 512 a 16 kHz para el VAD)
        const val BITRATE = 128000
        const val CAL_VENTANA = 250     // ~8 s de HABLA para el índice de claridad

        // biquad pasa-altos RBJ fs=48k f0=80Hz Q=0.707, normalizado por a0
        private val HPF_B0: Double
        private val HPF_B1: Double
        private val HPF_B2: Double
        private val HPF_A1: Double
        private val HPF_A2: Double
        init {
            val w0 = 2.0 * Math.PI * 80.0 / SR
            val cosw = Math.cos(w0)
            val alpha = Math.sin(w0) / (2.0 * 0.7071)
            val a0 = 1.0 + alpha
            HPF_B0 = (1.0 + cosw) / 2.0 / a0
            HPF_B1 = -(1.0 + cosw) / a0
            HPF_B2 = HPF_B0
            HPF_A1 = -2.0 * cosw / a0
            HPF_A2 = (1.0 - alpha) / a0
        }
    }

    @Volatile private var corriendo = false
    @Volatile private var pedidoParar = false
    private val pedidoRotar = AtomicReference<File?>(null)
    private var hilo: Thread? = null

    // piso de ruido (RMS crudo): aprende SOLO de los frames tranquilos
    private var pisoRuido = 150.0

    // envolvente del medidor: ataque instantáneo, caída suave (~500 ms), como un vúmetro
    private var envNivel = 0.0

    // AUTO-GANANCIA digital GATEADA POR VOZ: los perfiles sin AGC entregan muy poco
    // nivel; se amplifica hacia ~-6 dBFS pero la ganancia se calcula SOLO con los
    // picos de habla (nunca sube durante silencio: amplificar el ruido de fondo
    // rompía el medidor y la detección).
    private var ganancia = 1.0
    // arranca BAJO: el primer pico de voz real lo eleva al instante (max), así la
    // ganancia converge en ~1 s en vez de tardar 10 s decayendo desde un valor alto
    private var picoVoz = 600.0

    // el VAD del frame anterior también habilita el aprendizaje de ganancia:
    // la voz LEJANA no supera el gate de energía pero el VAD sí la ve
    private var ultimoEsVoz = false

    /** true si el VAD neuronal está activo (false = detector de energía). */
    @Volatile var usandoVad = false
        private set

    /** true si la supresión de ruido del DSP quedó activa en esta sesión. */
    @Volatile var nsActivo = false
        private set

    // telemetría para el modo prueba: nivel crudo del mic y ganancia aplicada
    @Volatile var dbCrudo = -90
        private set
    @Volatile var gananciaActual = 1.0
        private set
    /** Probabilidad de voz de Silero del último frame (calibración en vivo). */
    @Volatile var probVoz = 0f
        private set

    // suavizado de la probabilidad: ataque instantáneo, caída ~200 ms. La voz
    // LEJANA/reverberante hace fluctuar a Silero entre sílabas; sin puentear esos
    // huecos hay que hablar fuerte para sostener la detección.
    private var probSuave = 0f

    // PASA-ALTOS 80 Hz (biquad): saca el retumbe estructural (mesa, pisos viejos,
    // aire acondicionado) que ensucia el espectro sin aportar voz. Coeficientes
    // RBJ para fs=48k, f0=80, Q=0.707, normalizados por a0.
    private var hx1 = 0.0; private var hx2 = 0.0
    private var hy1 = 0.0; private var hy2 = 0.0

    // LIMITADOR con lookahead de frame: conocemos el pico del frame ANTES de
    // emitirlo, así que la atenuación se aplica en rampa y las sílabas fuertes
    // no salen recortadas (el clipping es lo único irreparable río abajo).
    private var aten = 1.0

    // ---- ECUALIZADOR de 3 bandas (biquads RBJ en cascada) ----
    // Corre sobre TODO el camino (métricas, VAD, grabación): así el índice de
    // claridad refleja la curva activa y el modo auto puede compararlas.
    private class Biquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun procesar(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }
    private val eqBajo = Biquad()
    private val eqMedio = Biquad()
    private val eqAlto = Biquad()
    private var eqG = Float.NaN   // NaN fuerza la primera configuración
    private var eqM = Float.NaN
    private var eqP = Float.NaN
    private var eqActivo = false

    private fun setShelfBajo(q: Biquad, f0: Double, dB: Double) {
        val A = Math.pow(10.0, dB / 40.0)
        val w0 = 2.0 * Math.PI * f0 / SR
        val cs = Math.cos(w0)
        val alpha = Math.sin(w0) / 2.0 * Math.sqrt(2.0)  // S = 1
        val sq = 2.0 * Math.sqrt(A) * alpha
        val a0 = (A + 1) + (A - 1) * cs + sq
        q.b0 = A * ((A + 1) - (A - 1) * cs + sq) / a0
        q.b1 = 2 * A * ((A - 1) - (A + 1) * cs) / a0
        q.b2 = A * ((A + 1) - (A - 1) * cs - sq) / a0
        q.a1 = -2 * ((A - 1) + (A + 1) * cs) / a0
        q.a2 = ((A + 1) + (A - 1) * cs - sq) / a0
    }

    private fun setCampana(q: Biquad, f0: Double, qFactor: Double, dB: Double) {
        val A = Math.pow(10.0, dB / 40.0)
        val w0 = 2.0 * Math.PI * f0 / SR
        val cs = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * qFactor)
        val a0 = 1 + alpha / A
        q.b0 = (1 + alpha * A) / a0
        q.b1 = -2 * cs / a0
        q.b2 = (1 - alpha * A) / a0
        q.a1 = -2 * cs / a0
        q.a2 = (1 - alpha / A) / a0
    }

    private fun configurarEq(g: Float, m: Float, p: Float) {
        eqG = g; eqM = m; eqP = p
        eqActivo = abs(g) >= 0.25f || abs(m) >= 0.25f || abs(p) >= 0.25f
        if (!eqActivo) return
        setShelfBajo(eqBajo, 120.0, g.toDouble())
        setCampana(eqMedio, 400.0, 1.0, m.toDouble())
        // presencia = CAMPANA ancha en 3 kHz (no shelf): un shelf a +9 dB sube
        // también el siseo de 8-24 kHz, que la decimación 3:1 del VAD repliega
        // (aliasing) dentro de la banda de Silero y le ensucia la detección
        setCampana(eqAlto, 3000.0, 0.7, p.toDouble())
    }

    /** CLARIDAD de la voz captada, 0..100 (-1 hasta juntar ~2 s de habla).
     *  Combina el SNR (cuánto sobresale la voz del ruido de sala) con la
     *  confianza de Silero (cae con eco, distancia y voz entredicha) menos un
     *  castigo por saturación: un proxy de "qué tan transcribible" llega la voz.
     *  Se calcula SOLO sobre frames con habla (el silencio no opina). */
    @Volatile var calidadVoz = -1
        private set
    private val calSnr = FloatArray(CAL_VENTANA)
    private val calProb = FloatArray(CAL_VENTANA)
    private val calSat = BooleanArray(CAL_VENTANA)
    private var calIdx = 0
    private var calLlenos = 0

    /** Vacía la ventana de claridad (el modo auto-EQ mide cada curva desde cero).
     *  Se pide con un flag y lo aplica el hilo de captura al borde del frame. */
    fun reiniciarCalidad() { pedidoResetCal = true }
    @Volatile private var pedidoResetCal = false

    private fun calcularCalidad(): Int {
        var sSnr = 0f; var sProb = 0f; var nSat = 0
        for (i in 0 until calLlenos) {
            sSnr += calSnr[i]; sProb += calProb[i]; if (calSat[i]) nSat++
        }
        val snrScore = ((sSnr / calLlenos - 5f) * (100f / 20f)).coerceIn(0f, 100f)   // 5 dB→0, 25 dB→100
        val probScore = ((sProb / calLlenos - 0.4f) * (100f / 0.55f)).coerceIn(0f, 100f) // 0.40→0, 0.95→100
        val castigo = 30f * nSat / calLlenos
        val q = if (usandoVad) 0.45f * snrScore + 0.55f * probScore - castigo
                else snrScore - castigo  // sin VAD no hay confianza que medir
        return q.coerceIn(0f, 100f).toInt()
    }

    fun iniciar(primerArchivo: File) {
        if (corriendo) return
        corriendo = true
        pedidoParar = false
        vad?.reset()
        hilo = Thread({ loop(primerArchivo) }, "captura-audio").apply {
            isDaemon = true
            start()
        }
    }

    /** Pide cerrar la parte actual y seguir grabando en 'siguiente' (sin hueco).
     *  La parte cerrada llega por onParteCerrada. */
    fun rotar(siguiente: File) {
        pedidoRotar.set(siguiente)
    }

    /** Cierra la parte actual y detiene todo. Espera al hilo (la última parte
     *  también sale por onParteCerrada antes de retornar). */
    fun detener() {
        pedidoParar = true
        hilo?.join(5000)
        hilo = null
        corriendo = false
    }

    /** Corte abrupto (onDestroy): no espera; el hilo cierra lo mejor que pueda. */
    fun abortar() {
        pedidoParar = true
        corriendo = false
    }

    // ---------------- hilo de captura ----------------

    @SuppressLint("MissingPermission")  // el servicio valida RECORD_AUDIO antes
    private fun loop(primerArchivo: File) {
        var audio: AudioRecord? = null
        var ns: android.media.audiofx.NoiseSuppressor? = null
        var aec: android.media.audiofx.AcousticEchoCanceler? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var pista = -1
        var formatoSalida: MediaFormat? = null
        var archivoActual = primerArchivo
        var ptsBase = -1L                 // rebase de pts por archivo (cada .m4a arranca en ~0)
        var ultimoPtsEscrito = -1L
        var framesParte = 0L              // duración de la parte en frames de 32 ms
        var hablaMsParte = 0L
        var sumaNivelParte = 0L
        var muestrasTotales = 0L

        fun cerrarParte() {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            val medio = if (framesParte > 0) (sumaNivelParte / framesParte).toInt() else 0
            listener.onParteCerrada(ParteCerrada(archivoActual, framesParte * 32, hablaMsParte, medio))
            framesParte = 0
            hablaMsParte = 0
            sumaNivelParte = 0
        }

        fun abrirParte(f: File) {
            muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            archivoActual = f
            ptsBase = -1L
            ultimoPtsEscrito = -1L
            formatoSalida?.let { fmt ->
                pista = muxer!!.addTrack(fmt)
                muxer!!.start()
            }
        }

        fun drenar(codecArg: MediaCodec, hastaEos: Boolean) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val idx = codecArg.dequeueOutputBuffer(info, if (hastaEos) 10_000L else 0L)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        formatoSalida = codecArg.outputFormat
                        // primer archivo: la pista recién se puede crear ahora
                        if (muxer != null && pista < 0) {
                            pista = muxer!!.addTrack(formatoSalida!!)
                            muxer!!.start()
                        }
                    }
                    idx >= 0 -> {
                        val buf = codecArg.getOutputBuffer(idx)!!
                        val esConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!esConfig && info.size > 0 && muxer != null && pista >= 0) {
                            if (ptsBase < 0) ptsBase = info.presentationTimeUs
                            var pts = info.presentationTimeUs - ptsBase
                            if (pts <= ultimoPtsEscrito) pts = ultimoPtsEscrito + 1
                            ultimoPtsEscrito = pts
                            val infoLocal = MediaCodec.BufferInfo().apply {
                                set(info.offset, info.size, pts, info.flags)
                            }
                            muxer!!.writeSampleData(pista, buf, infoLocal)
                        }
                        codecArg.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> if (!hastaEos) return  // TRY_AGAIN sin EOS pendiente: listo
                }
            }
        }

        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audio = AudioRecord(fuente, SR, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SR))  // buffer ~1 s
            if (audio.state != AudioRecord.STATE_INITIALIZED)
                throw IllegalStateException("AudioRecord no inicializó (¿mic ocupado?)")

            // captación DIRECCIONAL solo si se pidió (sala con eco). Con eco
            // apagado NO se toca el enrutado de mics: pedir campo "amplio"
            // (-1.0) también activa el beamforming del fabricante y en el S22U
            // baja la sensibilidad a la voz lejana/fuera de eje.
            if (ecoSala && Build.VERSION.SDK_INT >= 29) {
                try {
                    audio.setPreferredMicrophoneDirection(
                        android.media.MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED)
                    audio.setPreferredMicrophoneFieldDimension(0.8f)
                } catch (_: Exception) {}
            }

            // SUPRESIÓN DE RUIDO por hardware (DSP del teléfono, tipo Krisp nativo):
            // limpia el ruido ambiente ANTES del encoder y del VAD. Si el equipo no
            // la trae, sigue sin ella (nsActivo lo refleja).
            if (supresionRuido) {
                try {
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        ns = android.media.audiofx.NoiseSuppressor.create(audio.audioSessionId)
                            ?.apply { enabled = Ajustes.nsDeseado }
                        nsActivo = ns?.enabled == true
                    }
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                        aec = android.media.audiofx.AcousticEchoCanceler.create(audio.audioSessionId)
                            ?.apply { enabled = true }
                    }
                } catch (_: Exception) { /* sin efectos: se graba igual */ }
            }

            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audio.startRecording()
            abrirParte(primerArchivo)

            val pcm = ShortArray(FRAME)
            val chunkVad = FloatArray(VadSilero.CHUNK)
            var vadRoto = vad == null
            usandoVad = !vadRoto
            var framesDesdeNs = 0
            var framesDesdeCal = 0

            while (!pedidoParar) {
                // el slider en su mínimo también APAGA el NS del DSP en vivo (el NS
                // del fabricante aplasta la voz lejana como si fuera ruido); se
                // chequea ~1 vez por segundo para no tocar el efecto por frame
                if (ns != null && ++framesDesdeNs >= 31) {
                    framesDesdeNs = 0
                    val quiere = supresionRuido && Ajustes.nsDeseado
                    try {
                        if (ns.enabled != quiere) {
                            ns.enabled = quiere
                            nsActivo = ns.enabled
                        }
                    } catch (_: Exception) {}
                }
                // leer un frame completo (32 ms)
                var leidas = 0
                while (leidas < FRAME && !pedidoParar) {
                    val n = audio.read(pcm, leidas, FRAME - leidas)
                    if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                    leidas += n
                }
                if (pedidoParar) break

                // PASA-ALTOS 80 Hz (antes de todo: el retumbe no debe contaminar ni
                // el piso de ruido ni la ganancia ni lo grabado)
                for (i in pcm.indices) {
                    val x = pcm[i].toDouble()
                    val y = HPF_B0 * x + HPF_B1 * hx1 + HPF_B2 * hx2 - HPF_A1 * hy1 - HPF_A2 * hy2
                    hx2 = hx1; hx1 = x
                    hy2 = hy1; hy1 = y
                    pcm[i] = y.toInt().coerceIn(-32768, 32767).toShort()
                }

                // ECUALIZADOR (manual o auto): recalcular coeficientes solo si la
                // curva cambió; plano (0/0/0) no corre nada
                if (Ajustes.eqGraves != eqG || Ajustes.eqMedios != eqM ||
                    Ajustes.eqPresencia != eqP)
                    configurarEq(Ajustes.eqGraves, Ajustes.eqMedios, Ajustes.eqPresencia)
                if (eqActivo) {
                    for (i in pcm.indices) {
                        var v = pcm[i].toDouble()
                        v = eqBajo.procesar(v)
                        v = eqMedio.procesar(v)
                        v = eqAlto.procesar(v)
                        pcm[i] = v.toInt().coerceIn(-32768, 32767).toShort()
                    }
                }

                // reset de la ventana de claridad pedido por el auto-EQ
                if (pedidoResetCal) {
                    pedidoResetCal = false
                    calIdx = 0; calLlenos = 0
                    calidadVoz = -1
                }

                // métricas de la señal CRUDA: piso de ruido, silencio y candidato a voz
                var suma0 = 0.0
                var pico0 = 0
                for (s in pcm) {
                    val v = s.toInt()
                    suma0 += (v * v).toDouble()
                    val a = if (v < 0) -v else v
                    if (a > pico0) pico0 = a
                }
                val rms0 = sqrt(suma0 / FRAME)
                // piso de ruido: aprende SOLO cuando NO hay voz (según el VAD del
                // frame anterior). Aprender de los valles entre sílabas hacía subir
                // el piso durante habla continua: la barra caía, cada vez más frames
                // parecían "silencio" y terminaba cortando a mitad de frase.
                // Baja rápido (recuperarse de un pico de ruido), sube MUY lento.
                if (!ultimoEsVoz && rms0 < pisoRuido * 2.5) {
                    pisoRuido = if (rms0 < pisoRuido)
                        0.90 * pisoRuido + 0.10 * max(rms0, 20.0)
                    else
                        0.995 * pisoRuido + 0.005 * max(rms0, 20.0)
                }
                val silencioso = rms0 < pisoRuido * 2.0
                val vozPorEnergia = rms0 > pisoRuido * 3.0

                // ganancia: solo aprende cuando HAY voz (gate); nunca sube con ruido.
                // Tope x30: en el S22U los perfiles sin AGC entregan voz tan baja que
                // con x12 el archivo quedaba inaudible.
                if (vozPorEnergia || ultimoEsVoz) {
                    picoVoz = max(pico0.toDouble(), picoVoz * 0.995)
                    val deseada = (16384.0 / max(picoVoz, 600.0)).coerceIn(1.0, 30.0)
                    ganancia += (deseada - ganancia) * 0.15  // converge en ~1 s de voz
                }
                dbCrudo = (20.0 * log10(max(rms0, 1.0) / 32768.0)).toInt()
                gananciaActual = ganancia
                if (ganancia > 1.01) {
                    for (i in pcm.indices) {
                        val v = (pcm[i] * ganancia).toInt()
                        pcm[i] = v.coerceIn(-32768, 32767).toShort()
                    }
                }
                var pico = 0
                for (s in pcm) {
                    val a = if (s < 0) (-s).toInt() else s.toInt()
                    if (a > pico) pico = a
                }
                if (pico >= 32700) ganancia = max(1.0, ganancia * 0.85)  // recorta: bajar YA
                val saturado = pico >= 32200

                // LIMITADOR (lookahead de frame): el pico ya es conocido ANTES de
                // emitir el frame; atenuar en rampa hacia 30000 evita el recorte
                // duro en sílabas fuertes. Release suave (+4 %/frame) al normalizar.
                val objetivo = if (pico > 30000) 30000.0 / pico else 1.0
                val destino = if (objetivo < aten) objetivo else minOf(1.0, aten * 1.04)
                if (destino < 0.999 || aten < 0.999) {
                    val paso = (destino - aten) / FRAME
                    var fLim = aten
                    for (i in pcm.indices) {
                        fLim += paso
                        pcm[i] = (pcm[i] * fLim).toInt().coerceIn(-32768, 32767).toShort()
                    }
                }
                aten = destino

                // medidor por SNR: cuánto sobresale la señal del piso de ruido.
                // Silencio ≈ 0-15 %, voz normal ≈ 50-90 % (30 dB de SNR = 100 %).
                val snrDb = 20.0 * log10(max(rms0, 1.0) / max(pisoRuido, 1.0))
                val instante = (snrDb * 100.0 / 30.0).coerceIn(0.0, 100.0)
                envNivel = max(instante, envNivel * 0.93)  // caída ~500 ms
                val nivel = envNivel.toInt()

                // VAD: decimar 48k→16k (promedio de a 3) y preguntar por HABLA
                var esVoz: Boolean
                if (!vadRoto) {
                    var j = 0
                    var i = 0
                    var maxAbs = 0f
                    while (j < VadSilero.CHUNK) {
                        val v = (pcm[i] + pcm[i + 1] + pcm[i + 2]) / (3f * 32768f)
                        chunkVad[j] = v
                        val a = if (v < 0) -v else v
                        if (a > maxAbs) maxAbs = a
                        i += 3; j++
                    }
    // normalizar la ENTRADA del VAD (independiente de la grabación):
                    // la voz LEJANA/baja llega a Silero a nivel sano y la detecta.
                    // El tope y el umbral los gradúa el slider de supresión EN VIVO.
                    if (maxAbs > 1e-4f && maxAbs < 0.7f) {
                        val escala = minOf(0.7f / maxAbs, Ajustes.capNorm)
                        for (k in 0 until VadSilero.CHUNK) chunkVad[k] *= escala
                    }
                    esVoz = try {
                        val p = vad!!.prob(chunkVad)
                        probSuave = if (p > probSuave) p else probSuave * 0.85f
                        probVoz = probSuave
                        probSuave >= Ajustes.umbralVad
                    } catch (e: Exception) {
                        vadRoto = true  // el modelo falló en runtime: energía para siempre
                        usandoVad = false
                        vozPorEnergia
                    }
                } else {
                    esVoz = vozPorEnergia
                }
                ultimoEsVoz = esVoz
                framesParte++
                if (esVoz) hablaMsParte += 32
                sumaNivelParte += nivel
                listener.onFrame(nivel, esVoz, silencioso, saturado)

                // claridad: acumular SOLO los frames con habla; publicar ~1 vez/s
                if (esVoz) {
                    calSnr[calIdx] = snrDb.toFloat()
                    calProb[calIdx] = probVoz
                    calSat[calIdx] = saturado
                    calIdx = (calIdx + 1) % CAL_VENTANA
                    if (calLlenos < CAL_VENTANA) calLlenos++
                }
                if (++framesDesdeCal >= 31) {
                    framesDesdeCal = 0
                    if (calLlenos >= 60) calidadVoz = calcularCalidad()  // ≥~2 s de habla
                }

                // encolar el PCM al encoder
                val idx = codec.dequeueInputBuffer(10_000L)
                if (idx >= 0) {
                    val bb: ByteBuffer = codec.getInputBuffer(idx)!!
                    bb.clear()
                    bb.order(ByteOrder.LITTLE_ENDIAN)  // PCM16 es little-endian SIEMPRE
                    for (s in pcm) { bb.putShort(s) }
                    val ptsUs = muestrasTotales * 1_000_000L / SR
                    codec.queueInputBuffer(idx, 0, FRAME * 2, ptsUs, 0)
                    muestrasTotales += FRAME
                }
                drenar(codec, hastaEos = false)

                // rotación pedida: cerrar la parte y abrir la siguiente (el encoder sigue)
                pedidoRotar.getAndSet(null)?.let { siguiente ->
                    cerrarParte()
                    abrirParte(siguiente)
                }
            }

            // fin: EOS al encoder, drenar lo pendiente y cerrar la última parte
            val idx = codec.dequeueInputBuffer(50_000L)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0,
                    muestrasTotales * 1_000_000L / SR, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drenar(codec, hastaEos = true)
            }
            cerrarParte()
        } catch (e: Exception) {
            try { muxer?.release() } catch (_: Exception) {}
            listener.onError(e.message ?: e.javaClass.simpleName)
        } finally {
            try { ns?.release() } catch (_: Exception) {}
            try { aec?.release() } catch (_: Exception) {}
            try { audio?.stop() } catch (_: Exception) {}
            try { audio?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            corriendo = false
        }
    }

}

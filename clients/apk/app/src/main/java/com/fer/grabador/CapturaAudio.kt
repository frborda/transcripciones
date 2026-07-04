package com.fer.grabador

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
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

            // SUPRESIÓN DE RUIDO por hardware (DSP del teléfono, tipo Krisp nativo):
            // limpia el ruido ambiente ANTES del encoder y del VAD. Si el equipo no
            // la trae, sigue sin ella (nsActivo lo refleja).
            if (supresionRuido) {
                try {
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        ns = android.media.audiofx.NoiseSuppressor.create(audio.audioSessionId)
                            ?.apply { enabled = true }
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

            while (!pedidoParar) {
                // leer un frame completo (32 ms)
                var leidas = 0
                while (leidas < FRAME && !pedidoParar) {
                    val n = audio.read(pcm, leidas, FRAME - leidas)
                    if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                    leidas += n
                }
                if (pedidoParar) break

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
                    // Tope ×16: con ×40 el ruido ambiente subía tanto que a veces
                    // pasaba por voz y los chunks no cortaban nunca.
                    if (maxAbs > 1e-4f && maxAbs < 0.5f) {
                        val escala = minOf(0.5f / maxAbs, 16f)
                        for (k in 0 until VadSilero.CHUNK) chunkVad[k] *= escala
                    }
                    esVoz = try {
                        vad!!.esVoz(chunkVad)
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

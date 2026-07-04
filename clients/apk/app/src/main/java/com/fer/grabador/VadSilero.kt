package com.fer.grabador

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * VAD neuronal on-device (Silero, ~2 MB) sobre ONNX Runtime.
 *
 * Recibe tramos de 512 muestras a 16 kHz (32 ms) y devuelve si hay HABLA, no solo
 * energía: distingue "voz baja" de "pausa", que es lo que el corte por amplitud
 * confundía. El estado recurrente se mantiene entre tramos (contexto).
 *
 * Soporta las dos firmas del modelo publicado: v5 (input/state/sr -> output/stateN)
 * y v4 (input/sr/h/c -> output/hn/cn). Cualquier error se propaga: el llamador
 * cae al detector de energía adaptativo.
 */
// umbral 0.35: con voz a nivel moderado el 0.5 clásico deja habla afuera; 0.3-0.4
// es el rango recomendado para detección de voz (vs. música/ruido).
class VadSilero(ctx: Context, private val umbral: Float = 0.35f) {

    companion object {
        const val CHUNK = 512      // muestras a 16 kHz = 32 ms
        const val CONTEXTO = 64    // v5 exige 64 muestras del chunk ANTERIOR adelante
        const val SR = 16000L
    }

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(ctx.assets.open("silero_vad.onnx").readBytes())
    private val esV5 = session.inputNames.contains("state")

    // estado recurrente: v5 usa "state" [2,1,128]; v4 usa h y c [2,1,64]
    private var state = FloatArray(2 * 1 * 128)
    private var h = FloatArray(2 * 1 * 64)
    private var c = FloatArray(2 * 1 * 64)
    // v5: la entrada es [1, 64+512] — las últimas 64 muestras del chunk anterior
    // van adelante. SIN este contexto el modelo no falla: devuelve ~0 SIEMPRE
    // (probabilidad clavada en 0.00 aunque se hable fuerte — bug de campo).
    private val contexto = FloatArray(CONTEXTO)
    private val entradaV5 = FloatArray(CONTEXTO + CHUNK)

    private var srTensor: OnnxTensor

    init {
        // el modelo puede querer "sr" como escalar (shape []) o como [1] según la
        // versión: probar con una inferencia real de punta a punta (self-test) y
        // quedarse con la que funcione. Si ninguna anda, el constructor lanza y el
        // llamador cae al detector de energía DESDE EL ARRANQUE (visible), no a
        // mitad de reunión.
        var elegido: OnnxTensor? = null
        var ultimo: Exception? = null
        for (shape in listOf(longArrayOf(), longArrayOf(1))) {
            val t = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SR)), shape)
            try {
                srTensor = t
                prob(FloatArray(CHUNK))  // self-test
                elegido = t
                break
            } catch (e: Exception) {
                ultimo = e
                t.close()
            }
        }
        srTensor = elegido ?: throw IllegalStateException(
            "self-test del VAD falló: ${ultimo?.message}")
        reset()
    }

    fun reset() {
        state.fill(0f); h.fill(0f); c.fill(0f); contexto.fill(0f)
    }

    /** true si el tramo (512 muestras float -1..1 a 16 kHz) contiene habla. */
    fun esVoz(chunk: FloatArray): Boolean = prob(chunk) >= umbral

    fun prob(chunk: FloatArray): Float {
        if (esV5) {
            // armar [contexto(64) + chunk(512)] y actualizar el contexto para el próximo
            System.arraycopy(contexto, 0, entradaV5, 0, CONTEXTO)
            System.arraycopy(chunk, 0, entradaV5, CONTEXTO, CHUNK)
            System.arraycopy(chunk, CHUNK - CONTEXTO, contexto, 0, CONTEXTO)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(entradaV5),
                                    longArrayOf(1, (CONTEXTO + CHUNK).toLong())).use { entrada ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(state),
                                        longArrayOf(2, 1, 128)).use { st ->
                    session.run(mapOf("input" to entrada, "state" to st, "sr" to srTensor)).use { r ->
                        val prob = (r[0].value as Array<FloatArray>)[0][0]
                        aplanar(r[1].value, state)
                        return prob
                    }
                }
            }
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk),
                                longArrayOf(1, CHUNK.toLong())).use { entrada ->
            run {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2, 1, 64)).use { th ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2, 1, 64)).use { tc ->
                        session.run(mapOf("input" to entrada, "sr" to srTensor,
                                          "h" to th, "c" to tc)).use { r ->
                            val prob = (r[0].value as Array<FloatArray>)[0][0]
                            aplanar(r[1].value, h)
                            aplanar(r[2].value, c)
                            return prob
                        }
                    }
                }
            }
        }
    }

    /** Copia un tensor [2][1][N] devuelto por la sesión al buffer plano del estado. */
    private fun aplanar(valor: Any, destino: FloatArray) {
        @Suppress("UNCHECKED_CAST")
        val t = valor as Array<Array<FloatArray>>
        var i = 0
        for (a in t) for (b in a) for (x in b) { destino[i++] = x }
    }
}

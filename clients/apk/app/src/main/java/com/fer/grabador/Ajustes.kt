package com.fer.grabador

import android.content.Context

/**
 * Ajustes VIVOS de la detección de voz: los cambia el slider de la pantalla
 * principal en plena reunión y el motor de captura los lee en cada frame.
 *
 * "supresion" 0..100 gradúa cuánto ruido se tolera antes de contar como voz:
 *   0   = máxima sensibilidad (detecta voz lejana/baja; el ruido puede colarse)
 *   100 = máxima supresión (solo voz clara y cercana cuenta como voz)
 * Se materializa en tres perillas internas del detector:
 *   - umbral de Silero: 0.20 → 0.60
 *   - tope de normalización de la entrada del VAD: ×64 → ×4 (geométrico; en el
 *     mínimo llega a levantar voz LEJANA que apenas mueve el micrófono)
 *   - NoiseSuppressor del DSP: APAGADO por debajo de 15 (el NS del fabricante
 *     aplasta la voz lejana/reverberante como si fuera ruido)
 */
object Ajustes {
    @Volatile var supresion: Int = 50

    // ECUALIZADOR de captura (dB, -12..+12 por banda): graves = shelf 120 Hz,
    // medios = campana 400 Hz (la "caja"/eco de sala vive ahí), presencia =
    // campana 3 kHz (inteligibilidad). 0/0/0 = plano (el filtro no corre).
    // Los setea el diálogo de EQ (manual) o la búsqueda automática.
    @Volatile var eqGraves = 0f
    @Volatile var eqMedios = 0f
    @Volatile var eqPresencia = 0f

    val umbralVad: Float get() = 0.20f + 0.40f * (supresion / 100f)
    val capNorm: Float
        get() = (256.0 * Math.pow(4.0 / 256.0, supresion / 100.0)).toFloat()  // 256→32→4
    val nsDeseado: Boolean get() = supresion >= 15

    fun cargar(c: Context) {
        supresion = Prefs.supresion(c)
        eqGraves = Prefs.eqG(c)
        eqMedios = Prefs.eqM(c)
        eqPresencia = Prefs.eqP(c)
    }
}

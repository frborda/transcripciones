package com.fer.grabador

import android.content.Context

/**
 * Ajustes VIVOS de la detección de voz: los cambia el slider de la pantalla
 * principal en plena reunión y el motor de captura los lee en cada frame.
 *
 * "supresion" 0..100 gradúa cuánto ruido se tolera antes de contar como voz:
 *   0   = máxima sensibilidad (detecta voz lejana/baja; el ruido puede colarse)
 *   100 = máxima supresión (solo voz clara y cercana cuenta como voz)
 * Se materializa en dos perillas internas del detector:
 *   - umbral de Silero: 0.20 → 0.70
 *   - tope de normalización de la entrada del VAD: ×16 → ×2
 */
object Ajustes {
    @Volatile var supresion: Int = 50

    val umbralVad: Float get() = 0.20f + 0.50f * (supresion / 100f)
    val capNorm: Float get() = 16f - 14f * (supresion / 100f)

    fun cargar(c: Context) {
        supresion = Prefs.supresion(c)
    }
}

package com.fer.grabador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Gráfico en tiempo real de la detección de voz (ventana de 30 s):
 *   - curva ROJA: probabilidad de voz de Silero (0..1)
 *   - banda VERDE: tramos donde el detector dio "voz" (lo que alimenta cortes y LED)
 *   - línea AZUL punteada: umbral del slider de supresión (se mueve en vivo)
 * Se alimenta con push() (~10 Hz desde la pantalla) y se limpia con reset().
 */
class GraficoVoz @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    companion object {
        const val MUESTRAS = 300   // 30 s a 10 Hz
    }

    private val prob = FloatArray(MUESTRAS)
    private val voz = BooleanArray(MUESTRAS)
    private var idx = 0
    private var llenos = 0

    private val pFondo = Paint().apply { color = 0xFF101014.toInt() }
    private val pGrid = Paint().apply {
        color = 0xFF2A2A32.toInt(); strokeWidth = 1f
    }
    private val pProb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEF4444.toInt(); style = Paint.Style.STROKE
        strokeWidth = 3.5f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val pVoz = Paint().apply { color = 0x3822C55E }        // verde translúcido
    private val pVozTope = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22C55E.toInt(); strokeWidth = 3f
    }
    private val pUmbral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val camino = Path()

    fun push(p: Float, v: Boolean) {
        prob[idx] = p
        voz[idx] = v
        idx = (idx + 1) % MUESTRAS
        llenos = min(llenos + 1, MUESTRAS)
        invalidate()
    }

    fun reset() {
        idx = 0; llenos = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 14f, 14f, pFondo)
        // grilla horizontal cada 25 %
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, pGrid)
        }
        if (llenos < 2) {
            // línea de umbral igual, para referencia
            val yU = h * (1f - Ajustes.umbralVad)
            canvas.drawLine(0f, yU, w, yU, pUmbral)
            return
        }
        val paso = w / (MUESTRAS - 1)
        val inicio = (idx - llenos + MUESTRAS) % MUESTRAS

        // banda verde: tramos con detección de voz (relleno + tope)
        var i = 0
        while (i < llenos) {
            if (voz[(inicio + i) % MUESTRAS]) {
                val x0 = (MUESTRAS - llenos + i) * paso
                var j = i
                while (j + 1 < llenos && voz[(inicio + j + 1) % MUESTRAS]) j++
                val x1 = (MUESTRAS - llenos + j) * paso + paso
                canvas.drawRect(x0, 0f, x1, h, pVoz)
                canvas.drawLine(x0, 2f, x1, 2f, pVozTope)
                i = j + 1
            } else i++
        }

        // curva roja: probabilidad de voz
        camino.reset()
        for (k in 0 until llenos) {
            val x = (MUESTRAS - llenos + k) * paso
            val y = h * (1f - prob[(inicio + k) % MUESTRAS].coerceIn(0f, 1f))
            if (k == 0) camino.moveTo(x, y) else camino.lineTo(x, y)
        }
        canvas.drawPath(camino, pProb)

        // umbral del slider (en vivo)
        val yU = h * (1f - Ajustes.umbralVad)
        canvas.drawLine(0f, yU, w, yU, pUmbral)
    }
}

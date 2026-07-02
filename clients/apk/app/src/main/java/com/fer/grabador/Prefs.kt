package com.fer.grabador

import android.content.Context

object Prefs {
    private const val NAME = "cfg"

    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun chatId(c: Context): String = sp(c).getString("chat_id", "") ?: ""
    fun intervaloSeg(c: Context): Int = sp(c).getInt("intervalo_seg", 60)
    fun auto(c: Context): Boolean = sp(c).getBoolean("auto", true)

    fun guardar(c: Context, token: String, chatId: String, intervaloSeg: Int, auto: Boolean) {
        sp(c).edit()
            .putString("token", token)
            .putString("chat_id", chatId)
            .putInt("intervalo_seg", if (intervaloSeg < 5) 5 else intervaloSeg)
            .putBoolean("auto", auto)
            .apply()
    }

    /** "hh:mm:ss", "mm:ss" o "ss" -> segundos (mínimo 5). */
    fun parseHms(s: String): Int {
        val p = s.trim().split(":")
        val seg = try {
            when (p.size) {
                1 -> p[0].trim().toInt()
                2 -> p[0].trim().toInt() * 60 + p[1].trim().toInt()
                3 -> p[0].trim().toInt() * 3600 + p[1].trim().toInt() * 60 + p[2].trim().toInt()
                else -> 60
            }
        } catch (e: Exception) {
            60
        }
        return if (seg < 5) 5 else seg
    }

    fun fmtHms(seg: Int): String =
        "%02d:%02d:%02d".format(seg / 3600, (seg % 3600) / 60, seg % 60)

    // sesión de grabación en curso (para retomar si la app muere a mitad de reunión)
    fun sesionActiva(c: Context): String = sp(c).getString("sesion_activa", "") ?: ""
    fun parteActual(c: Context): Int = sp(c).getInt("parte_actual", 0)
    fun marcarSesion(c: Context, sesion: String, parte: Int) {
        sp(c).edit().putString("sesion_activa", sesion).putInt("parte_actual", parte).apply()
    }
    fun limpiarSesion(c: Context) = marcarSesion(c, "", 0)

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

package com.fer.grabador

import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Cliente mínimo de la Bot API de Telegram (sin dependencias externas). */
object TelegramApi {

    private fun api(token: String, metodo: String) =
        URL("https://api.telegram.org/bot$token/$metodo")

    /** Código HTTP de la respuesta (200 = OK), o -1 si falló la red/conexión.
     *  Devolver el código deja que el llamador distinga un error permanente
     *  (4xx: token/chat/tamaño) de uno transitorio (red, 5xx, 429). */
    fun sendMessage(token: String, chatId: String, texto: String): Int {
        val con = api(token, "sendMessage").openConnection() as HttpURLConnection
        return try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            val body = "chat_id=" + URLEncoder.encode(chatId, "UTF-8") +
                    "&text=" + URLEncoder.encode(texto, "UTF-8")
            con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            con.responseCode
        } catch (e: Exception) {
            -1
        } finally {
            con.disconnect()
        }
    }

    fun sendDocument(token: String, chatId: String, archivo: File): Int {
        val boundary = "----grabador" + System.currentTimeMillis()
        val con = api(token, "sendDocument").openConnection() as HttpURLConnection
        return try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 20000
            con.readTimeout = 300000
            con.setChunkedStreamingMode(64 * 1024)
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            DataOutputStream(con.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                out.write(chatId.toByteArray(Charsets.UTF_8))
                out.writeBytes("\r\n--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"${archivo.name}\"\r\n")
                out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                archivo.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            con.responseCode
        } catch (e: Exception) {
            -1
        } finally {
            con.disconnect()
        }
    }

    /** chat_id del último mensaje que recibió el bot (mandale algo al bot antes de tocar Detectar). */
    fun detectarChatId(token: String): String? {
        val con = api(token, "getUpdates").openConnection() as HttpURLConnection
        return try {
            con.connectTimeout = 15000
            con.readTimeout = 30000
            val json = con.inputStream.bufferedReader().readText()
            val res = JSONObject(json).getJSONArray("result")
            if (res.length() == 0) return null
            res.getJSONObject(res.length() - 1)
                .optJSONObject("message")
                ?.getJSONObject("chat")
                ?.getLong("id")
                ?.toString()
        } catch (e: Exception) {
            null
        } finally {
            con.disconnect()
        }
    }
}

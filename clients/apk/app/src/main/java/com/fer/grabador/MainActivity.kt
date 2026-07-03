package com.fer.grabador

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var etToken: EditText
    private lateinit var etChat: EditText
    private lateinit var etIntervalo: EditText
    private lateinit var cbAuto: CompoundButton
    private lateinit var tvEstado: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvPartes: TextView
    private lateinit var dotEstado: View

    private val handler = Handler(Looper.getMainLooper())
    private val refresco = object : Runnable {
        override fun run() {
            val est = RecordService.estado
            tvEstado.text = est
            if (RecordService.corriendo) {
                val ahora = System.currentTimeMillis()
                tvTimer.text = "parte ${fmtSeg((ahora - RecordService.tParte) / 1000)}" +
                        "  ·  total ${fmtSeg((ahora - RecordService.tTotal) / 1000)}"
                tvTimer.visibility = View.VISIBLE
            } else {
                tvTimer.visibility = View.GONE
            }
            dotEstado.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, colorEstado(est)))
            tvPartes.text = listaPartes()
            handler.postDelayed(this, 1000)
        }
    }

    /** Color del indicador según el estado del servicio. */
    private fun colorEstado(est: String): Int = when {
        est.startsWith("ERROR") || est.startsWith("sin conexión") -> R.color.dot_err
        RecordService.corriendo && est.startsWith("grabando") -> R.color.dot_rec
        est.contains("subiendo") || est.contains("enviada") || est.contains("avisando") -> R.color.dot_up
        est.startsWith("listo") -> R.color.dot_done
        else -> R.color.dot_idle
    }

    private fun fmtSeg(s: Long): String =
        if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)

    /** Lista de las partes de la sesión actual (o la última) con su estado. */
    private fun listaPartes(): String {
        val ses = RecordService.sesionId.ifEmpty { Prefs.sesionActiva(this) }
        if (ses.isEmpty()) return "(sin sesión todavía)"
        val dir = File(getExternalFilesDir(null), "grabaciones")
        val files = dir.listFiles { f -> f.name.contains("reunion_${ses}_p") }
            ?.sortedBy { it.name.removePrefix("ok_") } ?: emptyList()
        if (files.isEmpty()) return "(sin partes todavía)"
        val sb = StringBuilder()
        var enviadas = 0
        for (f in files) {
            val nro = f.name.substringAfterLast("_p").substringBefore(".").toIntOrNull() ?: 0
            val mb = "%.1f".format(f.length() / 1048576.0)
            val st = when {
                f.name.startsWith("ok_") -> { enviadas++; "✅ enviada" }
                f.name == RecordService.grabandoArchivo -> "🎙 grabando..."
                f.name == RecordService.subiendoAhora -> "⬆ subiendo..."
                else -> "📦 pendiente"
            }
            sb.append("parte %02d · %s MB · %s\n".format(nro, mb, st))
        }
        sb.append("\n$enviadas/${files.size} enviadas")
        if (RecordService.pendientesN > 0) sb.append(" · ${RecordService.pendientesN} en cola")
        return sb.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etToken = findViewById(R.id.etToken)
        etChat = findViewById(R.id.etChat)
        etIntervalo = findViewById(R.id.etIntervalo)
        cbAuto = findViewById(R.id.cbAuto)
        tvEstado = findViewById(R.id.tvEstado)
        tvTimer = findViewById(R.id.tvTimer)
        tvPartes = findViewById(R.id.tvPartes)
        dotEstado = findViewById(R.id.dotEstado)

        etToken.setText(Prefs.token(this))
        etChat.setText(Prefs.chatId(this))
        etIntervalo.setText(Prefs.fmtHms(Prefs.intervaloSeg(this)))
        cbAuto.isChecked = Prefs.auto(this)

        findViewById<Button>(R.id.btnIniciar).setOnClickListener { iniciar() }
        findViewById<Button>(R.id.btnCortar).setOnClickListener { servicio(RecordService.ACTION_CUT) }
        findViewById<Button>(R.id.btnFinalizar).setOnClickListener { confirmarFinalizar() }
        findViewById<Button>(R.id.btnDetectar).setOnClickListener { detectarChat() }
        findViewById<Button>(R.id.btnBateria).setOnClickListener { abrirAjustesBateria() }

        // quedó una grabación sin terminar (la app murió): retomarla sola
        if (!RecordService.corriendo && Prefs.sesionActiva(this).isNotEmpty()) {
            toast("Retomando la grabación interrumpida...")
            iniciar()
        }
        if (intent?.getBooleanExtra("confirmar_fin", false) == true) confirmarFinalizar()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("confirmar_fin", false) == true) confirmarFinalizar()
    }

    private fun confirmarFinalizar() {
        if (!RecordService.corriendo) {
            toast("No hay grabación en curso")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Finalizar y procesar?")
            .setMessage("Se corta la grabación, se sube lo que falte y la PC genera y envía los PDFs. No se puede deshacer.")
            .setPositiveButton("Finalizar") { _, _ -> servicio(RecordService.ACTION_FINISH) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresco)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresco)
    }

    private fun guardarPrefs() {
        Prefs.guardar(
            this,
            etToken.text.toString().trim(),
            etChat.text.toString().trim(),
            Prefs.parseHms(etIntervalo.text.toString()),
            cbAuto.isChecked)
    }

    private fun iniciar() {
        guardarPrefs()
        if (Prefs.token(this).isEmpty() || Prefs.chatId(this).isEmpty()) {
            toast("Configurá el token del bot y el chat id primero")
            return
        }
        val faltan = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) faltan += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) faltan += Manifest.permission.POST_NOTIFICATIONS
        if (faltan.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltan.toTypedArray(), 1)
            return
        }
        val i = Intent(this, RecordService::class.java).setAction(RecordService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1 && res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) {
            iniciar()
        } else if (code == 1) {
            toast("Sin permiso de micrófono no puedo grabar")
        }
    }

    /** CUT/FINISH: la app está en primer plano, alcanza con startService. */
    private fun servicio(action: String) {
        guardarPrefs()
        if (!RecordService.corriendo) {
            toast("No hay grabación en curso")
            return
        }
        startService(Intent(this, RecordService::class.java).setAction(action))
    }

    private fun detectarChat() {
        guardarPrefs()
        val token = Prefs.token(this)
        if (token.isEmpty()) {
            toast("Pegá el token del bot primero")
            return
        }
        toast("Buscando... (antes mandale cualquier mensaje al bot)")
        Thread {
            val id = TelegramApi.detectarChatId(token)
            runOnUiThread {
                if (id != null) {
                    etChat.setText(id)
                    guardarPrefs()
                    toast("chat id detectado: $id")
                } else {
                    toast("No lo encontré: abrí el chat con el bot, mandá 'hola' y reintentá")
                }
            }
        }.start()
    }

    private fun abrirAjustesBateria() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}

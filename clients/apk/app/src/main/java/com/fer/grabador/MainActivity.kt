package com.fer.grabador

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvEstado: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvPartes: TextView
    private lateinit var dotEstado: View

    // el Salir de la notificación también cierra esta pantalla
    private val salirReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = finishAndRemoveTask()
    }

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

        tvEstado = findViewById(R.id.tvEstado)
        tvTimer = findViewById(R.id.tvTimer)
        tvPartes = findViewById(R.id.tvPartes)
        dotEstado = findViewById(R.id.dotEstado)

        findViewById<Button>(R.id.btnIniciar).setOnClickListener { iniciar() }
        findViewById<Button>(R.id.btnCortar).setOnClickListener { servicio(RecordService.ACTION_CUT) }
        findViewById<Button>(R.id.btnFinalizar).setOnClickListener { confirmarFinalizar() }
        findViewById<ImageButton>(R.id.btnConfig).setOnClickListener { abrirConfig() }
        findViewById<ImageButton>(R.id.btnSalir).setOnClickListener { salir() }

        ContextCompat.registerReceiver(this, salirReceiver,
            IntentFilter(RecordService.BC_SALIR), ContextCompat.RECEIVER_NOT_EXPORTED)

        // notificación persistente desde que se abre la app (solo Salir la quita);
        // en Android 13+ pide primero el permiso de notificaciones
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        mostrarNotificacion()

        // quedó una grabación sin terminar (la app murió): retomarla sola
        if (!RecordService.corriendo && Prefs.sesionActiva(this).isNotEmpty()) {
            toast("Retomando la grabación interrumpida...")
            iniciar()
        }
        if (intent?.getBooleanExtra("confirmar_fin", false) == true) confirmarFinalizar()
        if (intent?.getBooleanExtra("confirmar_salir", false) == true) salir()
    }

    private fun mostrarNotificacion() {
        ContextCompat.startForegroundService(
            this, Intent(this, RecordService::class.java).setAction(RecordService.ACTION_SHOW))
    }

    /** Salir (app o notificación): pide confirmación para no salir sin querer. */
    private fun salir() {
        if (RecordService.corriendo) {
            toast("Hay una grabación en curso: finalizala antes de salir")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Salir del grabador?")
            .setMessage("Se cierra la app y se quita la notificación de la barra.")
            .setPositiveButton("Salir") { _, _ ->
                startService(Intent(this, RecordService::class.java)
                    .setAction(RecordService.ACTION_EXIT))
                finishAndRemoveTask()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        try { unregisterReceiver(salirReceiver) } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("confirmar_fin", false) == true) confirmarFinalizar()
        if (intent?.getBooleanExtra("confirmar_salir", false) == true) salir()
    }

    /** Configuración en un diálogo: se usa una vez y no ocupa la pantalla principal. */
    private fun abrirConfig() {
        val v = layoutInflater.inflate(R.layout.dialog_config, null)
        val etToken = v.findViewById<EditText>(R.id.etToken)
        val etChat = v.findViewById<EditText>(R.id.etChat)
        val etIntervalo = v.findViewById<EditText>(R.id.etIntervalo)
        val swAuto = v.findViewById<CompoundButton>(R.id.swAuto)

        etToken.setText(Prefs.token(this))
        etChat.setText(Prefs.chatId(this))
        etIntervalo.setText(Prefs.fmtHms(Prefs.intervaloSeg(this)))
        swAuto.isChecked = Prefs.auto(this)

        v.findViewById<View>(R.id.btnDetectar).setOnClickListener {
            val token = etToken.text.toString().trim()
            if (token.isEmpty()) {
                toast("Pegá el token del bot primero")
                return@setOnClickListener
            }
            toast("Buscando... (antes mandale cualquier mensaje al bot)")
            Thread {
                val id = TelegramApi.detectarChatId(token)
                runOnUiThread {
                    if (id != null) {
                        etChat.setText(id)
                        toast("chat id detectado: $id")
                    } else {
                        toast("No lo encontré: escribí algo en el chat del bot y reintentá")
                    }
                }
            }.start()
        }
        v.findViewById<View>(R.id.btnBateria).setOnClickListener { abrirAjustesBateria() }

        MaterialAlertDialogBuilder(this)
            .setTitle("Configuración")
            .setView(v)
            .setPositiveButton("Guardar") { _, _ ->
                Prefs.guardar(
                    this,
                    etToken.text.toString().trim(),
                    etChat.text.toString().trim(),
                    Prefs.parseHms(etIntervalo.text.toString()),
                    swAuto.isChecked)
                toast("Configuración guardada")
            }
            .setNegativeButton("Cancelar", null)
            .show()
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

    private fun iniciar() {
        if (Prefs.token(this).isEmpty() || Prefs.chatId(this).isEmpty()) {
            toast("Configurá el token del bot y el chat id primero")
            abrirConfig()
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
        } else if (code == 2) {
            mostrarNotificacion()  // recién ahora la notificación puede verse
        }
    }

    /** CUT/FINISH: la app está en primer plano, alcanza con startService. */
    private fun servicio(action: String) {
        if (!RecordService.corriendo) {
            toast("No hay grabación en curso")
            return
        }
        startService(Intent(this, RecordService::class.java).setAction(action))
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

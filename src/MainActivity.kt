package ir.biral.tgrelay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etKey: EditText
    private lateinit var etWorker: EditText
    private lateinit var etPort: EditText
    private lateinit var btnToggle: Button
    private lateinit var btnOpenTelegram: Button
    private lateinit var tvStatus: TextView
    private lateinit var dotStatus: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.bg_top)
        }

        prefs = getSharedPreferences("tgrelay", MODE_PRIVATE)
        etKey = findViewById(R.id.etKey)
        etWorker = findViewById(R.id.etWorker)
        etPort = findViewById(R.id.etPort)
        btnToggle = findViewById(R.id.btnToggle)
        btnOpenTelegram = findViewById(R.id.btnOpenTelegram)
        tvStatus = findViewById(R.id.tvStatus)
        dotStatus = findViewById(R.id.dotStatus)

        etKey.setText(prefs.getString("key", ""))
        etWorker.setText(prefs.getString("worker", ""))
        etPort.setText(prefs.getString("port", "1080"))

        btnToggle.setOnClickListener { onToggleClicked() }
        btnOpenTelegram.setOnClickListener { openInTelegram() }
    }

    override fun onStart() {
        super.onStart()
        // Get notified here whenever the service state changes (started / stopped / errored)
        ProxyService.listener = { updateStatus() }
        updateStatus()
    }

    override fun onStop() {
        super.onStop()
        ProxyService.listener = null
    }

    private fun openInTelegram() {
        if (ProxyService.state != ProxyService.State.RUNNING) {
            Toast.makeText(this, "Tap \"Activate\" first and wait until the status turns active", Toast.LENGTH_SHORT).show()
            return
        }
        val port = etPort.text.toString().trim().ifEmpty { "1080" }
        val tgUri = Uri.parse("tg://socks?server=127.0.0.1&port=$port")
        val webUri = Uri.parse("https://t.me/socks?server=127.0.0.1&port=$port")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, tgUri))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "Telegram is not installed on this phone", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onToggleClicked() {
        when (ProxyService.state) {
            ProxyService.State.RUNNING -> {
                stopService(Intent(this, ProxyService::class.java))
                updateStatus()
            }
            ProxyService.State.CONNECTING -> {
                Toast.makeText(this, "Still checking, please wait a moment", Toast.LENGTH_SHORT).show()
            }
            ProxyService.State.STOPPED -> startProxy()
        }
    }

    private fun startProxy() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val worker = etWorker.text.toString().trim()
            .removePrefix("https://").removePrefix("wss://").removeSuffix("/")
        val port = etPort.text.toString().trim().toIntOrNull() ?: 1080
        val key = etKey.text.toString().trim()

        if (worker.isEmpty()) {
            Toast.makeText(this, "Enter the Cloudflare Worker address", Toast.LENGTH_SHORT).show()
            return
        }
        if (key.isEmpty()) {
            Toast.makeText(this, "Enter the shared key (the AUTH_KEY)", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().putString("worker", worker).putString("port", port.toString()).putString("key", key).apply()

        val intent = Intent(this, ProxyService::class.java).apply {
            putExtra("worker", worker)
            putExtra("port", port)
            putExtra("key", key)
        }

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateStatus()
    }

    private fun updateStatus() {
        when (ProxyService.state) {
            ProxyService.State.RUNNING -> {
                val port = prefs.getString("port", "1080")
                tvStatus.text = "Active — 127.0.0.1:$port"
                dotStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.dot_running)
                btnToggle.text = "Deactivate"
                btnToggle.isEnabled = true
                btnToggle.background = ContextCompat.getDrawable(this, R.drawable.bg_button_danger)
                btnToggle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
            ProxyService.State.CONNECTING -> {
                tvStatus.text = "Checking key and connection..."
                dotStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.dot_connecting)
                btnToggle.text = "Checking..."
                btnToggle.isEnabled = false
                btnToggle.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
                btnToggle.setTextColor(android.graphics.Color.parseColor("#1B1B1B"))
            }
            ProxyService.State.STOPPED -> {
                tvStatus.text = "Stopped"
                dotStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.dot_stopped)
                btnToggle.text = "Activate"
                btnToggle.isEnabled = true
                btnToggle.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
                btnToggle.setTextColor(android.graphics.Color.parseColor("#1B1B1B"))
            }
        }
    }
}

package ir.biral.tgrelay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
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
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etKey: EditText
    private lateinit var etWorker: EditText
    private lateinit var etPort: EditText
    private lateinit var btnToggle: Button
    private lateinit var btnOpenTelegram: Button
    private lateinit var btnGenKey: Button
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
        btnGenKey = findViewById(R.id.btnGenKey)
        tvStatus = findViewById(R.id.tvStatus)
        dotStatus = findViewById(R.id.dotStatus)

        etKey.setText(prefs.getString("key", ""))
        etWorker.setText(prefs.getString("worker", ""))
        etPort.setText(prefs.getString("port", "1080"))

        btnToggle.setOnClickListener { onToggleClicked() }
        btnOpenTelegram.setOnClickListener { openInTelegram() }
        btnGenKey.setOnClickListener { generateKey() }
    }

    override fun onStart() {
        super.onStart()
        // هر وقت وضعیت سرویس عوض بشه (فعال شد / قطع شد / خطا داد)، همینجا خبردار می‌شیم
        ProxyService.listener = { updateStatus() }
        updateStatus()
    }

    override fun onStop() {
        super.onStop()
        ProxyService.listener = null
    }

    private fun generateKey() {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val key = bytes.joinToString("") { "%02x".format(it) }
        etKey.setText(key)

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AUTH_KEY", key))

        Toast.makeText(this, "کلید ساخته شد و کپی شد — حالا برو توی Cloudflare بهش بچسبونش", Toast.LENGTH_LONG).show()
    }

    private fun openInTelegram() {
        if (ProxyService.state != ProxyService.State.RUNNING) {
            Toast.makeText(this, "اول دکمه «فعال کردن» رو بزن و صبر کن وضعیت فعال بشه", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "تلگرام روی این گوشی نصب نیست", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "در حال بررسیه، چند لحظه صبر کن", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "آدرس Cloudflare Worker رو وارد کن", Toast.LENGTH_SHORT).show()
            return
        }
        if (key.isEmpty()) {
            Toast.makeText(this, "کلید مشترک رو وارد کن (همون AUTH_KEY)", Toast.LENGTH_SHORT).show()
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
                tvStatus.text = "فعال — 127.0.0.1:$port"
                dotStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.dot_running)
                btnToggle.text = "غیرفعال کردن"
                btnToggle.isEnabled = true
                btnToggle.background = ContextCompat.getDrawable(this, R.drawable.bg_button_danger)
                btnToggle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
            ProxyService.State.CONNECTING -> {
                tvStatus.text = "در حال بررسی کلید و اتصال..."
                dotStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.dot_connecting)
                btnToggle.text = "در حال بررسی..."
                btnToggle.isEnabled = false
                btnToggle.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
                btnToggle.setTextColor(android.graphics.Color.parseColor("#1B1B1B"))
            }
            ProxyService.State.STOPPED -> {
                tvStatus.text = "غیرفعال"
                dotStatus.backgroundTintList = ContextCompat.getColorStateList(this, R.color.dot_stopped)
                btnToggle.text = "فعال کردن"
                btnToggle.isEnabled = true
                btnToggle.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
                btnToggle.setTextColor(android.graphics.Color.parseColor("#1B1B1B"))
            }
        }
    }
}

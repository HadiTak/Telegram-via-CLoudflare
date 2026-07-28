package ir.biral.tgrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Local SOCKS5 server. Telegram is configured (in its own proxy settings) to
 * connect here. Every connection Telegram makes is forwarded to a Cloudflare
 * Worker over a WebSocket (wss://, never filtered), and the Worker opens the
 * real TCP connection to Telegram's servers on our behalf.
 *
 * هر پیام قبل از ارسال یه padding تصادفی کوچیک می‌گیره تا اندازه‌ی بسته‌ها
 * (که می‌تونه امضای MTProto رو لو بده) نامنظم بشه. این padding فقط روی لینک
 * app<->worker اضافه می‌شه و قبل از رسیدن به تلگرام حذف می‌شه.
 */
class ProxyService : Service() {

    enum class State { STOPPED, CONNECTING, RUNNING }

    companion object {
        @Volatile var state: State = State.STOPPED
            private set
        @Volatile var listener: (() -> Unit)? = null
        const val CHANNEL_ID = "tgrelay_channel"
        const val NOTIF_ID = 1
        const val MAX_PAD = 63

        private val stateHandler = Handler(Looper.getMainLooper())

        private fun setState(s: State) {
            state = s
            stateHandler.post { listener?.invoke() }
        }

        fun wrapWithPadding(data: ByteArray): ByteArray {
            val padLen = Random.nextInt(0, MAX_PAD + 1)
            val out = ByteArray(1 + padLen + data.size)
            out[0] = padLen.toByte()
            if (padLen > 0) Random.nextBytes(out, 1, 1 + padLen)
            System.arraycopy(data, 0, out, 1 + padLen, data.size)
            return out
        }

        fun unwrapPadding(data: ByteArray): ByteArray {
            if (data.isEmpty()) return data
            val padLen = data[0].toInt() and 0xFF
            val start = 1 + padLen
            if (start > data.size) return ByteArray(0)
            return data.copyOfRange(start, data.size)
        }
    }

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var workerHost: String = ""
    private var localPort: Int = 1080
    private var authKey: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        workerHost = intent?.getStringExtra("worker") ?: return START_NOT_STICKY
        localPort = intent.getIntExtra("port", 1080)
        authKey = intent.getStringExtra("key") ?: ""

        setState(State.CONNECTING)
        startForeground(NOTIF_ID, buildNotification("در حال بررسی کلید و اتصال..."))

        pool.execute { verifyThenStart() }

        return START_STICKY
    }

    /** قبل از هر چیز، یه درخواست ساده به /check می‌فرسته تا مطمئن بشه کلید و آدرس درستن. */
    private fun verifyThenStart() {
        val checkUrl = "https://$workerHost/check"
        try {
            val req = Request.Builder()
                .url(checkUrl)
                .addHeader("X-Auth-Key", authKey)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    setState(State.RUNNING)
                    mainHandler.post {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm?.notify(NOTIF_ID, buildNotification("فعال است — 127.0.0.1:$localPort"))
                    }
                    runServer()
                    return
                }
                failAndStop(messageFor(resp.code))
            }
        } catch (e: Exception) {
            failAndStop("اتصال به Worker برقرار نشد — آدرس Worker یا اینترنت رو چک کن")
        }
    }

    private fun messageFor(code: Int): String = when (code) {
        403 -> "کلید مشترک اشتباهه — با کلید AUTH_KEY توی Cloudflare یکی نیست"
        404 -> "آدرس Worker درست نیست یا این نسخه از Worker رو دیپلوی نکردید"
        else -> "خطا از سمت Worker (کد $code)"
    }

    private fun failAndStop(message: String) {
        setState(State.STOPPED)
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runServer() {
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress("127.0.0.1", localPort))
            serverSocket = s
            while (state == State.RUNNING) {
                val client = s.accept()
                pool.execute { handleClient(client) }
            }
        } catch (e: Exception) {
            // server socket closed on stop, or bind failed (e.g. port in use)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // SOCKS5 greeting
            val greeting = ByteArray(2)
            if (readFully(input, greeting) != 2) { socket.close(); return }
            val nMethods = greeting[1].toInt() and 0xFF
            val methods = ByteArray(nMethods)
            readFully(input, methods)
            output.write(byteArrayOf(0x05, 0x00)) // no-auth accepted
            output.flush()

            // SOCKS5 connect request
            val head = ByteArray(4)
            if (readFully(input, head) != 4) { socket.close(); return }
            val atyp = head[3].toInt() and 0xFF

            val destHost: String = when (atyp) {
                0x01 -> { // IPv4
                    val a = ByteArray(4); readFully(input, a)
                    a.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> { // domain name
                    val len = input.read()
                    val a = ByteArray(len); readFully(input, a)
                    String(a)
                }
                else -> { socket.close(); return } // IPv6 not supported
            }
            val portBytes = ByteArray(2)
            readFully(input, portBytes)

            // reply: success
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            relayThroughWorker(destHost, socket, input, output)

        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun relayThroughWorker(destHost: String, socket: Socket, input: InputStream, output: OutputStream) {
        val url = "wss://$workerHost/apiws?dst=$destHost"
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Auth-Key", authKey)
            .build()
        val closed = AtomicBoolean(false)

        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val real = unwrapPadding(bytes.toByteArray())
                    if (real.isNotEmpty()) {
                        output.write(real)
                        output.flush()
                    }
                } catch (_: Exception) {
                    closeAll(closed, socket, webSocket)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                reportConnectionFailure(response)
                closeAll(closed, socket, webSocket)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closeAll(closed, socket, webSocket)
            }
        })

        pool.execute {
            try {
                val buf = ByteArray(8192)
                while (!closed.get()) {
                    val n = input.read(buf)
                    if (n == -1) break
                    ws.send(wrapWithPadding(buf.copyOf(n)).toByteString())
                }
            } catch (_: Exception) {
            } finally {
                closeAll(closed, socket, ws)
            }
        }
    }

    private val midSessionErrorShown = AtomicBoolean(false)

    private fun reportConnectionFailure(response: Response?) {
        if (!midSessionErrorShown.compareAndSet(false, true)) return // فقط یه‌بار در هر فعال‌سازی

        val message = when (response?.code) {
            403 -> "کلید مشترک اشتباهه — با کلید AUTH_KEY توی Cloudflare یکی نیست"
            404 -> "آدرس Worker درست نیست یا مسیر /apiws پیدا نشد"
            null -> "اتصال به Worker برقرار نشد — آدرس Worker یا اینترنت رو چک کن"
            else -> "خطا از سمت Worker (کد ${response.code})"
        }

        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification("خطا: $message"))
    }

    private fun closeAll(closed: AtomicBoolean, socket: Socket, ws: WebSocket) {
        if (closed.compareAndSet(false, true)) {
            try { ws.close(1000, null) } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n == -1) return off
            off += n
        }
        return off
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "TG Relay", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TG Relay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        setState(State.STOPPED)
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

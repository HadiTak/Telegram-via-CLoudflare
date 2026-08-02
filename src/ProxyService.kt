package ir.biral.tgrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Local SOCKS5 server. Telegram is configured (in its own proxy settings) to
 * connect here. Every connection Telegram makes is forwarded to a Cloudflare
 * Worker over a WebSocket (wss://, never filtered), and the Worker opens the
 * real TCP connection to Telegram's servers on our behalf.
 *
 * Each message gets a small random padding before being sent, so packet
 * sizes (which could reveal the MTProto signature) become irregular. This
 * padding only exists on the app<->worker link and is stripped before it
 * reaches Telegram.
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
        const val ACTION_STOP = "ir.biral.tgrelay.ACTION_STOP"

        // How many parallel connection attempts we race per single Telegram connection.
        // Higher = faster/more resilient against occasional Cloudflare hiccups, but more
        // Durable Object / TCP socket usage per connection. 3 is a reasonable balance;
        // feel free to raise it if you want to trade more resource usage for more speed.
        const val RACE_COUNT = 3

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
    private val activeSockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    private val activeWebSockets = java.util.Collections.synchronizedSet(mutableSetOf<WebSocket>())
    private val pool = Executors.newCachedThreadPool()
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var workerHost: String = ""
    private var localPort: Int = 1080
    private var authKey: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val everConnected = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setState(State.STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        workerHost = intent?.getStringExtra("worker") ?: return START_NOT_STICKY
        localPort = intent.getIntExtra("port", 1080)
        authKey = intent.getStringExtra("key") ?: ""

        setState(State.CONNECTING)
        startForeground(NOTIF_ID, buildNotification("Checking key and connection..."))

        pool.execute { verifyThenStart() }

        return START_STICKY
    }

    /** Before anything else, sends a simple request to /check to make sure the key and address are correct. */
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
                    everConnected.set(false)
                    mainHandler.post {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm?.notify(NOTIF_ID, buildNotification("Active — 127.0.0.1:$localPort", showStopAction = true))
                    }
                    scheduleNoConnectionWatchdog()
                    runServer()
                    return
                }
                failAndStop(messageFor(resp.code))
            }
        } catch (e: Exception) {
            failAndStop("Could not connect to the Worker -- check the Worker address or your internet")
        }
    }

    private fun messageFor(code: Int): String = when (code) {
        403 -> "Wrong shared key -- it does not match the AUTH_KEY set in Cloudflare"
        404 -> "Worker address is wrong, or this version of the Worker has not been deployed yet"
        else -> "Error from the Worker (code $code)"
    }

    /**
     * Instead of alerting on every single failed relay attempt (Telegram opens many
     * parallel connections, so isolated failures are normal and self-recovering),
     * we wait 15 seconds and only warn if genuinely NOT ONE connection has succeeded
     * in that time -- that's the only situation actually worth interrupting the user for.
     */
    private fun scheduleNoConnectionWatchdog() {
        mainHandler.postDelayed({
            if (state == State.RUNNING && !everConnected.get()) {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(
                    NOTIF_ID,
                    buildNotification(
                        "No connection to Telegram yet after 15 seconds. Double-check the Worker address and key, and make sure the proxy is selected in Telegram's settings.",
                        showStopAction = true
                    )
                )
            }
        }, 15000)
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
                activeSockets.add(client)
                pool.execute { handleClient(client) }
            }
        } catch (e: Exception) {
            if (state == State.RUNNING) {
                // This means the local SOCKS5 server actually failed to start (e.g. the port
                // was already in use), but the state had been wrongly left as "running" --
                // fix that here.
                setState(State.STOPPED)
                mainHandler.post {
                    Toast.makeText(
                        this,
                        "Local SOCKS5 server failed to start (port $localPort might already be in use) -- ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // else: this is normal, it means we were the ones stopping the service
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            try { socket.tcpNoDelay = true } catch (_: Exception) {}
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // SOCKS5 greeting
            val greeting = ByteArray(2)
            if (readFully(input, greeting) != 2) { closeSocket(socket); return }
            val nMethods = greeting[1].toInt() and 0xFF
            val methods = ByteArray(nMethods)
            readFully(input, methods)
            output.write(byteArrayOf(0x05, 0x00)) // no-auth accepted
            output.flush()

            // SOCKS5 connect request
            val head = ByteArray(4)
            if (readFully(input, head) != 4) { closeSocket(socket); return }
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
                0x04 -> { // IPv6
                    val a = ByteArray(16); readFully(input, a)
                    java.net.InetAddress.getByAddress(a).hostAddress
                }
                else -> {
                    reportLocalError("Unknown SOCKS5 request (ATYP=$atyp) -- rejected")
                    closeSocket(socket); return
                }
            }
            val portBytes = ByteArray(2)
            readFully(input, portBytes)

            // reply: success
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            relayThroughWorker(destHost, socket, input, output)

        } catch (e: Exception) {
            reportLocalError("Local SOCKS5 request failed: ${e.message}")
            closeSocket(socket)
        }
    }

    /** Closes the socket and removes it from the active-connections list. */
    private fun closeSocket(socket: Socket) {
        try { socket.close() } catch (_: Exception) {}
        activeSockets.remove(socket)
    }

    /** For errors that happen before reaching the Worker (in the local SOCKS5 layer itself). */
    private fun reportLocalError(message: String) {
        if (!midSessionErrorShown.compareAndSet(false, true)) return
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        // Note: we deliberately don't touch the persistent notification here -- this
        // error only concerns a single connection, not the whole service, and
        // Telegram usually just retries on its own.
    }

    /**
     * Opens up to RACE_COUNT parallel WebSocket connection attempts to the Worker for this
     * single Telegram connection. Whichever one successfully opens first "wins" and is used
     * for the whole session; every other (slower or failed) attempt is cancelled immediately.
     * This speeds up connection establishment and makes each connection more resilient to
     * occasional transient Cloudflare errors, since one bad attempt no longer means the whole
     * connection has to be retried from scratch by Telegram.
     */
    private fun relayThroughWorker(destHost: String, socket: Socket, input: InputStream, output: OutputStream) {
        val encodedDst = java.net.URLEncoder.encode(destHost, "UTF-8")
        val url = "wss://$workerHost/apiws?dst=$encodedDst"
        val closed = AtomicBoolean(false)
        val winner = AtomicReference<WebSocket>(null)
        val pumpStarted = AtomicBoolean(false)
        val racers = java.util.Collections.synchronizedSet(mutableSetOf<WebSocket>())

        fun discard(ws: WebSocket) {
            try { ws.cancel() } catch (_: Exception) {}
            activeWebSockets.remove(ws)
            racers.remove(ws)
        }

        fun startPump(ws: WebSocket) {
            if (!pumpStarted.compareAndSet(false, true)) return
            // this racer won -- immediately drop every other attempt for this connection
            synchronized(racers) {
                for (r in racers.toList()) if (r !== ws) discard(r)
            }
            pool.execute {
                try {
                    val buf = ByteArray(65536)
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

        repeat(RACE_COUNT) {
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Auth-Key", authKey)
                .build()
            val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    midSessionErrorShown.set(false)
                    if (winner.compareAndSet(null, webSocket)) {
                        startPump(webSocket)
                    } else {
                        discard(webSocket) // another racer already won
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (winner.get() !== webSocket) return // stray data from a discarded loser
                    everConnected.set(true)
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
                    if (winner.get() === webSocket) {
                        // A single failed connection attempt isn't worth interrupting the user for --
                        // Telegram opens several in parallel and retries on its own. We only alert
                        // immediately for actionable config errors (wrong key / wrong address);
                        // anything else is left to the 15-second watchdog, which only fires if
                        // NOT ONE connection has ever succeeded.
                        if (response?.code == 403 || response?.code == 404) {
                            reportConnectionFailure(response)
                        }
                        closeAll(closed, socket, webSocket)
                    } else {
                        discard(webSocket)
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (winner.get() === webSocket) {
                        // Not every close is an error -- Telegram routinely opens and closes many
                        // parallel connections, so we don't report anything here.
                        closeAll(closed, socket, webSocket)
                    } else {
                        discard(webSocket)
                    }
                }
            })
            activeWebSockets.add(ws)
            racers.add(ws)
        }
    }

    private val midSessionErrorShown = AtomicBoolean(false)

    private fun reportConnectionFailure(response: Response?, overrideMessage: String? = null) {
        if (!midSessionErrorShown.compareAndSet(false, true)) return // only once per activation

        val message = overrideMessage ?: when (response?.code) {
            403 -> "Wrong shared key -- it does not match the AUTH_KEY set in Cloudflare"
            404 -> "Worker address is wrong, or the /apiws path was not found"
            null -> "A connection to the Worker failed -- if Telegram is still working, do not worry, it usually retries on its own"
            else -> "Error from the Worker (code ${response.code})"
        }

        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        // Note: we deliberately don't touch the persistent notification -- this error
        // is about a single connection (Telegram keeps several in parallel), not
        // the whole service going down.
    }

    private fun closeAll(closed: AtomicBoolean, socket: Socket, ws: WebSocket) {
        if (closed.compareAndSet(false, true)) {
            try { ws.close(1000, null) } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            activeSockets.remove(socket)
            activeWebSockets.remove(ws)
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

    private fun buildNotification(text: String, showStopAction: Boolean = false): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "TG Relay CF", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openAppIntent, piFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TG Relay CF")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(contentIntent)
            .setOngoing(true)

        if (showStopAction) {
            val stopIntent = Intent(this, ProxyService::class.java).apply { action = ACTION_STOP }
            val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, piFlags)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
        }

        return builder.build()
    }

    override fun onDestroy() {
        setState(State.STOPPED)
        try { serverSocket?.close() } catch (_: Exception) {}

        // Important: up to this point we've only blocked *new* connections. Any
        // connections that were already established (Telegram's current session)
        // must be explicitly closed too, or they'll keep working as if nothing
        // was ever stopped.
        synchronized(activeSockets) {
            for (s in activeSockets) {
                try { s.close() } catch (_: Exception) {}
            }
            activeSockets.clear()
        }
        synchronized(activeWebSockets) {
            for (w in activeWebSockets) {
                try { w.cancel() } catch (_: Exception) {} // cancel = immediate teardown, no waiting for the close handshake
            }
            activeWebSockets.clear()
        }

        pool.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

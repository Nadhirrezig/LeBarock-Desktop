package com.lebarock.lebarock_desktop.data.realtime

import com.lebarock.lebarock_desktop.data.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** A signal from the caisse: what changed ([event]) on which [channel] (`private-floor.1`…), and its small payload. */
data class LiveEvent(val channel: String, val event: String, val data: JsonObject) {
    /** `floor`, `kitchen`, `waiter`, `printer`. */
    val kind: String get() = channel.removePrefix("private-").substringBefore('.')
}

/**
 * Live updates (client, 2026-11-11): one websocket to the caisse's Reverb server (Pusher protocol 7), through the same
 * address and port as the API (Nginx forwards `/app/`). It joins its private channels (each signed by the caisse through
 * [authorize]), answers the server's pings, and reconnects by itself (1 s, 2 s, 4 s … 30 s). The signals only say
 * something changed: the screens read the rest through the API, and keep a slow check while [connected] is false.
 */
class LiveSocket(
    private val http: () -> OkHttpClient,
    private val name: String,
    /** Signs [channel] for this [socketId]: the "auth" string, or null when refused. Called off the main thread. */
    private val authorize: suspend (socketId: String, channel: String) -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxBackoffMillis: Long = 30_000,
) {
    data class Target(val wsUrl: String, val channels: List<String>)

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    /** True while the live connection is open and at least one channel joined. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var target: Target? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0
    private var reconnect: Job? = null
    private var backoff = 1_000L

    /** Opens (or re-opens on a new address / channels) the connection. */
    @Synchronized
    fun connect(target: Target) {
        if (this.target == target && socket != null) return
        this.target = target
        backoff = 1_000L
        open()
    }

    /** Closes it for good (signed out, live updates off). */
    @Synchronized
    fun disconnect() {
        target = null
        reconnect?.cancel()
        reconnect = null
        generation++
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
    }

    @Synchronized
    private fun open() {
        val t = target ?: return
        reconnect?.cancel()
        reconnect = null
        val gen = ++generation
        socket?.cancel()
        _connected.value = false
        socket = try {
            http().newWebSocket(Request.Builder().url(t.wsUrl).build(), Listener(gen, t))
        } catch (e: Exception) {
            AppLog.warn("Direct ($name) : connexion impossible (${e.message})")
            null.also { scheduleReconnect(gen) }
        }
    }

    @Synchronized
    private fun scheduleReconnect(gen: Int) {
        if (gen != generation || target == null) return
        _connected.value = false
        val wait = backoff
        backoff = (backoff * 2).coerceAtMost(maxBackoffMillis)
        reconnect?.cancel()
        reconnect = scope.launch {
            delay(wait)
            synchronized(this@LiveSocket) { if (gen == generation) open() }
        }
    }

    private inner class Listener(private val gen: Int, private val target: Target) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val event = frame["event"]?.jsonPrimitive?.contentOrNull ?: return
            when {
                event == "pusher:connection_established" -> {
                    val socketId = dataOf(frame)["socket_id"]?.jsonPrimitive?.contentOrNull ?: return
                    backoff = 1_000L
                    target.channels.forEach { channel -> scope.launch { subscribe(webSocket, socketId, channel) } }
                }
                event == "pusher:ping" -> webSocket.send("""{"event":"pusher:pong","data":{}}""")
                event == "pusher_internal:subscription_succeeded" -> {
                    if (!_connected.value) AppLog.info("Direct ($name) : en direct (${frame["channel"]?.jsonPrimitive?.contentOrNull ?: ""})")
                    _connected.value = true
                }
                event == "pusher:error" -> {
                    val code = dataOf(frame)["code"]?.jsonPrimitive?.intOrNull
                    AppLog.warn("Direct ($name) : erreur du serveur ${code ?: ""} ${dataOf(frame)["message"]?.jsonPrimitive?.contentOrNull ?: ""}")
                    webSocket.close(1000, "error")
                }
                event.startsWith("pusher") -> Unit
                else -> {
                    val channel = frame["channel"]?.jsonPrimitive?.contentOrNull ?: return
                    _events.tryEmit(LiveEvent(channel, event, dataOf(frame)))
                }
            }
        }

        private suspend fun subscribe(webSocket: WebSocket, socketId: String, channel: String) {
            val auth = try {
                authorize(socketId, channel)
            } catch (e: Exception) {
                AppLog.warn("Direct ($name) : canal $channel non signé (${e.message})")
                null
            }
            if (gen != generation) return
            if (auth == null) {
                webSocket.close(1000, "refused")
                return
            }
            val message = buildJsonObject {
                put("event", "pusher:subscribe")
                put("data", buildJsonObject {
                    put("channel", channel)
                    put("auth", auth)
                })
            }
            webSocket.send(message.toString())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen == generation) scheduleReconnect(gen)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation) return
            AppLog.warn("Direct ($name) : connexion perdue (${t.message ?: response?.code?.toString() ?: "fermée par la caisse"}), reconnexion…")
            scheduleReconnect(gen)
        }
    }

    /** Pusher sends `data` as a JSON string (or, from some servers, an object). */
    private fun dataOf(frame: JsonObject): JsonObject = when (val data = frame["data"]) {
        is JsonObject -> data
        is JsonPrimitive -> data.contentOrNull?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        else -> JsonObject(emptyMap())
    }

    companion object {
        /** `http://host:port/` + `/app/KEY?…` → `ws://host:port/app/KEY?…` (https → wss). */
        fun wsUrl(baseUrl: String, path: String): String {
            val base = baseUrl.trimEnd('/')
            val ws = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> base
            }
            return ws + "/" + path.trimStart('/')
        }
    }
}

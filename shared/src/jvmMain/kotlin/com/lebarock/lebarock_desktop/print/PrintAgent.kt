package com.lebarock.lebarock_desktop.print

import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.data.SecretBox
import com.lebarock.lebarock_desktop.data.settings.AgentConfig
import com.lebarock.lebarock_desktop.data.settings.AgentPrinter
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.Base64

/** The caisse refused a call of the agent (401 = the key is no longer valid). */
class AgentHttpException(val code: Int, message: String) : IOException(message)

/** A ticket the caisse queued for this PC's printer. */
data class AgentJob(val id: Long, val bytes: ByteArray, val cut: Boolean)

/**
 * The caisse's print-jobs API seen from the counter PC (routes behind the branch key, header
 * X-AltosPos-KEY): pull this printer's oldest job, say how it went, list the branch's printers.
 */
class AgentClient(private val http: () -> OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The oldest pending job of [printerId], or null when there is none (204). */
    fun pull(baseUrl: String, key: String, printerId: Long): AgentJob? {
        val request = Request.Builder().url("${baseUrl}api/print-jobs/pull?printer_id=$printerId").header(KEY_HEADER, key).get().build()
        http().newCall(request).execute().use { response ->
            if (response.code == 204) return null
            if (!response.isSuccessful) throw AgentHttpException(response.code, "La caisse a répondu ${response.code}.")
            val body = response.body.string()
            if (body.isBlank()) return null
            return parseJob(json.parseToJsonElement(body).jsonObject)
        }
    }

    /** Live updates (2026-11-11): GET /api/realtime/agent — where to connect and the branch's printer channel; null = none. */
    fun realtime(baseUrl: String, key: String): Pair<String, String>? {
        val request = Request.Builder().url("${baseUrl}api/realtime/agent").header(KEY_HEADER, key).get().build()
        http().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val data = json.parseToJsonElement(response.body.string()).jsonObject["data"]?.jsonObject ?: return null
            if (data["enabled"]?.jsonPrimitive?.booleanOrNull != true) return null
            val path = data["path"]?.jsonPrimitive?.contentOrNull ?: return null
            val channel = data["channels"]?.jsonObject?.get("printer")?.jsonPrimitive?.contentOrNull ?: return null
            return path to channel
        }
    }

    /** Live updates: the caisse signs the printer channel for this connection (the branch key). */
    fun realtimeAuth(baseUrl: String, key: String, socketId: String, channel: String): String? {
        val payload = buildJsonObject {
            put("socket_id", socketId)
            put("channel_name", channel)
        }
        val request = Request.Builder().url("${baseUrl}api/realtime/auth").header(KEY_HEADER, key)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return json.parseToJsonElement(response.body.string()).jsonObject["auth"]?.jsonPrimitive?.contentOrNull
        }
    }

    /** PATCH /api/print-jobs/{id}: done or failed, with the printer that took it. */
    fun report(baseUrl: String, key: String, jobId: Long, printed: Boolean, printer: String, error: String?) {
        val payload = buildJsonObject {
            put("status", if (printed) "done" else "failed")
            put("printer", printer.take(180))
            put("printed_at", OffsetDateTime.now().toString())
            if (error != null) put("error", error.take(500))
        }
        val request = Request.Builder().url("${baseUrl}api/print-jobs/$jobId").header(KEY_HEADER, key)
            .patch(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AgentHttpException(response.code, "Compte rendu refusé (${response.code}).")
        }
    }

    /** The branch's printers (GET /api/printer-details), to change the agent's printer without an admin. */
    fun printers(baseUrl: String, key: String): List<AgentPrinter> {
        val request = Request.Builder().url("${baseUrl}api/printer-details").header(KEY_HEADER, key).get().build()
        http().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AgentHttpException(response.code, "La caisse a répondu ${response.code}.")
            return json.parseToJsonElement(response.body.string()).jsonArray.map { it.jsonObject }.mapNotNull { row ->
                val id = row["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                AgentPrinter(
                    id = id,
                    name = row["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    choice = row["printing_choice"]?.jsonPrimitive?.contentOrNull,
                    active = row["is_active"]?.jsonPrimitive?.let { it.booleanOrNull ?: (it.contentOrNull == "1") } ?: true,
                    caisses = row["order_details"]?.let { el ->
                        runCatching { el.jsonArray.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } }.getOrNull()
                    }.orEmpty(),
                )
            }
        }
    }

    companion object {
        const val KEY_HEADER = "X-AltosPos-KEY"

        /**
         * The job's bytes: `payload.base64` (the caisse's exact ESC/POS bytes, since step 4), else
         * `payload.text` from an older caisse (its non-UTF-8 bytes were re-read as Latin-1).
         */
        fun parseJob(obj: JsonObject): AgentJob {
            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: throw IOException("Ticket sans numéro.")
            val payload = obj["payload"]?.let { el -> runCatching { el.jsonObject }.getOrNull() }
            val base64 = payload?.get("base64")?.jsonPrimitive?.contentOrNull
            val bytes = when {
                !base64.isNullOrBlank() -> Base64.getDecoder().decode(base64)
                else -> {
                    val text = payload?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (text.all { it.code <= 0xFF }) text.toByteArray(Charsets.ISO_8859_1) else text.toByteArray(Charsets.UTF_8)
                }
            }
            val cut = payload?.get("cutPaper")?.jsonPrimitive?.booleanOrNull ?: true
            return AgentJob(id, bytes, cut)
        }
    }
}

enum class AgentState { OFF, NOT_SET, WAITING, PRINTING, CAISSE_DOWN, KEY_REFUSED, PRINTER_ERROR }

data class AgentStatus(
    val state: AgentState = AgentState.OFF,
    val message: String = "Arrêtée.",
    val printedCount: Int = 0,
    val lastPrintedAt: LocalDateTime? = null,
) {
    val healthy: Boolean get() = state == AgentState.WAITING || state == AgentState.PRINTING
}

/**
 * Impression « Directe » (step 4, D2): while switched on, every [pollMillis] it asks the caisse for
 * the next ticket queued for its printer (the web caisse and the handhelds print there when the
 * counter printer is on USB), prints it on this PC's printer and reports done / failed. It works
 * with the branch key, whoever is signed in, and keeps running with the window in the tray.
 */
class PrintAgent(
    private val settings: SettingsStore,
    private val secrets: SecretBox,
    private val printer: TicketPrinter,
    private val client: AgentClient,
    private val pollMillis: Long = 2_000,
    private val retryMillis: Long = 10_000,
    /** Live updates (2026-11-11): the websocket client; null = check every [pollMillis] only (tests, older setups). */
    private val liveHttp: (() -> OkHttpClient)? = null,
) {
    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status.asStateFlow()
    private var watcher: Job? = null

    /** Follows the settings: (re)starts on each change of the agent or the server address. */
    private var appScope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        if (watcher != null) return
        appScope = scope
        watcher = scope.launch(Dispatchers.IO) {
            settings.settings.map { it.agent to it.server.baseUrl }.distinctUntilChanged().collectLatest { (agent, baseUrl) ->
                run(agent, baseUrl)
            }
        }
    }

    private suspend fun run(agent: AgentConfig, baseUrl: String) {
        if (!agent.enabled) {
            _status.value = AgentStatus(AgentState.OFF, "Arrêtée.")
            return
        }
        val key = secrets.open(agent.keySealed)
        val printerId = agent.printerId
        when {
            key == null -> {
                _status.value = AgentStatus(AgentState.NOT_SET, "Clé absente ou illisible sur ce poste : configurez à nouveau.")
                return
            }
            printerId == null -> {
                _status.value = AgentStatus(AgentState.NOT_SET, "Choisissez l'imprimante de la caisse que ce poste imprime.")
                return
            }
            baseUrl.isBlank() -> {
                _status.value = AgentStatus(AgentState.NOT_SET, "Adresse du serveur non réglée.")
                return
            }
        }
        AppLog.info("Impression Directe : démarrée (imprimante #$printerId de la caisse)")
        var count = _status.value.printedCount
        var last = _status.value.lastPrintedAt
        fun set(state: AgentState, message: String) {
            _status.value = AgentStatus(state, message, count, last)
        }
        set(AgentState.WAITING, "En attente de tickets.")

        // Live updates: a new job wakes the agent at once; while live, it still checks every 30 s, otherwise as before.
        val live = openLive(baseUrl, key!!)
        val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val relay = live?.let { socket -> appScope?.launch { socket.events.collect { wake.trySend(Unit) } } }
        suspend fun idle() {
            val wait = if (live?.connected?.value == true) com.lebarock.lebarock_desktop.data.realtime.LiveUpdates.SLOW_CHECK_MILLIS else pollMillis
            kotlinx.coroutines.withTimeoutOrNull(wait) { wake.receive() }
        }
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val job = try {
                    withContext(Dispatchers.IO) { client.pull(baseUrl, key!!, printerId!!) }
                } catch (e: AgentHttpException) {
                    if (e.code == 401) {
                        AppLog.warn("Impression Directe : clé refusée par la caisse")
                        set(AgentState.KEY_REFUSED, "La caisse refuse la clé de ce poste : un administrateur doit configurer à nouveau.")
                        return
                    }
                    set(AgentState.CAISSE_DOWN, "La caisse a répondu ${e.code}. Nouvel essai dans ${retryMillis / 1000} s.")
                    delay(retryMillis)
                    continue
                } catch (e: Exception) {
                    set(AgentState.CAISSE_DOWN, "Caisse injoignable. Nouvel essai dans ${retryMillis / 1000} s.")
                    delay(retryMillis)
                    continue
                }

                if (job == null) {
                    if (_status.value.state != AgentState.PRINTER_ERROR) set(AgentState.WAITING, "En attente de tickets.")
                    idle()
                    continue
                }

                set(AgentState.PRINTING, "Impression du ticket #${job.id}…")
                val printed = printer.printRaw(job.bytes, job.cut)
                runCatching {
                    withContext(Dispatchers.IO) {
                        client.report(baseUrl, key!!, job.id, printed.isSuccess, printer.config.label, printed.exceptionOrNull()?.message)
                    }
                }.onFailure { AppLog.warn("Impression Directe : compte rendu du ticket #${job.id} non envoyé (${it.message})") }

                if (printed.isSuccess) {
                    count += 1
                    last = LocalDateTime.now()
                    set(AgentState.WAITING, "En attente de tickets.")
                } else {
                    set(AgentState.PRINTER_ERROR, "Ticket #${job.id} non imprimé : ${printed.exceptionOrNull()?.message}")
                    delay(retryMillis / 2)
                }
            }
        } finally {
            relay?.cancel()
            live?.disconnect()
        }
    }

    /** The printer channel of the branch, when the caisse has live updates; null otherwise (the agent then checks). */
    private suspend fun openLive(baseUrl: String, key: String): com.lebarock.lebarock_desktop.data.realtime.LiveSocket? {
        val http = liveHttp ?: return null
        val info = runCatching { withContext(Dispatchers.IO) { client.realtime(baseUrl, key) } }.getOrNull() ?: return null
        val socket = com.lebarock.lebarock_desktop.data.realtime.LiveSocket(
            http = http,
            name = "impression",
            authorize = { socketId, channel -> withContext(Dispatchers.IO) { client.realtimeAuth(baseUrl, key, socketId, channel) } },
        )
        socket.connect(com.lebarock.lebarock_desktop.data.realtime.LiveSocket.Target(
            com.lebarock.lebarock_desktop.data.realtime.LiveSocket.wsUrl(baseUrl, info.first), listOf(info.second),
        ))
        AppLog.info("Impression Directe : mises à jour en direct (${info.second})")
        return socket
    }
}

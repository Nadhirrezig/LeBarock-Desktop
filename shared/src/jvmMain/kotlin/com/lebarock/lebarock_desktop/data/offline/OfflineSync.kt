package com.lebarock.lebarock_desktop.data.offline

import com.lebarock.lebarock_desktop.AppInfo
import com.lebarock.lebarock_desktop.data.AppLog
import com.lebarock.lebarock_desktop.data.SecretBox
import com.lebarock.lebarock_desktop.data.remote.AltosApi
import com.lebarock.lebarock_desktop.data.remote.ApiClient
import com.lebarock.lebarock_desktop.data.remote.HeartbeatRequest
import com.lebarock.lebarock_desktop.data.remote.SyncBatchRequest
import com.lebarock.lebarock_desktop.data.remote.SyncCommandDto
import com.lebarock.lebarock_desktop.data.remote.SyncResultDto
import com.lebarock.lebarock_desktop.data.remote.WaitingCommandDto
import com.lebarock.lebarock_desktop.data.session.Session
import com.lebarock.lebarock_desktop.data.session.SessionHolder
import com.lebarock.lebarock_desktop.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** What the banner and Réglages show of the offline mode. */
data class OfflineStatus(
    /** Offline mode applies: a counter account is signed in, or this PC holds a device key from an earlier one. */
    val active: Boolean = false,
    val offline: Boolean = false,
    val syncing: Boolean = false,
    val pending: Int = 0,
    /** The caisse refused this PC's key: someone must sign in online once. */
    val keyRefused: Boolean = false,
)

/** The copy this PC holds, for Réglages. */
data class SnapshotSummary(
    val savedAt: Long? = null,
    val checksSavedAt: Long? = null,
    val caisseName: String? = null,
    val items: Int = 0,
    val tables: Int = 0,
    val openChecks: Int = 0,
)

/**
 * Offline mode, desktop step 1 — the handheld's OfflineSync on this PC: keeps it known to the caisse (heartbeat,
 * device code « D1 », sync key), keeps its copy fresh while an account is signed in (snapshot: menu, tables, open
 * checks), notices when the caisse stops answering, and sends the outbox in order when it answers again. Results the
 * caisse refused or set aside go to « À vérifier » with its French message. Step 2 fills the outbox (orders taken
 * offline), step 3 adds the « Hors ligne » sign-in and the payments.
 *
 * The desktop's session lives in memory and closes when the counter is idle: with nobody signed in the PC still
 * says it is there and still sends what waits (the device key alone), but the caisse gives the copy to a signed-in
 * account only, so the copy is as fresh as the last moment someone was signed in (client, 2026-11-10: as the handheld).
 */
class OfflineSync(
    private val store: OfflineStore,
    private val client: ApiClient,
    private val settings: SettingsStore,
    private val sessions: SessionHolder,
    private val secrets: SecretBox,
    val monitor: ConnectionMonitor,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val HEARTBEAT_MS = 30_000L
        const val CHECKS_MS = 30_000L
        const val PROBE_MS = 3_000L
        const val BATCH_SIZE = 50

        /** What this app is to the caisse's sync API: it names the PC « D1 », « D2 »… and lists it as « Caisse PC ». */
        const val KIND = "desktop"

        /** The POS context the caisse last gave (order types): kept with the copy for the offline cart (step 2). */
        const val POS_CONTEXT_PART = "pos_context"
    }

    private val json = client.json
    private val syncing = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pushLock = Mutex()
    private var started = false

    /** A counter account (not a kitchen-only one, which has nothing to do on this PC). */
    private fun counter(session: Session?): Boolean = session != null && !session.rights.kitchenOnly

    /** Offline mode applies: a counter account signed in; with nobody signed in, a device key still syncs the outbox. */
    private val activeFlow = combine(sessions.session, store.deviceFlow()) { session, device ->
        counter(session) || (session == null && device?.syncKeySealed != null)
    }

    val status: StateFlow<OfflineStatus> = combine(
        activeFlow, monitor.offline, syncing, store.outboxCount(), store.deviceFlow(),
    ) { active, offline, isSyncing, pending, device ->
        OfflineStatus(active, offline, isSyncing, pending, device?.keyRefused == true)
    }.stateIn(scope, SharingStarted.Eagerly, OfflineStatus())

    val deviceCode: StateFlow<String?> = store.deviceFlow().map { it?.code }.stateIn(scope, SharingStarted.Eagerly, null)

    val lastSyncAt: StateFlow<Long?> = store.deviceFlow().map { it?.lastSyncAt }.stateIn(scope, SharingStarted.Eagerly, null)

    /** When the caisse last answered this PC (its heartbeat, or a sending of what waited), for Réglages. */
    val lastContactAt: StateFlow<Long?> = store.deviceFlow().map { d -> listOfNotNull(d?.lastHeartbeatAt, d?.lastSyncAt).maxOrNull() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val reviewCount: StateFlow<Int> = store.reviewCount().stateIn(scope, SharingStarted.Eagerly, 0)

    val reviewItems = store.reviewFlow()

    val snapshot: StateFlow<SnapshotSummary> = combine(store.snapshotFlow(), store.openCheckCount()) { parts, checks ->
        summarize(parts, checks)
    }.stateIn(scope, SharingStarted.Eagerly, SnapshotSummary())

    /** False when `offline.db` could not be opened: the PC works with a store that is lost when the app closes. */
    val storeOnDisk: Boolean get() = store.persistent

    /** Starts the loops once, for the life of the app (they go on with the window in the tray). */
    fun start() {
        if (started) return
        started = true

        // A counter account signs in: the PC registers, gets its key, takes a full copy.
        scope.launch {
            sessions.session
                .map { if (counter(it)) it?.user?.id ?: 0L else 0L }
                .distinctUntilChanged()
                .collect { userId -> if (userId > 0) refreshAll() }
        }

        // Another caisse address saved in Réglages: what was known of the old one says nothing about the new one.
        scope.launch {
            settings.settings.map { it.server.baseUrl }.distinctUntilChanged().drop(1).collect {
                monitor.reset()
                wake.trySend(Unit)
            }
        }

        // Back online: send what waits and catch up. Each change goes to the journal (support reads it).
        scope.launch {
            monitor.offline.drop(1).collect { offline ->
                AppLog.info(if (offline) "Caisse injoignable : mode hors ligne" else "Caisse de nouveau joignable")
                if (!offline) wake.trySend(Unit)
            }
        }

        // Heartbeat: every 30 s.
        scope.launch {
            while (true) {
                runCatching { heartbeat() }
                delay(HEARTBEAT_MS)
            }
        }

        // Open checks: every 30 s while signed in and online.
        scope.launch {
            while (true) {
                delay(CHECKS_MS)
                if (!monitor.offline.value) runCatching { refreshSnapshot("checks") }
            }
        }

        // Calls failing: ask the caisse every 3 s until it answers (that brings the PC back online).
        scope.launch {
            while (true) {
                delay(PROBE_MS)
                if (monitor.doubtful.value || monitor.offline.value) runCatching { api()?.syncPing() }
            }
        }

        // The outbox: on demand, back online, and every 30 s.
        scope.launch {
            while (true) {
                withTimeoutOrNull(HEARTBEAT_MS) { wake.receive() }
                if (!monitor.offline.value) runCatching { push() }.onFailure { AppLog.warn("Envoi des actions en attente", it) }
            }
        }
    }

    /** « Synchroniser maintenant » in Réglages, and right after a sign-in. */
    suspend fun refreshAll() {
        runCatching { heartbeat() }
        runCatching { refreshSnapshot("static,checks") }
        runCatching { push() }
    }

    /** Ask the outbox to be sent now (step 2 calls it after queuing an action). */
    fun requestSync() {
        wake.trySend(Unit)
    }

    /** Outbox first: once something waits, new actions queue behind it so the caisse gets them in order. */
    fun shouldQueue(): Boolean = status.value.offline || status.value.pending > 0

    /**
     * The same, read from the store itself (the status flow follows a write a moment later): an action queued a
     * moment ago keeps the next one behind it, even if the caisse answered in between.
     */
    suspend fun mustQueue(): Boolean = monitor.offline.value || store.outboxSize() > 0

    /** The POS context the caisse just gave (online): its order types are what the offline cart offers. */
    suspend fun rememberPosContext(context: JsonObject) {
        val part = store.snapshotPart("static") ?: return
        store.saveSnapshotPart(SnapshotPartEntity(POS_CONTEXT_PART, null, part.caisseId, part.userId, context.toString(), now()))
    }

    private fun api(): AltosApi? = settings.current.server.takeIf { it.isConfigured }?.let { client.apiFor(it.baseUrl) }

    // ---------------------------------------------------------------- outbox

    /**
     * Queues an action for the caisse (steps 2 and 3): a new sequence number, the time on the caisse's clock, the
     * actor who did it. Never fails for the network: it is sent when the caisse answers.
     */
    suspend fun enqueue(
        type: String,
        data: JsonObject,
        actorId: Long,
        label: String,
        orderRef: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): OutboxEntity {
        val command = store.enqueue(::newDevice) { device ->
            OutboxEntity(
                id = id,
                seq = device.nextSeq,
                type = type,
                actorId = actorId,
                at = caisseNow(device).toString(),
                dataJson = data.toString(),
                label = label,
                createdAt = now(),
                orderRef = orderRef,
            )
        }
        requestSync()
        return command
    }

    /** The next temporary order number of this PC, e.g. « D1-007 » (step 2). Null before the first heartbeat. */
    suspend fun nextOfflineNumber(): String? {
        var number: String? = null
        store.updateDevice(::newDevice) { device ->
            val code = device.code ?: return@updateDevice device
            number = "%s-%03d".format(code, device.nextOfflineNumber)
            device.copy(nextOfflineNumber = device.nextOfflineNumber + 1)
        }
        return number
    }

    /** Sends the outbox in seq order, in batches; stops at the first action the caisse asks to send again. */
    suspend fun push(): Unit = pushLock.withLock {
        if (!active()) return@withLock
        val api = api() ?: return@withLock
        val device = ensureDevice()
        val key = secrets.open(device.syncKeySealed) ?: return@withLock
        settledSome = false
        try {
            sendOutbox(api, device, key)
        } finally {
            syncing.value = false
        }
        if (settledSome) {
            runCatching { refreshSnapshot("checks") }
            store.pruneLocalOrders(now() - 24 * 3600_000L)
        }
    }

    private suspend fun sendOutbox(api: AltosApi, device: DeviceStateEntity, key: String) {
        while (true) {
            val batch = store.nextOutbox(BATCH_SIZE)
            if (batch.isEmpty()) break
            syncing.value = true

            val response = try {
                api.syncBatch(
                    SyncBatchRequest(
                        deviceUuid = device.uuid,
                        commands = batch.map { SyncCommandDto(it.id, it.seq, it.type, it.at, it.actorId, json.parseToJsonElement(it.dataJson)) },
                    ),
                    deviceUuid = device.uuid,
                    syncKey = key,
                )
            } catch (e: HttpException) {
                if (e.code() == 401) updateDevice { it.copy(keyRefused = true) }
                store.markAttempt(batch.map { it.id }, "HTTP ${e.code()}")
                break
            } catch (e: IOException) {
                store.markAttempt(batch.map { it.id }, e.message)
                break
            }

            val data = response.data ?: break
            val byId = batch.associateBy { it.id }
            var stop = data.stopped

            for (result in data.results) {
                val command = byId[result.id] ?: continue
                if (result.status == "retry") {
                    stop = true
                    break
                }
                settle(command, result)
            }

            updateDevice { it.copy(lastSyncAt = now(), keyRefused = false) }
            if (stop || data.results.isEmpty()) break
        }
    }

    private suspend fun settle(command: OutboxEntity, result: SyncResultDto) {
        val mapping = result.mapping as? JsonObject
        val ref = mapping?.let { orderRefOf(command, it) }

        val review = when (result.status) {
            "review", "refused", "waiting_session" -> ReviewItemEntity(
                commandId = command.id,
                seq = command.seq,
                type = command.type,
                status = result.status,
                code = result.code,
                message = result.message ?: "Action refusée par la caisse.",
                label = command.label,
                at = command.at,
                dataJson = command.dataJson,
                mappingJson = mapping?.toString(),
                updatedAt = now(),
            )
            else -> null // applied, merged: done.
        }

        store.settle(command.id, review, ref)
        settledSome = true
    }

    /** Something reached the caisse during the last push: the copy of the open checks is read again. */
    @Volatile
    private var settledSome = false

    /** Where the action's order went at the caisse (its real number), for step 2's screens. */
    private fun orderRefOf(command: OutboxEntity, mapping: JsonObject): OrderRefEntity? {
        val ref = mapping["order_ref"]?.jsonPrimitive?.contentOrNull ?: return null
        val data = runCatching { json.parseToJsonElement(command.dataJson).jsonObject }.getOrNull()
        return OrderRefEntity(
            ref = ref,
            offlineNumber = data?.get("offline_number")?.jsonPrimitive?.contentOrNull,
            orderId = mapping["order_id"]?.jsonPrimitive?.longOrNull,
            orderNumber = mapping["order_number"]?.jsonPrimitive?.contentOrNull,
            updatedAt = now(),
        )
    }

    // ---------------------------------------------------------------- heartbeat

    suspend fun heartbeat() {
        val api = api() ?: return
        val session = sessions.current
        if (session != null && !counter(session)) return

        val device = ensureDevice()
        val key = secrets.open(device.syncKeySealed)
        if (session == null && key == null) return

        val request = HeartbeatRequest(
            deviceUuid = device.uuid,
            kind = KIND,
            model = pcName(),
            appVersion = AppInfo.version,
            pendingCount = store.outboxSize(),
            oldestPendingAt = store.oldestOutbox()?.let { Instant.ofEpochMilli(it).toString() },
            snapshotVersion = store.snapshotPart("static")?.version,
        )

        val sentAt = now()
        val data = try {
            api.heartbeat(request, key).data ?: return
        } catch (e: HttpException) {
            if (e.code() == 401 && session == null) updateDevice { it.copy(keyRefused = true) }
            return
        }

        val offset = data.serverTime?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() - (sentAt + now()) / 2 }.getOrNull() }
        val before = device.code
        val after = updateDevice { current ->
            current.copy(
                code = data.device.code.takeIf { it.isNotBlank() } ?: current.code,
                syncKeySealed = data.syncKey?.let { secrets.seal(it) } ?: current.syncKeySealed,
                keyRefused = false,
                clockOffsetMs = offset ?: current.clockOffsetMs,
                lastHeartbeatAt = now(),
            )
        }
        if (after.code != before) AppLog.info("Mode hors ligne : ce poste est « Caisse PC ${after.code} » sur la caisse")

        if (data.syncKey != null) requestSync()
        if (data.snapshotStale && session != null) runCatching { refreshSnapshot("static") }
        applyWaiting(data.waiting)
    }

    /** Payments the caisse held for a cash session: their state now (recorded, refused, still waiting). */
    private suspend fun applyWaiting(waiting: List<WaitingCommandDto>) {
        for (item in waiting) {
            val existing = store.review(item.commandId)
            val message = item.message ?: when (item.status) {
                "applied" -> "Enregistré à l'ouverture de la session de caisse."
                else -> "En attente d'une session de caisse."
            }
            if (existing != null) {
                if (existing.status != item.status || existing.message != message) {
                    store.saveReview(existing.copy(status = item.status, code = item.code, message = message, updatedAt = now()))
                }
            } else if (item.status == "refused") {
                // Removed from the list while it waited, then refused: back on the list, nothing is lost.
                store.saveReview(
                    ReviewItemEntity(
                        commandId = item.commandId, seq = 0, type = "payment", status = item.status, code = item.code,
                        message = message, label = "Paiement hors ligne", at = Instant.ofEpochMilli(now()).toString(), dataJson = "{}",
                        mappingJson = (item.mapping as? JsonObject)?.toString(), updatedAt = now(),
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- snapshot

    /** parts: « static », « checks » or both. Needs a counter account signed in and the device key. */
    suspend fun refreshSnapshot(parts: String) {
        val api = api() ?: return
        val session = sessions.current
        if (!counter(session)) return
        val userId = session?.user?.id ?: return

        var device = ensureDevice()
        if (device.syncKeySealed == null) {
            heartbeat()
            device = ensureDevice()
        }
        val key = secrets.open(device.syncKeySealed) ?: return
        val stored = store.snapshotPart("static")
        val asksStatic = "static" in parts
        // The copy names a « Hors ligne » account but this PC holds no PIN check: take the whole copy again.
        val pinsMissing = asksStatic && stored != null && namesOfflineAccount(stored) &&
            store.offlineAccess()?.accountVerifierSealed == null

        val response = api.snapshot(
            parts = parts,
            deviceUuid = device.uuid,
            syncKey = key,
            version = if (asksStatic && !pinsMissing) stored?.version?.let { "\"$it\"" } else null,
        )

        if (response.code() == 304) return
        if (!response.isSuccessful) {
            // Unknown device (the caisse lost it, or its key was replaced): register again on the next heartbeat.
            if (response.code() == 403) runCatching { heartbeat() }
            return
        }

        val data = response.body()?.data ?: return
        val savedAt = now()

        data.static?.let { static ->
            // The PIN checks are kept apart, sealed; the stored copy goes without them.
            keepOfflineAccess(static)
            store.saveSnapshotPart(SnapshotPartEntity("static", data.version, data.caisseId, userId, withoutVerifiers(static).toString(), savedAt))
            AppLog.info("Mode hors ligne : copie de la caisse mise à jour (version ${data.version ?: "?"})")
        }

        data.checks?.let { checks -> saveChecks(checks, data.caisseId, userId, savedAt) }
    }

    private suspend fun saveChecks(checks: JsonObject, caisseId: Long, userId: Long, savedAt: Long) {
        val orders = (checks["open_checks"] as? JsonArray).orEmpty().mapNotNull { element ->
            val order = element as? JsonObject ?: return@mapNotNull null
            val id = order["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            OpenCheckEntity(
                id = id,
                number = order["order_number"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                offlineNumber = (order["offline_number"] as? JsonPrimitive)?.contentOrNull,
                tableId = (order["table"] as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull,
                status = order["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                total = order["total"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                json = order.toString(),
                savedAt = savedAt,
            )
        }
        val tables = (checks["tables"] as? JsonArray).orEmpty().mapNotNull { element ->
            val table = element as? JsonObject ?: return@mapNotNull null
            val id = table["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            TableStateEntity(id, table["status"]?.jsonPrimitive?.contentOrNull.orEmpty(), table.toString())
        }

        store.replaceChecks(SnapshotPartEntity("checks", null, caisseId, userId, checks.toString(), savedAt), orders, tables)
    }

    /**
     * « Effacer les données hors ligne »: the copy and the offline PIN checks go (taken again at the next sign-in);
     * refused while actions still wait to be sent.
     */
    suspend fun clearCopy(): Result<Unit> {
        val pending = store.outboxSize()
        if (pending > 0) {
            return Result.failure(IllegalStateException("$pending action(s) pas encore envoyée(s) à la caisse : synchronisez d'abord."))
        }
        store.clearCopy()
        AppLog.info("Mode hors ligne : données hors ligne effacées sur ce poste")
        return Result.success(Unit)
    }

    /**
     * The Caisse's « Hors ligne » account and the PIN checks from a new copy, sealed for this Windows account. No
     * offline PIN at the caisse any more: they are erased.
     */
    private suspend fun keepOfflineAccess(static: JsonObject) {
        val offline = static["offline"] as? JsonObject
        val account = offline?.get("account") as? JsonObject
        val caisse = static["caisse"] as? JsonObject
        val accountId = account?.get("id")?.jsonPrimitive?.longOrNull
        val verifier = (account?.get("verifier") as? JsonPrimitive)?.contentOrNull

        if (accountId == null || verifier.isNullOrBlank()) {
            store.saveOfflineAccess(null)
            return
        }

        store.saveOfflineAccess(
            OfflineAccess(
                accountId = accountId,
                accountName = (account["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                accountVerifierSealed = secrets.seal(verifier),
                paymentVerifierSealed = (offline["payment_pin"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { secrets.seal(it) },
                caisseId = caisse?.get("id")?.jsonPrimitive?.longOrNull ?: 0,
                caisseName = (caisse?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                caisseMenuId = (caisse?.get("menu_id") as? JsonPrimitive)?.longOrNull ?: 0,
            ),
        )
    }

    private fun namesOfflineAccount(stored: SnapshotPartEntity): Boolean {
        val static = runCatching { json.parseToJsonElement(stored.json).jsonObject }.getOrNull() ?: return false
        return ((static["offline"] as? JsonObject)?.get("account") as? JsonObject)?.get("id") != null
    }

    /** The copy as stored: the account's id and name stay (orders are recorded as it), the PIN checks do not. */
    private fun withoutVerifiers(static: JsonObject): JsonObject {
        val offline = static["offline"] as? JsonObject ?: return static
        val account = (offline["account"] as? JsonObject)?.let { JsonObject(it - "verifier") }
        val cleaned = JsonObject(offline - "payment_pin" - "account" + listOfNotNull(account?.let { "account" to it }).toMap())
        return JsonObject(static + ("offline" to cleaned))
    }

    suspend fun dismissReview(commandId: String) = store.deleteReview(commandId)

    // ---------------------------------------------------------------- helpers

    private suspend fun active(): Boolean {
        val session = sessions.current
        if (session != null) return counter(session)
        return store.device()?.syncKeySealed != null
    }

    private fun newDevice() = DeviceStateEntity(uuid = UUID.randomUUID().toString())

    /** This PC's row, made once with a uuid that never changes. */
    private suspend fun ensureDevice(): DeviceStateEntity = store.device() ?: store.updateDevice(::newDevice) { it }

    /** Changes to the device row go one at a time, so the sequence numbers are never overwritten. */
    private suspend fun updateDevice(change: (DeviceStateEntity) -> DeviceStateEntity): DeviceStateEntity = store.updateDevice(::newDevice, change)

    /** Now on the caisse's clock (this PC's own clock may be off). */
    private fun caisseNow(device: DeviceStateEntity): Instant = Instant.ofEpochMilli(now() + device.clockOffsetMs)

    /** What the caisse lists next to « Caisse PC D1 »: the Windows name of this PC. */
    private fun pcName(): String {
        val name = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() } ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        return listOfNotNull(name, System.getProperty("os.name")).joinToString(" · ").take(120)
    }

    private fun summarize(parts: List<SnapshotPartEntity>, openChecks: Int): SnapshotSummary {
        val static = parts.firstOrNull { it.part == "static" }
        val checks = parts.firstOrNull { it.part == "checks" }
        val root = static?.let { runCatching { json.parseToJsonElement(it.json).jsonObject }.getOrNull() }

        val items = ((root?.get("menu") as? JsonObject)?.get("items") as? JsonArray)?.size ?: 0
        val tables = (root?.get("areas") as? JsonArray).orEmpty().sumOf { area -> ((area as? JsonObject)?.get("tables") as? JsonArray)?.size ?: 0 }
        val caisseName = ((root?.get("caisse") as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull

        return SnapshotSummary(static?.savedAt, checks?.savedAt, caisseName, items, tables, openChecks)
    }
}

package com.lebarock.lebarock_desktop.data.offline

import com.lebarock.lebarock_desktop.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/*
 * Offline mode (desktop step 1): what this PC keeps to work while the caisse cannot be reached, the handheld's
 * `OfflineDatabase` (Room) as one SQLite file, `offline.db` in the data folder. The caisse stays the only source of
 * truth: these tables are a copy (snapshot, open checks) plus what is waiting to be sent (outbox) and what the caisse
 * refused or set aside (review = « À vérifier »). The rows and the calls keep the handheld's names, so its offline
 * code copies over as it is.
 */

/** This PC as the caisse knows it: one row. The sync key is stored sealed for this Windows account (SecretBox). */
data class DeviceStateEntity(
    val uuid: String,
    /** « D1 », « D2 »… given by the caisse at the first heartbeat. */
    val code: String? = null,
    val syncKeySealed: String? = null,
    /** The caisse refused the key (lost, replaced): someone must sign in online once. */
    val keyRefused: Boolean = false,
    /** Next command sequence number (commands are applied in this order). */
    val nextSeq: Long = 1,
    /** Next temporary order number: « D1-007 ». */
    val nextOfflineNumber: Int = 1,
    /** Caisse clock − this PC's clock, from the last heartbeat (offline times are sent on the caisse's clock). */
    val clockOffsetMs: Long = 0,
    val lastHeartbeatAt: Long? = null,
    val lastSyncAt: Long? = null,
)

/** One part of the snapshot (« static »: menu, tables, receipt; « checks »: open checks) as the caisse sent it. */
data class SnapshotPartEntity(
    val part: String,
    val version: String?,
    val caisseId: Long,
    val userId: Long,
    val json: String,
    val savedAt: Long,
)

/** An open check of the Caisse, from the last « checks » refresh. */
data class OpenCheckEntity(
    val id: Long,
    val number: String,
    val offlineNumber: String?,
    val tableId: Long?,
    val status: String,
    val total: Double,
    val json: String,
    val savedAt: Long,
)

/** A table's state, from the last « checks » refresh. */
data class TableStateEntity(val id: Long, val status: String, val json: String)

/**
 * An action taken while offline (or while earlier ones still wait), sent to POST /api/sync/batch in [seq] order.
 * Removed once the caisse answered it.
 */
data class OutboxEntity(
    val id: String,
    val seq: Long,
    val type: String,
    val actorId: Long,
    /** When it was done, on the caisse's clock (ISO 8601). */
    val at: String,
    val dataJson: String,
    /** What staff read in the lists, e.g. « Commande D1-007 · T5 ». */
    val label: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    /** The order the action is about (its order_ref), so the order's screens know what is still on its way. */
    val orderRef: String? = null,
)

/**
 * « À vérifier »: an action the caisse refused or set aside, with its French message, or a payment waiting for a
 * cash session. Staff remove it once seen.
 */
data class ReviewItemEntity(
    val commandId: String,
    val seq: Long,
    val type: String,
    /** refused | review | waiting_session | applied (a held payment recorded since). */
    val status: String,
    val code: String?,
    val message: String,
    val label: String,
    val at: String,
    val dataJson: String,
    val mappingJson: String?,
    val updatedAt: Long,
)

/** Where a temporary order reference ended up at the caisse: its real check and number. */
data class OrderRefEntity(
    val ref: String,
    val offlineNumber: String?,
    val orderId: Long?,
    val orderNumber: String?,
    val updatedAt: Long,
)

/**
 * The Caisse's « Hors ligne » account and the offline PIN checks of the last copy, sealed for this Windows account.
 * The copy itself is stored without them. The sign-in with the PIN comes with step 3.
 */
data class OfflineAccess(
    val accountId: Long,
    val accountName: String,
    val accountVerifierSealed: String?,
    val paymentVerifierSealed: String?,
    val caisseId: Long,
    val caisseName: String,
    val caisseMenuId: Long,
)

/**
 * The store. One connection, one writer at a time; every change is written before the call returns (a power cut at
 * the counter loses nothing that was confirmed). [changes] moves on each write: the flows read again after it.
 */
class OfflineStore private constructor(private val connection: Connection, val persistent: Boolean) : AutoCloseable {

    companion object {
        /** The schema's version (`PRAGMA user_version`); raise it with each change and add its step to [migrate]. */
        const val SCHEMA_VERSION = 1

        /**
         * Opens `offline.db` (made on the first start). A file that cannot be read is kept aside
         * (`offline.db.bad`) and a new one is made; if even that fails the app works with a store in memory, so
         * a damaged disk never keeps the counter from opening.
         */
        fun open(file: File): OfflineStore {
            runCatching { return connect(file) }.onFailure { AppLog.error("Base hors ligne illisible : ${file.path}", it) }
            runCatching {
                val bad = File(file.path + ".bad")
                bad.delete()
                file.renameTo(bad)
                listOf("-wal", "-shm").forEach { File(file.path + it).delete() }
                return connect(file)
            }.onFailure { AppLog.error("Base hors ligne impossible à recréer : ${file.path}", it) }
            return inMemory()
        }

        /** A store that lives with the process: tests, and the last resort of [open]. */
        fun inMemory(): OfflineStore = OfflineStore(DriverManager.getConnection("jdbc:sqlite::memory:"), persistent = false).also { it.migrate() }

        private fun connect(file: File): OfflineStore {
            file.parentFile?.mkdirs()
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
            return try {
                connection.createStatement().use { st ->
                    // Each commit reaches the disk before the action is confirmed to the cashier.
                    st.execute("PRAGMA journal_mode=WAL")
                    st.execute("PRAGMA synchronous=FULL")
                    st.execute("PRAGMA busy_timeout=5000")
                }
                OfflineStore(connection, persistent = true).also { it.migrate() }
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        }
    }

    private val lock = Mutex()
    private val changes = MutableStateFlow(0L)

    private fun migrate() {
        val version = connection.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        if (version >= SCHEMA_VERSION) return
        connection.autoCommit = false
        try {
            connection.createStatement().use { st ->
                if (version < 1) SCHEMA_V1.forEach(st::execute)
                st.execute("PRAGMA user_version=$SCHEMA_VERSION")
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun <T> read(block: Connection.() -> T): T = withContext(Dispatchers.IO) { lock.withLock { connection.block() } }

    /** One transaction: everything in [block] is kept, or nothing. */
    private suspend fun <T> write(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.autoCommit = false
            try {
                val result = connection.block()
                connection.commit()
                result
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }.also { changes.value++ }
    }

    private fun <T> watch(query: suspend () -> T): Flow<T> = changes.map { query() }.distinctUntilChanged()

    private fun Connection.run(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { it.bind(args); it.executeUpdate() }

    private fun <T> Connection.list(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = prepareStatement(sql).use { st ->
        st.bind(args)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
    }

    private fun <T> Connection.one(sql: String, vararg args: Any?, row: (ResultSet) -> T): T? = list(sql, *args, row = row).firstOrNull()

    private fun PreparedStatement.bind(args: Array<out Any?>) {
        args.forEachIndexed { i, value ->
            when (value) {
                null -> setNull(i + 1, Types.NULL)
                is String -> setString(i + 1, value)
                is Long -> setLong(i + 1, value)
                is Int -> setInt(i + 1, value)
                is Double -> setDouble(i + 1, value)
                is Boolean -> setInt(i + 1, if (value) 1 else 0)
                else -> error("Type non géré : ${value::class}")
            }
        }
    }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeIf { !wasNull() }

    override fun close() {
        runCatching { connection.close() }
    }

    // ---------------------------------------------------------------- device

    suspend fun device(): DeviceStateEntity? = read { readDevice() }

    fun deviceFlow(): Flow<DeviceStateEntity?> = watch { device() }

    suspend fun saveDevice(device: DeviceStateEntity) = write { writeDevice(device) }

    /**
     * Reads the device row, changes it and writes it back as one step, so two changes never overwrite each other
     * (the sequence numbers above all). [create] makes the row the first time.
     */
    suspend fun updateDevice(create: () -> DeviceStateEntity, change: (DeviceStateEntity) -> DeviceStateEntity): DeviceStateEntity = write {
        change(readDevice() ?: create()).also { writeDevice(it) }
    }

    private fun Connection.readDevice(): DeviceStateEntity? = one("SELECT * FROM device_state WHERE id = 1") { rs ->
        DeviceStateEntity(
            uuid = rs.getString("uuid"),
            code = rs.getString("code"),
            syncKeySealed = rs.getString("sync_key_sealed"),
            keyRefused = rs.getInt("key_refused") != 0,
            nextSeq = rs.getLong("next_seq"),
            nextOfflineNumber = rs.getInt("next_offline_number"),
            clockOffsetMs = rs.getLong("clock_offset_ms"),
            lastHeartbeatAt = rs.longOrNull("last_heartbeat_at"),
            lastSyncAt = rs.longOrNull("last_sync_at"),
        )
    }

    private fun Connection.writeDevice(d: DeviceStateEntity) {
        run(
            "INSERT OR REPLACE INTO device_state (id, uuid, code, sync_key_sealed, key_refused, next_seq, next_offline_number, " +
                "clock_offset_ms, last_heartbeat_at, last_sync_at) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            d.uuid, d.code, d.syncKeySealed, d.keyRefused, d.nextSeq, d.nextOfflineNumber, d.clockOffsetMs, d.lastHeartbeatAt, d.lastSyncAt,
        )
    }

    // ---------------------------------------------------------------- snapshot

    suspend fun snapshotPart(part: String): SnapshotPartEntity? = read { one("SELECT * FROM snapshot_parts WHERE part = ?", part, row = ::snapshotRow) }

    fun snapshotFlow(): Flow<List<SnapshotPartEntity>> = watch { read { list("SELECT * FROM snapshot_parts ORDER BY part", row = ::snapshotRow) } }

    suspend fun saveSnapshotPart(part: SnapshotPartEntity) = write { writeSnapshotPart(part) }

    private fun snapshotRow(rs: ResultSet) = SnapshotPartEntity(
        rs.getString("part"), rs.getString("version"), rs.getLong("caisse_id"), rs.getLong("user_id"), rs.getString("json"), rs.getLong("saved_at"),
    )

    private fun Connection.writeSnapshotPart(p: SnapshotPartEntity) {
        run(
            "INSERT OR REPLACE INTO snapshot_parts (part, version, caisse_id, user_id, json, saved_at) VALUES (?, ?, ?, ?, ?, ?)",
            p.part, p.version, p.caisseId, p.userId, p.json, p.savedAt,
        )
    }

    fun openCheckCount(): Flow<Int> = watch { read { one("SELECT COUNT(*) FROM open_checks") { it.getInt(1) } ?: 0 } }

    suspend fun openChecks(): List<OpenCheckEntity> = read {
        list("SELECT * FROM open_checks ORDER BY id") { rs ->
            OpenCheckEntity(
                rs.getLong("id"), rs.getString("number"), rs.getString("offline_number"), rs.longOrNull("table_id"),
                rs.getString("status"), rs.getDouble("total"), rs.getString("json"), rs.getLong("saved_at"),
            )
        }
    }

    suspend fun tableStates(): List<TableStateEntity> = read {
        list("SELECT * FROM table_states ORDER BY id") { rs -> TableStateEntity(rs.getLong("id"), rs.getString("status"), rs.getString("json")) }
    }

    /** The checks part replaces the last one whole. */
    suspend fun replaceChecks(part: SnapshotPartEntity, checks: List<OpenCheckEntity>, tables: List<TableStateEntity>) = write {
        writeSnapshotPart(part)
        run("DELETE FROM open_checks")
        checks.forEach { c ->
            run(
                "INSERT OR REPLACE INTO open_checks (id, number, offline_number, table_id, status, total, json, saved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                c.id, c.number, c.offlineNumber, c.tableId, c.status, c.total, c.json, c.savedAt,
            )
        }
        run("DELETE FROM table_states")
        tables.forEach { t -> run("INSERT OR REPLACE INTO table_states (id, status, json) VALUES (?, ?, ?)", t.id, t.status, t.json) }
    }

    /** « Effacer les données hors ligne »: the copy and the PIN checks go, the outbox and the review list stay. */
    suspend fun clearCopy() = write {
        run("DELETE FROM snapshot_parts")
        run("DELETE FROM open_checks")
        run("DELETE FROM table_states")
        run("DELETE FROM offline_access")
    }

    // ---------------------------------------------------------------- offline access

    suspend fun offlineAccess(): OfflineAccess? = read {
        one("SELECT * FROM offline_access WHERE id = 1") { rs ->
            OfflineAccess(
                rs.getLong("account_id"), rs.getString("account_name"), rs.getString("account_verifier_sealed"),
                rs.getString("payment_verifier_sealed"), rs.getLong("caisse_id"), rs.getString("caisse_name"), rs.getLong("caisse_menu_id"),
            )
        }
    }

    /** null = no « Hors ligne » account (or no PIN) at the caisse any more: nothing is kept. */
    suspend fun saveOfflineAccess(access: OfflineAccess?) = write {
        if (access == null) {
            run("DELETE FROM offline_access")
        } else {
            run(
                "INSERT OR REPLACE INTO offline_access (id, account_id, account_name, account_verifier_sealed, payment_verifier_sealed, " +
                    "caisse_id, caisse_name, caisse_menu_id) VALUES (1, ?, ?, ?, ?, ?, ?, ?)",
                access.accountId, access.accountName, access.accountVerifierSealed, access.paymentVerifierSealed,
                access.caisseId, access.caisseName, access.caisseMenuId,
            )
        }
    }

    // ---------------------------------------------------------------- outbox

    suspend fun insertOutbox(command: OutboxEntity) = write { writeOutbox(command) }

    /**
     * Queues an action with the device's next sequence number, as one step: [build] gets the device row and gives
     * the action; the row's counter moves on with it.
     */
    suspend fun enqueue(create: () -> DeviceStateEntity, build: (DeviceStateEntity) -> OutboxEntity): OutboxEntity = write {
        val device = readDevice() ?: create()
        val command = build(device)
        writeDevice(device.copy(nextSeq = device.nextSeq + 1))
        writeOutbox(command)
        command
    }

    private fun Connection.writeOutbox(c: OutboxEntity) {
        run(
            "INSERT INTO outbox (id, seq, type, actor_id, at, data_json, label, created_at, attempts, last_error, order_ref) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            c.id, c.seq, c.type, c.actorId, c.at, c.dataJson, c.label, c.createdAt, c.attempts, c.lastError, c.orderRef,
        )
    }

    private fun outboxRow(rs: ResultSet) = OutboxEntity(
        rs.getString("id"), rs.getLong("seq"), rs.getString("type"), rs.getLong("actor_id"), rs.getString("at"), rs.getString("data_json"),
        rs.getString("label"), rs.getLong("created_at"), rs.getInt("attempts"), rs.getString("last_error"), rs.getString("order_ref"),
    )

    suspend fun nextOutbox(limit: Int): List<OutboxEntity> = read { list("SELECT * FROM outbox ORDER BY seq LIMIT ?", limit, row = ::outboxRow) }

    suspend fun outboxSize(): Int = read { one("SELECT COUNT(*) FROM outbox") { it.getInt(1) } ?: 0 }

    fun outboxCount(): Flow<Int> = watch { outboxSize() }

    fun outboxFlow(): Flow<List<OutboxEntity>> = watch { read { list("SELECT * FROM outbox ORDER BY seq", row = ::outboxRow) } }

    suspend fun outboxIds(): List<String> = read { list("SELECT id FROM outbox ORDER BY seq") { it.getString(1) } }

    suspend fun oldestOutbox(): Long? = read { one("SELECT MIN(created_at) AS oldest FROM outbox") { it.longOrNull("oldest") } }

    suspend fun deleteOutbox(id: String) = write { run("DELETE FROM outbox WHERE id = ?", id) }

    suspend fun markAttempt(ids: List<String>, error: String?) = write {
        ids.forEach { run("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?", error, it) }
    }

    /** A command the caisse answered: out of the outbox, into « À vérifier » when it needs a look. */
    suspend fun settle(id: String, review: ReviewItemEntity?, ref: OrderRefEntity?) = write {
        run("DELETE FROM outbox WHERE id = ?", id)
        review?.let { writeReview(it) }
        ref?.let {
            run(
                "INSERT OR REPLACE INTO order_refs (ref, offline_number, order_id, order_number, updated_at) VALUES (?, ?, ?, ?, ?)",
                it.ref, it.offlineNumber, it.orderId, it.orderNumber, it.updatedAt,
            )
            // The order taken offline now has its real check at the caisse (step 2 reads it).
            run("UPDATE local_orders SET synced_order_id = ?, synced_number = ? WHERE ref = ?", it.orderId, it.orderNumber, it.ref)
        }
    }

    // ---------------------------------------------------------------- « À vérifier »

    suspend fun saveReview(item: ReviewItemEntity) = write { writeReview(item) }

    private fun Connection.writeReview(r: ReviewItemEntity) {
        run(
            "INSERT OR REPLACE INTO review_items (command_id, seq, type, status, code, message, label, at, data_json, mapping_json, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            r.commandId, r.seq, r.type, r.status, r.code, r.message, r.label, r.at, r.dataJson, r.mappingJson, r.updatedAt,
        )
    }

    private fun reviewRow(rs: ResultSet) = ReviewItemEntity(
        rs.getString("command_id"), rs.getLong("seq"), rs.getString("type"), rs.getString("status"), rs.getString("code"), rs.getString("message"),
        rs.getString("label"), rs.getString("at"), rs.getString("data_json"), rs.getString("mapping_json"), rs.getLong("updated_at"),
    )

    suspend fun review(id: String): ReviewItemEntity? = read { one("SELECT * FROM review_items WHERE command_id = ?", id, row = ::reviewRow) }

    suspend fun reviews(): List<ReviewItemEntity> = read { list("SELECT * FROM review_items ORDER BY seq DESC", row = ::reviewRow) }

    fun reviewFlow(): Flow<List<ReviewItemEntity>> = watch { reviews() }

    fun reviewCount(): Flow<Int> = watch { read { one("SELECT COUNT(*) FROM review_items") { it.getInt(1) } ?: 0 } }

    suspend fun deleteReview(id: String) = write { run("DELETE FROM review_items WHERE command_id = ?", id) }

    // ---------------------------------------------------------------- order references

    suspend fun orderRef(ref: String): OrderRefEntity? = read {
        one("SELECT * FROM order_refs WHERE ref = ?", ref) { rs ->
            OrderRefEntity(rs.getString("ref"), rs.getString("offline_number"), rs.longOrNull("order_id"), rs.getString("order_number"), rs.getLong("updated_at"))
        }
    }

    /** Orders taken offline (step 2) the caisse has had for a day: forgotten here, unless an action of theirs still waits. */
    suspend fun pruneLocalOrders(before: Long) = write {
        run("DELETE FROM local_orders WHERE created_at < ? AND ref NOT IN (SELECT order_ref FROM outbox WHERE order_ref IS NOT NULL)", before)
    }
}

/** The handheld's Room schema (its version 3), with the PIN checks in their own table. */
private val SCHEMA_V1 = listOf(
    """CREATE TABLE IF NOT EXISTS device_state (
        id INTEGER PRIMARY KEY CHECK (id = 1), uuid TEXT NOT NULL, code TEXT, sync_key_sealed TEXT,
        key_refused INTEGER NOT NULL DEFAULT 0, next_seq INTEGER NOT NULL DEFAULT 1, next_offline_number INTEGER NOT NULL DEFAULT 1,
        clock_offset_ms INTEGER NOT NULL DEFAULT 0, last_heartbeat_at INTEGER, last_sync_at INTEGER)""",
    """CREATE TABLE IF NOT EXISTS snapshot_parts (
        part TEXT PRIMARY KEY, version TEXT, caisse_id INTEGER NOT NULL, user_id INTEGER NOT NULL, json TEXT NOT NULL, saved_at INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS open_checks (
        id INTEGER PRIMARY KEY, number TEXT NOT NULL, offline_number TEXT, table_id INTEGER, status TEXT NOT NULL,
        total REAL NOT NULL, json TEXT NOT NULL, saved_at INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS table_states (id INTEGER PRIMARY KEY, status TEXT NOT NULL, json TEXT NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS outbox (
        id TEXT PRIMARY KEY, seq INTEGER NOT NULL UNIQUE, type TEXT NOT NULL, actor_id INTEGER NOT NULL, at TEXT NOT NULL,
        data_json TEXT NOT NULL, label TEXT NOT NULL, created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
        last_error TEXT, order_ref TEXT)""",
    """CREATE TABLE IF NOT EXISTS review_items (
        command_id TEXT PRIMARY KEY, seq INTEGER NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL, code TEXT, message TEXT NOT NULL,
        label TEXT NOT NULL, at TEXT NOT NULL, data_json TEXT NOT NULL, mapping_json TEXT, updated_at INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS order_refs (
        ref TEXT PRIMARY KEY, offline_number TEXT, order_id INTEGER, order_number TEXT, updated_at INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS local_orders (
        local_id INTEGER PRIMARY KEY AUTOINCREMENT, ref TEXT NOT NULL UNIQUE, base_order_id INTEGER, offline_number TEXT,
        table_id INTEGER, table_code TEXT, pax INTEGER NOT NULL, order_type TEXT NOT NULL, status TEXT NOT NULL,
        lines_json TEXT NOT NULL, created_at INTEGER NOT NULL, synced_order_id INTEGER, synced_number TEXT, payments_json TEXT)""",
    """CREATE TABLE IF NOT EXISTS offline_access (
        id INTEGER PRIMARY KEY CHECK (id = 1), account_id INTEGER NOT NULL, account_name TEXT NOT NULL, account_verifier_sealed TEXT,
        payment_verifier_sealed TEXT, caisse_id INTEGER NOT NULL, caisse_name TEXT NOT NULL, caisse_menu_id INTEGER NOT NULL)""",
)
